package com.sidenote.app.capture

import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CaptureRecoveryHandoff(
    private val store: RecoveryDraftStore,
    private val processScope: CoroutineScope,
) {
    private val submissionLock = Any()
    private var activeSessionId = 0L
    private var tail: Job = CompletableDeferred(Unit)
    private var committedRecoveryCleanupPending = false

    fun openSession(): CaptureRecoverySession = synchronized(submissionLock) {
        CaptureRecoverySession(
            handoff = this,
            sessionId = ++activeSessionId,
        )
    }

    internal suspend fun load(sessionId: Long): RecoveryLoadResult =
        enqueueForActiveSession(
            sessionId = sessionId,
            staleResult = RecoveryLoadResult.Empty,
        ) {
            if (!committedRecoveryCleanupPending) {
                store.load()
            } else {
                try {
                    store.clear()
                    committedRecoveryCleanupPending = false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The process observed a confirmed append before cleanup failed. Never
                    // re-expose that known-stale mirror; a later load will retry the clear.
                }
                RecoveryLoadResult.Empty
            }
        }.await()

    internal suspend fun save(sessionId: Long, draft: RecoveryDraft) {
        enqueueSave(sessionId, draft).await()
    }

    internal suspend fun clear(sessionId: Long) {
        enqueueForActiveSession(sessionId, staleResult = false) {
            store.clear()
            committedRecoveryCleanupPending = false
            true
        }.await()
    }

    internal suspend fun reconcile(
        sessionId: Long,
        operation: suspend (RecoveryDraftStore) -> com.sidenote.app.data.documents.AppendResult,
    ): com.sidenote.app.data.documents.AppendResult = enqueueForActiveSession(
        sessionId,
        staleResult = com.sidenote.app.data.documents.AppendResult.Conflict,
    ) { operation(commitRecoveryStore()) }.await()

    internal fun enqueueSave(
        sessionId: Long,
        draft: RecoveryDraft,
    ): Deferred<Boolean> = enqueueForActiveSession(sessionId, staleResult = false) {
        store.save(draft)
        committedRecoveryCleanupPending = false
        true
    }

    private fun commitRecoveryStore(): RecoveryDraftStore = object : RecoveryDraftStore {
        override suspend fun load(): RecoveryLoadResult = store.load()

        override suspend fun save(draft: RecoveryDraft) {
            store.save(draft)
            committedRecoveryCleanupPending = false
        }

        override suspend fun clear() {
            committedRecoveryCleanupPending = true
            try {
                store.clear()
                committedRecoveryCleanupPending = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A confirmed append must not become a failed append because cleanup failed.
                // The pending marker is process-local and is retried before any later load.
            }
        }
    }

    private fun <T> enqueueForActiveSession(
        sessionId: Long,
        staleResult: T,
        operation: suspend () -> T,
    ): Deferred<T> = synchronized(submissionLock) {
        if (sessionId != activeSessionId) {
            return@synchronized CompletableDeferred(staleResult)
        }
        enqueueLocked(operation)
    }

    private fun <T> enqueueLocked(operation: suspend () -> T): Deferred<T> {
        val predecessor = tail
        val result = CompletableDeferred<T>()
        val task = processScope.launch {
            predecessor.join()
            try {
                result.complete(operation())
            } catch (error: CancellationException) {
                result.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        task.invokeOnCompletion { error ->
            if (error != null && !result.isCompleted) {
                result.completeExceptionally(error)
            }
        }
        tail = task
        return result
    }
}

class CaptureRecoverySession internal constructor(
    private val handoff: CaptureRecoveryHandoff,
    private val sessionId: Long,
) : RecoveryDraftStore {
    override suspend fun load(): RecoveryLoadResult = handoff.load(sessionId)

    override suspend fun save(draft: RecoveryDraft) {
        handoff.save(sessionId, draft)
    }

    override suspend fun clear() {
        handoff.clear(sessionId)
    }

    suspend fun reconcile(
        operation: suspend (RecoveryDraftStore) -> com.sidenote.app.data.documents.AppendResult,
    ): com.sidenote.app.data.documents.AppendResult = handoff.reconcile(sessionId, operation)

    fun flushInBackground(draft: RecoveryDraft): Deferred<Boolean> =
        handoff.enqueueSave(sessionId, draft)
}

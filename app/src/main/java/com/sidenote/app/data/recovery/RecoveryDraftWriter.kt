package com.sidenote.app.data.recovery

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecoveryDraftWriter(
    private val store: RecoveryDraftStore,
    private val scope: CoroutineScope,
    private val debounce: Duration,
    private val onPersistenceResult: (Boolean) -> Unit = {},
) {
    private val writeMutex = Mutex()
    private var pendingWrite: Job? = null

    fun onDraftChanged(draft: RecoveryDraft) {
        pendingWrite?.cancel()
        pendingWrite = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(debounce)
            writeMutex.withLock {
                persist(draft)
            }
        }
    }

    fun cancelPending() {
        pendingWrite?.cancel()
        pendingWrite = null
    }

    suspend fun flushOnStop(draft: RecoveryDraft) {
        val pending = pendingWrite
        pendingWrite = null
        pending?.cancelAndJoin()
        writeMutex.withLock {
            persist(draft)
        }
    }

    private suspend fun persist(draft: RecoveryDraft) {
        try {
            store.save(draft)
            onPersistenceResult(true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onPersistenceResult(false)
        }
    }
}

package com.sidenote.app.capture

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureRecoveryHandoffTest {
    @Test
    fun olderSuspendedFlushCannotOverwriteNewerSessionEditAndJobsTerminate() = runTest {
        val store = SuspendedFirstSaveStore()
        val processJob = SupervisorJob()
        val processScope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler))
        val handoff = CaptureRecoveryHandoff(store, processScope)

        try {
            val olderSession = handoff.openSession()
            val olderFlush = olderSession.flushInBackground(draft("older stop"))
            runCurrent()
            assertThat(store.firstSaveStarted.isCompleted).isTrue()

            val newerSession = handoff.openSession()
            val newerEdit = async { newerSession.save(draft("newer edit")) }
            val lateOlderFlush = olderSession.flushInBackground(draft("late older stop"))
            runCurrent()

            assertThat(newerEdit.isCompleted).isFalse()
            assertThat(lateOlderFlush.await()).isFalse()

            store.releaseFirstSave.complete(Unit)
            advanceUntilIdle()

            assertThat(store.persisted).isEqualTo(draft("newer edit"))
            assertThat(olderFlush.await()).isTrue()
            assertThat(newerEdit.isCompleted).isTrue()
            assertThat(processJob.children.toList()).isEmpty()
        } finally {
            processScope.cancel()
        }
    }

    @Test
    fun failedPostCommitClearIsMaskedFromNextSessionAndRetried() = runTest {
        val store = ThrowingClearStore(draft("already committed"))
        val processJob = SupervisorJob()
        val processScope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler))
        val handoff = CaptureRecoveryHandoff(store, processScope)

        try {
            val committingSession = handoff.openSession()
            val commit = async {
                committingSession.reconcile { ownedRecovery ->
                    ownedRecovery.save(draft("already committed"))
                    AppendResult.Success.also { ownedRecovery.clear() }
                }
            }
            advanceUntilIdle()

            assertThat(commit.await()).isEqualTo(AppendResult.Success)
            assertThat(store.clearCalls).isEqualTo(1)

            val nextSession = handoff.openSession()
            val maskedLoad = async { nextSession.load() }
            advanceUntilIdle()

            assertThat(maskedLoad.await()).isEqualTo(RecoveryLoadResult.Empty)
            assertThat(store.loadCalls).isEqualTo(0)
            assertThat(store.clearCalls).isEqualTo(2)

            store.throwOnClear = false
            val retriedLoad = async { nextSession.load() }
            advanceUntilIdle()

            assertThat(retriedLoad.await()).isEqualTo(RecoveryLoadResult.Empty)
            assertThat(store.persisted).isNull()
            assertThat(store.loadCalls).isEqualTo(0)
            assertThat(store.clearCalls).isEqualTo(3)
        } finally {
            processScope.cancel()
        }
    }

    private fun draft(text: String): RecoveryDraft = RecoveryDraft(
        text = text,
        selection = TextRange(text.length),
        voiceEnabled = false,
    )
}

private class SuspendedFirstSaveStore : RecoveryDraftStore {
    val firstSaveStarted = CompletableDeferred<Unit>()
    val releaseFirstSave = CompletableDeferred<Unit>()
    private val saveCalls = AtomicInteger()
    @Volatile var persisted: RecoveryDraft? = null

    override suspend fun load(): RecoveryLoadResult = persisted
        ?.let(RecoveryLoadResult::Draft)
        ?: RecoveryLoadResult.Empty

    override suspend fun save(draft: RecoveryDraft) {
        if (saveCalls.incrementAndGet() == 1) {
            firstSaveStarted.complete(Unit)
            releaseFirstSave.await()
        }
        persisted = draft
    }

    override suspend fun clear() {
        persisted = null
    }
}

private class ThrowingClearStore(
    @Volatile var persisted: RecoveryDraft?,
) : RecoveryDraftStore {
    var throwOnClear = true
    var clearCalls = 0
    var loadCalls = 0

    override suspend fun load(): RecoveryLoadResult {
        loadCalls += 1
        return persisted?.let(RecoveryLoadResult::Draft) ?: RecoveryLoadResult.Empty
    }

    override suspend fun save(draft: RecoveryDraft) {
        persisted = draft
    }

    override suspend fun clear() {
        clearCalls += 1
        if (throwOnClear) throw java.io.IOException("recovery unavailable")
        persisted = null
    }
}

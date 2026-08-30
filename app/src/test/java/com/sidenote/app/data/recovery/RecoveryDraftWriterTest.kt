package com.sidenote.app.data.recovery

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryDraftWriterTest {
    @Test
    fun draftChangesWriteOnlyTheLatestDraftAfterDebounce() = runTest {
        val store = RecordingRecoveryDraftStore()
        val writer = RecoveryDraftWriter(store, backgroundScope, 500.milliseconds)
        val first = draft("first")
        val latest = draft("latest")

        writer.onDraftChanged(first)
        advanceTimeBy(499)
        writer.onDraftChanged(latest)
        advanceTimeBy(499)

        assertThat(store.saved).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        assertThat(store.saved).containsExactly(latest)
    }

    @Test
    fun flushOnStopForcesLatestDraftWithoutWaitingForDebounce() = runTest {
        val store = RecordingRecoveryDraftStore()
        val writer = RecoveryDraftWriter(store, backgroundScope, 500.milliseconds)
        val stale = draft("stale")
        val latest = draft("latest")

        writer.onDraftChanged(stale)
        writer.flushOnStop(latest)
        advanceUntilIdle()

        assertThat(store.saved).containsExactly(latest)
    }

    private fun draft(text: String): RecoveryDraft =
        RecoveryDraft(text, TextRange(text.length), voiceEnabled = true)
}

private class RecordingRecoveryDraftStore : RecoveryDraftStore {
    val saved = mutableListOf<RecoveryDraft>()

    override suspend fun load(): RecoveryLoadResult = RecoveryLoadResult.Empty

    override suspend fun save(draft: RecoveryDraft) {
        saved += draft
    }

    override suspend fun clear() = Unit
}

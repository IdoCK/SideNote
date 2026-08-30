package com.sidenote.app.capture

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.RepositoryError
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test

class CaptureCoordinatorTest {
    private val instant = Instant.parse("2026-08-29T14:26:00Z")
    private val zone = ZoneId.of("America/New_York")
    private lateinit var repository: RecordingDocumentRepository
    private lateinit var recovery: RecordingRecoveryDraftStore
    private lateinit var speech: RecordingSpeechControl
    private lateinit var haptic: RecordingHapticConfirmation
    private lateinit var closer: RecordingCaptureCloser
    private lateinit var coordinator: CaptureCoordinator

    @Before
    fun setUp() {
        repository = RecordingDocumentRepository()
        recovery = RecordingRecoveryDraftStore()
        speech = RecordingSpeechControl()
        haptic = RecordingHapticConfirmation()
        closer = RecordingCaptureCloser()
        coordinator = CaptureCoordinator(
            repository = repository,
            recovery = recovery,
            saveMutex = Mutex(),
            clock = Clock.fixed(instant, zone),
            zone = zone,
            speech = speech,
            haptic = haptic,
            closer = closer,
        )
    }

    @Test
    fun defaultOffLaunchStartsWithVoiceDisabled() {
        coordinator.start(voiceDefaultOn = false, recovered = null)

        assertThat(coordinator.state.value.voiceEnabled).isFalse()
        assertThat(coordinator.state.value.draft.text).isEmpty()
    }

    @Test
    fun typingStopsVoiceAndPreservesSpeechText() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("hello")

        coordinator.onUserEdit(TextFieldValue("hello שלום", TextRange(10)))

        assertThat(coordinator.state.value.voiceEnabled).isFalse()
        assertThat(coordinator.state.value.draft.text).isEqualTo("hello שלום")
        assertThat(coordinator.state.value.speechOwnedRange).isNull()
        assertThat(speech.stopCalls).isEqualTo(1)
    }

    @Test
    fun typingMakesLateSpeechCallbacksUnableToOverwriteManualText() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("speech")
        coordinator.onUserEdit(TextFieldValue("speech plus typing", TextRange(18)))

        coordinator.onSpeechPartial("stale partial")
        coordinator.onSpeechFinal("stale final", Locale.ENGLISH)

        assertThat(coordinator.state.value.draft.text).isEqualTo("speech plus typing")
    }

    @Test
    fun blobOffOnToggleStopsThenReenablesVoice() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("usable partial")

        coordinator.onVoiceToggle()

        assertThat(coordinator.state.value.voiceEnabled).isFalse()
        assertThat(coordinator.state.value.draft.text).isEqualTo("usable partial")
        assertThat(coordinator.state.value.speechOwnedRange).isNull()
        assertThat(speech.stopCalls).isEqualTo(1)

        coordinator.onVoiceToggle()

        assertThat(coordinator.state.value.voiceEnabled).isTrue()
        assertThat(speech.stopCalls).isEqualTo(1)
    }

    @Test
    fun partialSpeechReplacesOnlyOwnedSpan() {
        coordinator.start(true, RecoveryDraft("prefix ", TextRange(7), true))

        coordinator.onSpeechPartial("one")
        coordinator.onSpeechPartial("one two")

        assertThat(coordinator.state.value.draft.text).isEqualTo("prefix one two")
        assertThat(coordinator.state.value.speechOwnedRange).isEqualTo(TextRange(7, 14))
    }

    @Test
    fun finalSpeechCommitsOwnedSpan() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("one")

        coordinator.onSpeechFinal("one two", Locale.ENGLISH)

        assertThat(coordinator.state.value.draft.text).isEqualTo("one two")
        assertThat(coordinator.state.value.draft.selection).isEqualTo(TextRange(7))
        assertThat(coordinator.state.value.speechOwnedRange).isNull()
    }

    @Test
    fun englishVoiceTagCommandBecomesVisibleTokenWithoutCommandProse() {
        coordinator.start(voiceDefaultOn = true, recovered = null)

        coordinator.onSpeechFinal(
            "buy paint tag project Home-Renovation today tag project Errands",
            Locale.ENGLISH,
        )

        assertThat(coordinator.state.value.draft.text)
            .isEqualTo("@Home-Renovation @Errands buy paint today")
    }

    @Test
    fun hebrewVoiceTagCommandBecomesVisibleTokenWithoutCommandProse() {
        coordinator.start(voiceDefaultOn = true, recovered = null)

        coordinator.onSpeechFinal(
            "לקנות צבע תייג פרויקט שיפוץ-הבית",
            Locale.forLanguageTag("he"),
        )

        assertThat(coordinator.state.value.draft.text).isEqualTo("@שיפוץ-הבית לקנות צבע")
    }

    @Test
    fun nonCommandSpeechRemainsUnchanged() {
        coordinator.start(voiceDefaultOn = true, recovered = null)

        coordinator.onSpeechFinal("meet at noon", Locale.ENGLISH)

        assertThat(coordinator.state.value.draft.text).isEqualTo("meet at noon")
    }

    @Test
    fun failedSpeechLeavesUsablePartialText() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("usable partial")

        coordinator.onSpeechFailure()

        assertThat(coordinator.state.value.draft.text).isEqualTo("usable partial")
        assertThat(coordinator.state.value.speechOwnedRange).isNull()
        assertThat(coordinator.state.value.voiceEnabled).isFalse()
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.SpeechUnavailable)
    }

    @Test
    fun simultaneousSignalsAppendExactlyOnce() = runTest {
        coordinator.start(true, RecoveryDraft("one thought", TextRange(11), true))

        coroutineScope {
            CompletionSignal.entries.forEach { signal ->
                launch { coordinator.complete(signal) }
            }
        }

        assertThat(repository.appends).containsExactly(AppendCall("one thought", instant, zone))
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saved)
    }

    @Test
    fun completionStopsVoiceSoLateSpeechCannotChangeTheSavedDraft() = runTest {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("stable partial")
        repository.beforeResult = { coordinator.onSpeechPartial("late partial") }

        coordinator.complete(CompletionSignal.ScreenOff)

        assertThat(repository.appends).containsExactly(AppendCall("stable partial", instant, zone))
        assertThat(coordinator.state.value.draft.text).isEqualTo("stable partial")
        assertThat(coordinator.state.value.voiceEnabled).isFalse()
        assertThat(speech.stopCalls).isEqualTo(1)
    }

    @Test
    fun blankCompletionClosesWithoutAppend() = runTest {
        coordinator.start(false, RecoveryDraft(" \n\t", TextRange(3), false))

        coordinator.complete(CompletionSignal.Backgrounded)

        assertThat(repository.appends).isEmpty()
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(0)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    @Test
    fun appendFailurePreservesRecoveryDraftAndVisibleScreen() = runTest {
        repository.result = AppendResult.Failure(RepositoryError.PermissionLost)
        coordinator.start(true, RecoveryDraft("keep me", TextRange(7), true))

        coordinator.complete(CompletionSignal.ScreenOff)

        assertThat(repository.appends).containsExactly(AppendCall("keep me", instant, zone))
        assertThat(coordinator.state.value.draft.text).isEqualTo("keep me")
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.SaveFailed)
        assertThat(recovery.clearCalls).isEqualTo(0)
        assertThat(haptic.confirmCalls).isEqualTo(0)
        assertThat(closer.closeCalls).isEqualTo(0)
    }

    @Test
    fun conflictAlsoPreservesRecoveryDraftAndVisibleScreen() = runTest {
        repository.result = AppendResult.Conflict
        coordinator.start(true, RecoveryDraft("retry me", TextRange(8), true))

        coordinator.complete(CompletionSignal.FaceDown)

        assertThat(coordinator.state.value.draft.text).isEqualTo("retry me")
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.SaveFailed)
        assertThat(recovery.clearCalls).isEqualTo(0)
        assertThat(haptic.confirmCalls).isEqualTo(0)
        assertThat(closer.closeCalls).isEqualTo(0)
    }

    @Test
    fun failedAppendCanBeRetriedAndOnlySuccessCloses() = runTest {
        repository.result = AppendResult.Failure(RepositoryError.WriteFailed)
        coordinator.start(true, RecoveryDraft("retry me", TextRange(8), true))
        coordinator.complete(CompletionSignal.Backgrounded)
        repository.result = AppendResult.Success

        coordinator.complete(CompletionSignal.RepeatedLaunch)

        assertThat(repository.appends).hasSize(2)
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    @Test
    fun discardNeverAppends() = runTest {
        coordinator.start(true, RecoveryDraft("do not save", TextRange(11), true))

        coordinator.discard()

        assertThat(repository.appends).isEmpty()
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
        assertThat(coordinator.state.value.draft.text).isEmpty()
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Discarded)
    }
}

private data class AppendCall(
    val text: String,
    val committedAt: Instant,
    val zone: ZoneId,
)

private class RecordingDocumentRepository : DocumentRepository {
    val appends = mutableListOf<AppendCall>()
    var result: AppendResult = AppendResult.Success
    var beforeResult: (() -> Unit)? = null

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult {
        appends += AppendCall(text, committedAt, zone)
        beforeResult?.invoke()
        yield()
        return result
    }

    override suspend fun days(): List<ParsedDailyFile> = emptyList()

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Not used by capture tests")

    override suspend fun uncheckedCount(): Int = 0
}

private class RecordingRecoveryDraftStore : RecoveryDraftStore {
    var clearCalls = 0

    override suspend fun load(): RecoveryLoadResult = RecoveryLoadResult.Empty

    override suspend fun save(draft: RecoveryDraft) = Unit

    override suspend fun clear() {
        clearCalls += 1
    }
}

private class RecordingSpeechControl : SpeechControl {
    var stopCalls = 0

    override fun stop() {
        stopCalls += 1
    }
}

private class RecordingHapticConfirmation : HapticConfirmation {
    var confirmCalls = 0

    override fun confirm() {
        confirmCalls += 1
    }
}

private class RecordingCaptureCloser : CaptureCloser {
    var closeCalls = 0

    override fun close() {
        closeCalls += 1
    }
}

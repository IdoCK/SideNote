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
import com.sidenote.app.notification.NotificationRefreshResult
import com.sidenote.app.notification.NotificationRefresher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
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
    private lateinit var notificationRefresher: RecordingNotificationRefresher
    private lateinit var coordinator: CaptureCoordinator

    @Before
    fun setUp() {
        repository = RecordingDocumentRepository()
        recovery = RecordingRecoveryDraftStore()
        speech = RecordingSpeechControl()
        haptic = RecordingHapticConfirmation()
        closer = RecordingCaptureCloser()
        notificationRefresher = RecordingNotificationRefresher()
        coordinator = CaptureCoordinator(
            repository = repository,
            recovery = recovery,
            saveMutex = Mutex(),
            clock = Clock.fixed(instant, zone),
            zone = zone,
            speech = speech,
            haptic = haptic,
            closer = closer,
            notificationRefresher = notificationRefresher,
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
        coordinator.onSpeechRms(0.8f)

        coordinator.onSpeechFinal("one two", Locale.ENGLISH)

        assertThat(coordinator.state.value.draft.text).isEqualTo("one two")
        assertThat(coordinator.state.value.draft.selection).isEqualTo(TextRange(7))
        assertThat(coordinator.state.value.speechOwnedRange).isNull()
        assertThat(coordinator.state.value.rms).isEqualTo(0f)
    }

    @Test
    fun speechRmsIsClampedAndAcceptedOnlyWhileVoiceIsReady() = runTest {
        coordinator.start(voiceDefaultOn = true, recovered = null)

        coordinator.onSpeechRms(1.7f)
        assertThat(coordinator.state.value.rms).isEqualTo(1f)

        coordinator.onUserEdit(TextFieldValue("typing", TextRange(6)))
        coordinator.onSpeechRms(0.9f)
        assertThat(coordinator.state.value.rms).isEqualTo(0f)

        coordinator.onVoiceToggle()
        coordinator.onSpeechRms(-0.4f)
        assertThat(coordinator.state.value.rms).isEqualTo(0f)

        coordinator.onSpeechRms(0.45f)
        assertThat(coordinator.state.value.rms).isEqualTo(0.45f)

        coordinator.onSpeechStopped()
        assertThat(coordinator.state.value.rms).isEqualTo(0f)

        val suspendedAppend = repository.suspendNextAppend()
        coordinator.onUserEdit(TextFieldValue("saving", TextRange(6)))
        coordinator.onVoiceToggle()
        val completion = launch { coordinator.complete(CompletionSignal.ScreenOff) }
        suspendedAppend.started.await()
        coordinator.onSpeechRms(0.95f)
        assertThat(coordinator.state.value.rms).isEqualTo(0f)
        suspendedAppend.release.complete(Unit)
        completion.join()
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
    fun userEditDuringSuspendedAppendIsRejected() = runTest {
        val suspendedAppend = repository.suspendNextAppend()
        coordinator.start(true, RecoveryDraft("stable draft", TextRange(12), true))
        val completion = launch { coordinator.complete(CompletionSignal.ScreenOff) }
        suspendedAppend.started.await()

        coordinator.onUserEdit(TextFieldValue("late edit", TextRange(9)))

        assertThat(coordinator.state.value.draft.text).isEqualTo("stable draft")
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saving)
        suspendedAppend.release.complete(Unit)
        completion.join()
        assertThat(repository.appends).containsExactly(AppendCall("stable draft", instant, zone))
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    @Test
    fun voiceToggleDuringSuspendedAppendIsRejected() = runTest {
        val suspendedAppend = repository.suspendNextAppend()
        coordinator.start(false, RecoveryDraft("stable draft", TextRange(12), false))
        val completion = launch { coordinator.complete(CompletionSignal.FaceDown) }
        suspendedAppend.started.await()

        coordinator.onVoiceToggle()

        assertThat(coordinator.state.value.voiceEnabled).isFalse()
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saving)
        suspendedAppend.release.complete(Unit)
        completion.join()
        assertThat(repository.appends).containsExactly(AppendCall("stable draft", instant, zone))
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saved)
    }

    @Test
    fun speechFailureDuringSuspendedAppendCannotReplaceSavingState() = runTest {
        val suspendedAppend = repository.suspendNextAppend()
        coordinator.start(true, RecoveryDraft("stable draft", TextRange(12), true))
        val completion = launch { coordinator.complete(CompletionSignal.Backgrounded) }
        suspendedAppend.started.await()

        coordinator.onSpeechFailure()

        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saving)
        assertThat(coordinator.state.value.draft.text).isEqualTo("stable draft")
        suspendedAppend.release.complete(Unit)
        completion.join()
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saved)
    }

    @Test
    fun stopPortSeesDisabledSavingStateBeforeReentrantCallback() = runTest {
        var stateObservedByStop: CaptureState? = null
        speech.onStop = {
            stateObservedByStop = coordinator.state.value
            coordinator.onSpeechFailure()
        }
        coordinator.start(true, RecoveryDraft("stable draft", TextRange(12), true))

        coordinator.complete(CompletionSignal.RepeatedLaunch)

        assertThat(stateObservedByStop?.voiceEnabled).isFalse()
        assertThat(stateObservedByStop?.status).isEqualTo(CaptureStatus.Saving)
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saved)
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
        assertThat(notificationRefresher.refreshCalls).isEqualTo(0)
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
        assertThat(notificationRefresher.refreshCalls).isEqualTo(0)
    }

    @Test
    fun confirmedAppendClearsRecoveryThenRefreshesBeforeClosingItsOwner() = runTest {
        repository.beforeResult = { assertThat(notificationRefresher.refreshCalls).isEqualTo(0) }
        notificationRefresher.onRefresh = {
            assertThat(repository.appends).hasSize(1)
            assertThat(recovery.clearCalls).isEqualTo(1)
            assertThat(haptic.confirmCalls).isEqualTo(1)
            assertThat(closer.closeCalls).isEqualTo(0)
        }
        coordinator.start(false, RecoveryDraft("refresh me", TextRange(10), false))

        coordinator.complete(CompletionSignal.RepeatedLaunch)

        assertThat(notificationRefresher.refreshCalls).isEqualTo(1)
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    @Test
    fun unavailableNotificationRefreshDoesNotUndoConfirmedAppend() = runTest {
        notificationRefresher.failure = IllegalStateException("notifications unavailable")
        coordinator.start(false, RecoveryDraft("still saved", TextRange(11), false))

        coordinator.complete(CompletionSignal.RepeatedLaunch)

        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saved)
        assertThat(notificationRefresher.refreshCalls).isEqualTo(1)
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    @Test
    fun cancellationDuringNotificationRefreshCannotLeaveACommittedDraftRecoverable() = runTest {
        val suspendedRefresh = notificationRefresher.suspendNextRefresh()
        coordinator.start(false, RecoveryDraft("save once", TextRange(9), false))
        val completion = launch { coordinator.complete(CompletionSignal.ScreenOff) }
        suspendedRefresh.started.await()

        completion.cancel()
        completion.join()

        assertThat(repository.appends).containsExactly(AppendCall("save once", instant, zone))
        assertThat(coordinator.state.value.status).isEqualTo(CaptureStatus.Saved)
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(0)

        coordinator.complete(CompletionSignal.RepeatedLaunch)
        assertThat(repository.appends).hasSize(1)
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
    fun delayedSpeechFailureAfterTypingIsIgnored() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("speech")
        coordinator.onUserEdit(TextFieldValue("speech plus typing", TextRange(18)))
        val typedState = coordinator.state.value

        coordinator.onSpeechFailure()

        assertThat(coordinator.state.value).isEqualTo(typedState)
    }

    @Test
    fun delayedSpeechFailureAfterVoiceOffIsIgnored() {
        coordinator.start(voiceDefaultOn = true, recovered = null)
        coordinator.onSpeechPartial("usable partial")
        coordinator.onVoiceToggle()
        val voiceOffState = coordinator.state.value

        coordinator.onSpeechFailure()

        assertThat(coordinator.state.value).isEqualTo(voiceOffState)
    }

    @Test
    fun delayedSpeechFailureAfterAppendFailurePreservesSaveFailed() = runTest {
        repository.result = AppendResult.Failure(RepositoryError.WriteFailed)
        coordinator.start(true, RecoveryDraft("retry me", TextRange(8), true))
        coordinator.complete(CompletionSignal.Backgrounded)
        val failedState = coordinator.state.value

        coordinator.onSpeechFailure()

        assertThat(failedState.status).isEqualTo(CaptureStatus.SaveFailed)
        assertThat(coordinator.state.value).isEqualTo(failedState)
        assertThat(recovery.clearCalls).isEqualTo(0)
        assertThat(closer.closeCalls).isEqualTo(0)
    }

    @Test
    fun successfulCompletionRejectsAllLateEvents() = runTest {
        coordinator.start(true, RecoveryDraft("saved draft", TextRange(11), true))
        coordinator.complete(CompletionSignal.ScreenOff)
        val savedState = coordinator.state.value

        sendAllLateEvents()

        assertThat(coordinator.state.value).isEqualTo(savedState)
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    @Test
    fun blankCompletionRejectsAllLateEvents() = runTest {
        coordinator.start(false, RecoveryDraft(" \n", TextRange(2), false))
        coordinator.complete(CompletionSignal.Backgrounded)
        val closedState = coordinator.state.value

        sendAllLateEvents()

        assertThat(coordinator.state.value).isEqualTo(closedState)
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(0)
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

    @Test
    fun discardRejectsAllLateEvents() = runTest {
        coordinator.start(true, RecoveryDraft("discarded draft", TextRange(15), true))
        coordinator.discard()
        val discardedState = coordinator.state.value

        sendAllLateEvents()

        assertThat(coordinator.state.value).isEqualTo(discardedState)
        assertThat(repository.appends).isEmpty()
        assertThat(recovery.clearCalls).isEqualTo(1)
        assertThat(haptic.confirmCalls).isEqualTo(1)
        assertThat(closer.closeCalls).isEqualTo(1)
    }

    private fun sendAllLateEvents() {
        coordinator.onUserEdit(TextFieldValue("late edit", TextRange(9)))
        coordinator.onVoiceToggle()
        coordinator.onSpeechPartial("late partial")
        coordinator.onSpeechFinal("late final", Locale.ENGLISH)
        coordinator.onSpeechRms(0.9f)
        coordinator.onSpeechFailure()
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
    private var suspendedAppend: SuspendedAppend? = null

    fun suspendNextAppend(): SuspendedAppend = SuspendedAppend(
        started = CompletableDeferred(),
        release = CompletableDeferred(),
    ).also { suspendedAppend = it }

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult {
        appends += AppendCall(text, committedAt, zone)
        beforeResult?.invoke()
        suspendedAppend?.let { suspension ->
            suspension.started.complete(Unit)
            suspension.release.await()
            suspendedAppend = null
        }
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

private data class SuspendedAppend(
    val started: CompletableDeferred<Unit>,
    val release: CompletableDeferred<Unit>,
)

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
    var onStop: (() -> Unit)? = null

    override fun stop() {
        stopCalls += 1
        onStop?.invoke()
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

private class RecordingNotificationRefresher : NotificationRefresher {
    var refreshCalls = 0
    var onRefresh: (() -> Unit)? = null
    var failure: RuntimeException? = null
    private var suspendedRefresh: SuspendedRefresh? = null

    fun suspendNextRefresh(): SuspendedRefresh = SuspendedRefresh(
        started = CompletableDeferred(),
        release = CompletableDeferred(),
    ).also { suspendedRefresh = it }

    override suspend fun refresh(): NotificationRefreshResult {
        refreshCalls += 1
        onRefresh?.invoke()
        suspendedRefresh?.let { suspension ->
            suspension.started.complete(Unit)
            try {
                suspension.release.await()
            } finally {
                suspendedRefresh = null
            }
        }
        failure?.let { throw it }
        return NotificationRefreshResult.Removed
    }
}

private data class SuspendedRefresh(
    val started: CompletableDeferred<Unit>,
    val release: CompletableDeferred<Unit>,
)

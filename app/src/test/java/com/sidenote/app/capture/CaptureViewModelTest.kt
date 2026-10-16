package com.sidenote.app.capture

import android.content.Intent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.recovery.RecoveryReadError
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun clearingHostAfterWriteBeforeIoReturnStillReconcilesRecovery() = runTest(mainDispatcher) {
        val io = QueuedIoDispatcher()
        val recovery = RecordingRecoveryStore()
        var writes = 0
        val repository = object : DocumentRepository by NoOpDocumentRepository {
            override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult {
                writes++
                return AppendResult.Success
            }
        }
        val viewModel = viewModel(SessionRecordingSpeechEngine(), this, recovery, repository, io)
        val owner = ViewModelStore().apply { put("capture", viewModel) }
        viewModel.start(Intent(), AppSettings(treeUri = null, voiceOnAtLaunch = false))
        runCurrent()
        viewModel.onUserEdit(TextFieldValue("save once"))
        runCurrent()
        viewModel.complete(CompletionSignal.ScreenOff)
        runCurrent()
        io.runPending()
        assertThat(writes).isEqualTo(1)
        owner.clear()
        runCurrent()
        io.runPending()
        runCurrent()
        assertThat(recovery.clearCount).isEqualTo(1)
    }

    @Test
    fun unreadableRecoveryIsNeverClearedByBlankCompletionOrOverwrittenByTyping() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val recovery = RecordingRecoveryStore().apply {
            loadResult = RecoveryLoadResult.ReadFailure(RecoveryReadError.Unavailable)
        }
        val viewModel = viewModel(speech, this, recovery)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        viewModel.complete(CompletionSignal.ScreenOff)
        advanceUntilIdle()
        viewModel.onUserEdit(TextFieldValue("replacement"))
        viewModel.onCaptureStopped(false)
        advanceUntilIdle()
        assertThat(recovery.clearCount).isEqualTo(0)
        assertThat(recovery.saved).isEmpty()
        assertThat(speech.listeners).isEmpty()
        assertThat(viewModel.state.value.draft.text).isEmpty()
    }

    @Test
    fun failedTemporaryMirrorDoesNotPreventExplicitMarkdownCompletion() = runTest(mainDispatcher) {
        val recovery = RecordingRecoveryStore().apply { throwOnSave = true }
        val viewModel = viewModel(SessionRecordingSpeechEngine(), this, recovery)
        viewModel.start(Intent())
        advanceUntilIdle()
        viewModel.onUserEdit(TextFieldValue("in memory"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.draft.text).isEqualTo("in memory")
        viewModel.complete(CompletionSignal.ScreenOff)
        advanceUntilIdle()
        assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.Saved)
        assertThat(recovery.clearCount).isEqualTo(1)
    }

    @Test
    fun immediateCompletionSurfacesMirrorFailureWhenMarkdownAlsoCannotBeSaved() =
        runTest(mainDispatcher) {
            val recovery = RecordingRecoveryStore().apply { throwOnSave = true }
            val repository = object : DocumentRepository by NoOpDocumentRepository {
                override suspend fun append(
                    text: String,
                    committedAt: Instant,
                    zone: ZoneId,
                ): AppendResult = AppendResult.Failure(
                    com.sidenote.app.data.documents.RepositoryError.WriteFailed,
                )
            }
            val viewModel = viewModel(
                SessionRecordingSpeechEngine(),
                this,
                recovery,
                repository,
            )
            viewModel.start(Intent())
            advanceUntilIdle()
            viewModel.onUserEdit(TextFieldValue("only in memory"))
            runCurrent()

            viewModel.complete(CompletionSignal.ScreenOff)
            advanceUntilIdle()

            assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.SaveFailed)
            assertThat(viewModel.state.value.recoveryWriteFailed).isTrue()
            assertThat(recovery.clearCount).isEqualTo(0)
        }

    @Test
    fun discardCanRetryAfterRecoveryClearFails() = runTest(mainDispatcher) {
        val recovery = RecordingRecoveryStore().apply {
            loadResult = RecoveryLoadResult.Draft(
                RecoveryDraft("discard safely", TextRange(14), voiceEnabled = false),
            )
            throwOnClear = true
        }
        val viewModel = viewModel(SessionRecordingSpeechEngine(), this, recovery)
        viewModel.start(Intent())
        advanceUntilIdle()

        viewModel.discard()
        advanceUntilIdle()
        assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.Ready)
        assertThat(viewModel.state.value.recoveryWriteFailed).isTrue()

        recovery.throwOnClear = false
        viewModel.discard()
        advanceUntilIdle()
        assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.Discarded)
        assertThat(recovery.clearCount).isEqualTo(1)
    }

    @Test
    fun repeatedStartDoesNotReplaceAnActiveRecognitionSession() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        assertThat(speech.listeners).hasSize(1)
    }

    @Test
    fun transientBusyRetriesWithoutRequiringMoreCircleTaps() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        repeat(5) {
            speech.listeners.last().onUnavailable(SpeechFailure.Busy)
            advanceUntilIdle()
            assertThat(viewModel.state.value.voiceEnabled).isTrue()
        }
        assertThat(speech.listeners).hasSize(6)
        speech.listeners.last().onPartial("working on the first activation")
        runCurrent()
        assertThat(viewModel.state.value.draft.text).isEqualTo("working on the first activation")
        viewModel.onVoiceToggle()
        advanceUntilIdle()
        assertThat(viewModel.state.value.voiceEnabled).isFalse()
        assertThat(speech.listeners).hasSize(6)
    }

    @Test
    fun repeatedStartDuringSupportLookupDoesNotStrandListeningIntent() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<SpeechAvailability>()
        val speech = SessionRecordingSpeechEngine().apply { supportGate = gate }
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        runCurrent()
        viewModel.onCaptureStarted()
        runCurrent()
        gate.complete(SpeechAvailability.Available)
        advanceUntilIdle()
        assertThat(speech.listeners).hasSize(1)
        speech.listeners.single().onPartial("still listening")
        runCurrent()
        assertThat(viewModel.state.value.draft.text).isEqualTo("still listening")
    }

    @Test
    fun stopTapDoesNotWaitForSupportAndLateSupportCannotStartMicrophone() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<SpeechAvailability>()
        val speech = SessionRecordingSpeechEngine().apply { supportGate = gate }
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        runCurrent()
        viewModel.onVoiceToggle()
        runCurrent()
        assertThat(viewModel.state.value.voiceEnabled).isFalse()
        gate.complete(SpeechAvailability.Available)
        advanceUntilIdle()
        assertThat(speech.listeners).isEmpty()
    }

    @Test
    fun completedUtteranceDoesNotRecheckSupportAndLoseNextSentence() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        speech.supportGate = CompletableDeferred()
        speech.listeners.last().onFinal("first sentence")
        advanceTimeBy(1000)
        runCurrent()
        assertThat(speech.listeners).hasSize(2)
        speech.listeners.last().onFinal("second sentence")
        runCurrent()
        assertThat(viewModel.state.value.draft.text).isEqualTo("first sentence second sentence")
        viewModel.onCaptureStopped(false)
        advanceUntilIdle()
    }

    @Test
    fun physicalCompletionDoesNotWaitForStartupSupport() = runTest(mainDispatcher) {
        listOf(CompletionSignal.ScreenOff, CompletionSignal.FaceDown).forEach { signal ->
            val speech = SessionRecordingSpeechEngine().apply { supportGate = CompletableDeferred() }
            val viewModel = viewModel(speech, this)
            viewModel.start(Intent())
            runCurrent()
            viewModel.onUserEdit(TextFieldValue("keep this"))
            runCurrent()
            viewModel.onCaptureStarted()
            viewModel.onVoiceToggle()
            runCurrent()
            viewModel.complete(signal)
            runCurrent()
            assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.Saved)
            advanceUntilIdle()
            assertThat(speech.listeners).isEmpty()
        }
    }

    @Test
    fun focusPauseIsIdempotentAndCannotToggleVoiceBackOn() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        viewModel.onVoicePause()
        viewModel.onVoicePause()
        advanceUntilIdle()
        assertThat(viewModel.state.value.voiceEnabled).isFalse()
        assertThat(speech.listeners).hasSize(1)
    }

    @Test
    fun lateReadyCannotShowListeningAfterStopAndPermissionFailureIsNotRetried() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        val listener = speech.listeners.single()
        listener.onReady()
        runCurrent()
        assertThat(viewModel.state.value.voicePhase).isEqualTo(VoicePhase.Listening)
        listener.onUnavailable(SpeechFailure.Permission)
        runCurrent()
        val stopped = viewModel.state.value
        listener.onReady()
        listener.onPartial("late")
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(stopped)
        assertThat(stopped.voiceEnabled).isFalse()
        assertThat(speech.listeners).hasSize(1)
    }

    @Test
    fun supportTimeoutCanRecoverWithoutAnotherTap() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<SpeechAvailability>()
        val speech = SessionRecordingSpeechEngine().apply { supportGate = gate }
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        runCurrent()
        advanceTimeBy(3100)
        runCurrent()
        assertThat(viewModel.state.value.voiceEnabled).isTrue()
        assertThat(viewModel.state.value.voicePhase).isEqualTo(VoicePhase.Retrying)
        gate.complete(SpeechAvailability.Available)
        advanceTimeBy(1500)
        runCurrent()
        assertThat(speech.listeners).hasSize(1)
        speech.listeners.single().onPartial("recovered")
        runCurrent()
        assertThat(viewModel.state.value.draft.text).isEqualTo("recovered")
        viewModel.onVoicePause()
        advanceUntilIdle()
    }

    @Test
    fun temporaryNetworkFailureKeepsListeningIntentAndStopCancelsRetry() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        speech.listeners.last().onUnavailable(SpeechFailure.Network)
        runCurrent()
        assertThat(viewModel.state.value.voiceEnabled).isTrue()
        viewModel.onVoiceToggle()
        advanceUntilIdle()
        assertThat(speech.listeners).hasSize(1)
        assertThat(viewModel.state.value.voiceEnabled).isFalse()
    }

    @Test
    fun typedOnlySupportNeverStartsCaptureRecognitionAndTypingRemainsAvailable() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine().apply { availability = SpeechAvailability.TypedOnly }
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        assertThat(speech.listeners).isEmpty()
        assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.SpeechUnavailable)
        viewModel.onUserEdit(TextFieldValue("typed safely"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.draft.text).isEqualTo("typed safely")
    }

    @Test
    fun twoUtterancesContinueWithASeparatorAndStaleCallbacksAreIgnored() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        val first = speech.listeners.single()
        first.onFinal("first thought")
        advanceUntilIdle()
        assertThat(speech.listeners).hasSize(2)
        first.onPartial("stale")
        speech.listeners.last().onPartial("second")
        runCurrent()
        assertThat(viewModel.state.value.draft.text).isEqualTo("first thought second")
        speech.listeners.last().onFinal("second thought")
        advanceUntilIdle()
        assertThat(viewModel.state.value.draft.text).isEqualTo("first thought second thought")
    }

    @Test
    fun normalSilenceRestartsWithoutPermanentUnavailableState() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        speech.listeners.last().onUnavailable(SpeechFailure.SpeechTimeout)
        advanceUntilIdle()
        assertThat(viewModel.state.value.voiceEnabled).isTrue()
        assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.Ready)
        assertThat(speech.listeners).hasSize(2)
    }

    @Test
    fun pendingUtteranceRestartCannotSurviveTypingOffDiscardCompletionOrPause() = runTest(mainDispatcher) {
        val actions: List<(CaptureViewModel) -> Unit> = listOf(
            { it.onUserEdit(TextFieldValue("typed")) },
            { it.onVoiceToggle() },
            { it.discard() },
            { it.complete(CompletionSignal.ScreenOff) },
            { it.onCaptureStopped(false) },
        )
        actions.forEach { action ->
            val speech = SessionRecordingSpeechEngine()
            val viewModel = viewModel(speech, this)
            viewModel.start(Intent())
            viewModel.onCaptureStarted()
            advanceUntilIdle()
            speech.listeners.last().onFinal("first")
            runCurrent()
            action(viewModel)
            advanceUntilIdle()
            assertThat(speech.listeners).hasSize(1)
        }
    }

    @Test
    fun terminalSpeechDuringCompletionReachesCoordinatorWithoutEventMutexDeadlock() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        speech.onFinish = { speech.listeners.last().onFinal("finished thought") }
        viewModel.complete(CompletionSignal.ScreenOff)
        advanceUntilIdle()
        assertThat(viewModel.state.value.draft.text).isEqualTo("finished thought")
        assertThat(viewModel.state.value.status).isEqualTo(CaptureStatus.Saved)
    }

    @Test
    fun onlyTheCurrentRecognitionSessionCanPublishRms() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()

        val firstSession = speech.listeners.single()
        firstSession.onRms(0.6f)
        advanceUntilIdle()
        assertThat(viewModel.state.value.rms).isEqualTo(0.6f)

        viewModel.onVoiceToggle()
        advanceUntilIdle()
        viewModel.onVoiceToggle()
        advanceUntilIdle()
        assertThat(speech.listeners).hasSize(2)

        firstSession.onRms(0.95f)
        speech.listeners.last().onRms(0.35f)
        advanceUntilIdle()

        assertThat(viewModel.state.value.rms).isEqualTo(0.35f)
    }

    @Test
    fun stoppingCaptureClearsRmsAndRejectsLateCallback() = runTest(mainDispatcher) {
        val speech = SessionRecordingSpeechEngine()
        val viewModel = viewModel(speech, this)
        viewModel.start(Intent())
        viewModel.onCaptureStarted()
        advanceUntilIdle()
        val listener = speech.listeners.single()
        listener.onRms(0.6f)
        advanceUntilIdle()
        assertThat(viewModel.state.value.rms).isEqualTo(0.6f)

        viewModel.onCaptureStopped(completeIfBackgrounded = false)
        advanceUntilIdle()

        assertThat(viewModel.state.value.rms).isEqualTo(0f)
        listener.onRms(0.95f)
        advanceUntilIdle()
        assertThat(viewModel.state.value.rms).isEqualTo(0f)
    }

    @Test
    fun sustainedRmsDoesNotRescheduleCancelOrDuplicateRecoveryOfSpeechDraft() =
        runTest(mainDispatcher) {
            val speech = SessionRecordingSpeechEngine()
            val recovery = RecordingRecoveryStore()
            val viewModel = viewModel(speech, this, recovery)
            viewModel.start(Intent())
            viewModel.onCaptureStarted()
            advanceUntilIdle()
            val listener = speech.listeners.single()

            listener.onPartial("@Home persist")
            runCurrent()
            repeat(5) { index ->
                advanceTimeBy(40)
                listener.onRms((index + 1) / 10f)
                runCurrent()
            }
            advanceTimeBy(49)
            runCurrent()
            assertThat(recovery.saved).isEmpty()

            advanceTimeBy(1)
            runCurrent()

            assertThat(recovery.saved).containsExactly(
                RecoveryDraft(
                    text = "@Home persist",
                    selection = TextRange(13),
                    voiceEnabled = true,
                ),
            )

            repeat(5) { index ->
                listener.onRms((index + 5) / 10f)
                advanceTimeBy(40)
                runCurrent()
            }
            advanceTimeBy(500)
            runCurrent()
            assertThat(recovery.saved).containsExactly(
                RecoveryDraft(
                    text = "@Home persist",
                    selection = TextRange(13),
                    voiceEnabled = true,
                ),
            )
        }

    @Test
    fun projectSuggestionsNeverReadHistoryUntilUnlockedAndRefreshFromMarkdownSource() =
        runTest(mainDispatcher) {
            val repository = SuggestionRepository(
                listOf(day("2026-08-30", "- [ ] **09:00** Plan @Home\n")),
            )
            val viewModel = viewModel(
                SessionRecordingSpeechEngine(),
                this,
                repository = repository,
            )
            viewModel.start(Intent())
            advanceUntilIdle()

            viewModel.refreshProjectSuggestions(unlocked = false)
            advanceUntilIdle()
            assertThat(repository.dayReads).isEqualTo(0)
            assertThat(viewModel.state.value.projectSuggestions).isEmpty()

            viewModel.onUserEdit(
                TextFieldValue(
                    text = "For @ho",
                    selection = TextRange(7),
                    composition = TextRange(4, 7),
                ),
            )
            advanceUntilIdle()
            viewModel.refreshProjectSuggestions(unlocked = true)
            advanceUntilIdle()

            assertThat(repository.dayReads).isEqualTo(1)
            assertThat(viewModel.state.value.projectSuggestions.map { it.display })
                .containsExactly("Home")
            assertThat(viewModel.state.value.draft.selection).isEqualTo(TextRange(7))
            assertThat(viewModel.state.value.draft.composition).isEqualTo(TextRange(4, 7))

            repository.days = listOf(
                day("2026-08-31", "- [ ] **10:00** Call @Work and @בית\n"),
            )
            viewModel.refreshProjectSuggestions(unlocked = true)
            advanceUntilIdle()

            assertThat(repository.dayReads).isEqualTo(2)
            assertThat(viewModel.state.value.projectSuggestions.map { it.display })
                .containsExactly("Work", "בית").inOrder()

            viewModel.refreshProjectSuggestions(unlocked = false)
            advanceUntilIdle()
            assertThat(repository.dayReads).isEqualTo(2)
            assertThat(viewModel.state.value.projectSuggestions).isEmpty()
        }

    @Test
    fun lockingBeforeUnlockedScanCoroutineDispatchPreventsHistoryRead() =
        runTest(mainDispatcher) {
            val io = QueuedIoDispatcher()
            val repository = SuggestionRepository(
                listOf(day("2026-08-30", "- [ ] **09:00** Plan @Home\n")),
            )
            val viewModel = viewModel(
                SessionRecordingSpeechEngine(),
                this,
                repository = repository,
                ioDispatcher = io,
            )
            viewModel.start(
                Intent(),
                AppSettings(treeUri = null, voiceOnAtLaunch = false, onboardingComplete = true),
            )
            runCurrent()

            viewModel.refreshProjectSuggestions(unlocked = true)
            viewModel.refreshProjectSuggestions(unlocked = false)
            runCurrent()
            io.runPending()
            runCurrent()

            assertThat(repository.dayReads).isEqualTo(0)
            assertThat(viewModel.state.value.projectSuggestions).isEmpty()
        }

    @Test
    fun lockingWhileUnlockedScanIsQueuedForIoPreventsHistoryRead() =
        runTest(mainDispatcher) {
            val io = QueuedIoDispatcher()
            val repository = SuggestionRepository(
                listOf(day("2026-08-30", "- [ ] **09:00** Plan @Home\n")),
            )
            val viewModel = viewModel(
                SessionRecordingSpeechEngine(),
                this,
                repository = repository,
                ioDispatcher = io,
            )
            viewModel.start(
                Intent(),
                AppSettings(treeUri = null, voiceOnAtLaunch = false, onboardingComplete = true),
            )
            runCurrent()

            viewModel.refreshProjectSuggestions(unlocked = true)
            runCurrent()
            viewModel.refreshProjectSuggestions(unlocked = false)
            io.runPending()
            runCurrent()

            assertThat(repository.dayReads).isEqualTo(0)
            assertThat(viewModel.state.value.projectSuggestions).isEmpty()
        }

    private fun viewModel(
        speech: SpeechEngine,
        recoveryScope: CoroutineScope,
        recovery: RecoveryDraftStore = EmptyRecoveryStore(),
        repository: DocumentRepository = NoOpDocumentRepository,
        ioDispatcher: CoroutineDispatcher = mainDispatcher,
    ): CaptureViewModel {
        return CaptureViewModel(
            CaptureDependencies(
                settings = FixedSettingsRepository(),
                repository = repository,
                speechFactory = { speech },
                completionSignals = MutableSharedFlow(),
                clock = Clock.fixed(
                    Instant.parse("2026-08-31T12:00:00Z"),
                    ZoneId.of("America/New_York"),
                ),
                zone = ZoneId.of("America/New_York"),
                ioDispatcher = ioDispatcher,
                recoveryHandoff = CaptureRecoveryHandoff(recovery, recoveryScope),
            ),
        )
    }

    private fun day(date: String, body: String): ParsedDailyFile =
        MarkdownCodec().parse(LocalDate.parse(date), "# $date\n\n$body")
}

private class SessionRecordingSpeechEngine : SpeechEngine {
    val listeners = mutableListOf<SpeechEngine.Listener>()
    var onFinish: () -> Unit = {}
    var availability = SpeechAvailability.Available
    var supportGate: CompletableDeferred<SpeechAvailability>? = null

    override suspend fun finish() = onFinish()

    override suspend fun support(): SpeechAvailability = supportGate?.await() ?: availability

    override suspend fun requestModelDownloads() = Unit

    override fun start(listener: SpeechEngine.Listener) {
        listeners += listener
    }

    override fun stop() = Unit

    override fun destroy() = Unit
}

private class FixedSettingsRepository : SettingsRepository {
    override val settings: Flow<AppSettings> = MutableStateFlow(
        AppSettings(
            treeUri = null,
            voiceOnAtLaunch = true,
            onboardingComplete = true,
        ),
    )

    override suspend fun setTreeUri(treeUri: android.net.Uri?) = Unit

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) = Unit

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) = Unit

    override suspend fun setOnboardingComplete(complete: Boolean) = Unit
}

private class EmptyRecoveryStore : RecoveryDraftStore {
    override suspend fun load(): RecoveryLoadResult = RecoveryLoadResult.Empty

    override suspend fun save(draft: RecoveryDraft) = Unit

    override suspend fun clear() = Unit
}

private class RecordingRecoveryStore : RecoveryDraftStore {
    val saved = mutableListOf<RecoveryDraft>()
    var loadResult: RecoveryLoadResult = RecoveryLoadResult.Empty
    var clearCount = 0
    var throwOnSave = false
    var throwOnClear = false

    override suspend fun load(): RecoveryLoadResult = loadResult

    override suspend fun save(draft: RecoveryDraft) {
        if (throwOnSave) throw java.io.IOException("temporary mirror unavailable")
        saved += draft
    }

    override suspend fun clear() {
        if (throwOnClear) throw java.io.IOException("temporary mirror unavailable")
        clearCount++
    }
}

private object NoOpDocumentRepository : DocumentRepository {
    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = AppendResult.Success

    override suspend fun days(): List<ParsedDailyFile> = emptyList()

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Not used by CaptureViewModelTest")

    override suspend fun uncheckedCount(): Int = 0
}

private class QueuedIoDispatcher : CoroutineDispatcher() {
    private val queue = java.util.ArrayDeque<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
    fun runPending() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}

private class SuggestionRepository(var days: List<ParsedDailyFile>) : DocumentRepository {
    var dayReads = 0

    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = AppendResult.Success

    override suspend fun days(): List<ParsedDailyFile> {
        dayReads += 1
        return days
    }

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Not used by project suggestion test")

    override suspend fun uncheckedCount(): Int = 0
}

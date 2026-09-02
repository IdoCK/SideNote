package com.sidenote.app.capture

import android.content.Intent
import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftStore
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
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

    private fun viewModel(
        speech: SpeechEngine,
        recoveryScope: CoroutineScope,
        recovery: RecoveryDraftStore = EmptyRecoveryStore(),
    ): CaptureViewModel {
        return CaptureViewModel(
            CaptureDependencies(
                settings = FixedSettingsRepository(),
                repository = NoOpDocumentRepository,
                speechFactory = { speech },
                completionSignals = MutableSharedFlow(),
                clock = Clock.fixed(
                    Instant.parse("2026-08-31T12:00:00Z"),
                    ZoneId.of("America/New_York"),
                ),
                zone = ZoneId.of("America/New_York"),
                ioDispatcher = mainDispatcher,
                recoveryHandoff = CaptureRecoveryHandoff(recovery, recoveryScope),
            ),
        )
    }
}

private class SessionRecordingSpeechEngine : SpeechEngine {
    val listeners = mutableListOf<SpeechEngine.Listener>()

    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available

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

    override suspend fun load(): RecoveryLoadResult = RecoveryLoadResult.Empty

    override suspend fun save(draft: RecoveryDraft) {
        saved += draft
    }

    override suspend fun clear() = Unit
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

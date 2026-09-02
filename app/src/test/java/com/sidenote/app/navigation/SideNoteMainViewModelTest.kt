package com.sidenote.app.navigation

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.MainDependencies
import com.sidenote.app.capture.SpeechAvailability
import com.sidenote.app.capture.SpeechEngine
import com.sidenote.app.capture.SpeechFailure
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import com.sidenote.app.onboarding.OnboardingStep
import com.sidenote.app.onboarding.SpeechPreparationState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SideNoteMainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun folderDisclosureAndThreeStepsPersistOnlyConfiguration() = runTest(dispatcher) {
        val settings = RecordingSettingsRepository()
        val speech = RecordingSpeechEngine()
        val viewModel = viewModel(settings, speech)
        advanceUntilIdle()

        assertThat(viewModel.state.value.step).isEqualTo(OnboardingStep.Folder)
        val uri = Uri.parse("content://notes/tree/SideNote")
        viewModel.onFolderSelected(uri)
        advanceUntilIdle()
        assertThat(settings.value.treeUri).isEqualTo(uri)
        assertThat(viewModel.state.value.step).isEqualTo(OnboardingStep.Voice)

        viewModel.acceptVoiceDisclosure(allowed = true)
        advanceUntilIdle()
        assertThat(settings.value.voiceDisclosureAccepted).isTrue()
        assertThat(settings.value.onlineFallbackAllowed).isTrue()
        viewModel.continueOnboarding()
        assertThat(viewModel.state.value.step).isEqualTo(OnboardingStep.QuickTap)

        viewModel.continueOnboarding()
        advanceUntilIdle()
        assertThat(settings.value.onboardingComplete).isTrue()
        assertThat(settings.configurationWrites).containsExactly(
            "folder:$uri",
            "fallback:true",
            "onboarding:true",
        ).inOrder()
    }

    @Test
    fun speechPreparationChecksSupportRequestsDownloadsAndDestroysEachEngine() =
        runTest(dispatcher) {
            val settings = RecordingSettingsRepository(
                AppSettings(
                    treeUri = Uri.parse("content://notes/tree/SideNote"),
                    voiceDisclosureAccepted = true,
                ),
            )
            val engines = mutableListOf<RecordingSpeechEngine>()
            val viewModel = SideNoteMainViewModel(
                MainDependencies(
                    settings = settings,
                    repository = EmptyDocumentRepository,
                    speechFactory = { RecordingSpeechEngine().also(engines::add) },
                    ioDispatcher = dispatcher,
                ),
            )
            advanceUntilIdle()

            viewModel.checkSpeechSupport()
            advanceUntilIdle()
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Available)
            assertThat(engines[0].supportCalls).isEqualTo(1)
            assertThat(engines[0].destroyCalls).isEqualTo(1)

            viewModel.requestModelDownloads()
            advanceUntilIdle()
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.DownloadRequested)
            assertThat(engines[1].downloadCalls).isEqualTo(1)
            assertThat(engines[1].destroyCalls).isEqualTo(1)
        }

    @Test
    fun supportCheckBlocksAConcurrentModelDownloadUntilTheSharedOperationFinishes() =
        runTest(dispatcher) {
            val settings = RecordingSettingsRepository(
                AppSettings(
                    treeUri = Uri.parse("content://notes/tree/SideNote"),
                    voiceDisclosureAccepted = true,
                ),
            )
            val checkingEngine = BlockingSupportSpeechEngine()
            val unexpectedDownloadEngine = RecordingSpeechEngine()
            val engines = ArrayDeque<SpeechEngine>().apply {
                add(checkingEngine)
                add(unexpectedDownloadEngine)
            }
            val policies = mutableListOf<Boolean>()
            val viewModel = SideNoteMainViewModel(
                MainDependencies(
                    settings = settings,
                    repository = EmptyDocumentRepository,
                    speechFactory = { allowed ->
                        policies += allowed
                        engines.removeFirst()
                    },
                    ioDispatcher = dispatcher,
                ),
            )
            advanceUntilIdle()

            viewModel.checkSpeechSupport()
            runCurrent()
            assertThat(checkingEngine.supportStarted.isCompleted).isTrue()

            viewModel.requestModelDownloads()
            runCurrent()

            assertThat(policies).containsExactly(false)
            assertThat(unexpectedDownloadEngine.downloadCalls).isEqualTo(0)
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Checking)

            checkingEngine.supportResult.complete(SpeechAvailability.Available)
            advanceUntilIdle()
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Available)
            assertThat(checkingEngine.destroyCalls).isEqualTo(1)
        }

    @Test
    fun fallbackConsentChangeDestroysAndInvalidatesAnInFlightPolicySnapshot() =
        runTest(dispatcher) {
            val settings = RecordingSettingsRepository(
                AppSettings(
                    treeUri = Uri.parse("content://notes/tree/SideNote"),
                    voiceDisclosureAccepted = true,
                    onlineFallbackAllowed = false,
                ),
            )
            val staleEngine = BlockingSupportSpeechEngine(ignoreCancellation = true)
            val currentEngine = RecordingSpeechEngine()
            val engines = ArrayDeque<SpeechEngine>().apply {
                add(staleEngine)
                add(currentEngine)
            }
            val policies = mutableListOf<Boolean>()
            val viewModel = SideNoteMainViewModel(
                MainDependencies(
                    settings = settings,
                    repository = EmptyDocumentRepository,
                    speechFactory = { allowed ->
                        policies += allowed
                        engines.removeFirst()
                    },
                    ioDispatcher = dispatcher,
                ),
            )
            advanceUntilIdle()
            viewModel.checkSpeechSupport()
            runCurrent()
            assertThat(staleEngine.supportStarted.isCompleted).isTrue()

            viewModel.setOnlineFallbackAllowed(true)
            runCurrent()

            assertThat(settings.value.onlineFallbackAllowed).isTrue()
            assertThat(staleEngine.destroyCalls).isEqualTo(1)
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Checking)

            staleEngine.supportResult.complete(SpeechAvailability.Available)
            advanceUntilIdle()
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Idle)

            viewModel.checkSpeechSupport()
            advanceUntilIdle()
            assertThat(policies).containsExactly(false, true).inOrder()
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Available)
            assertThat(currentEngine.destroyCalls).isEqualTo(1)
        }

    @Test
    fun disclosureAcceptanceInvalidatesAnInFlightGenerationWhenFallbackStaysOff() =
        runTest(dispatcher) {
            val settings = RecordingSettingsRepository(
                AppSettings(
                    treeUri = Uri.parse("content://notes/tree/SideNote"),
                    voiceDisclosureAccepted = false,
                    onlineFallbackAllowed = false,
                ),
            )
            val staleEngine = BlockingSupportSpeechEngine(ignoreCancellation = true)
            val viewModel = SideNoteMainViewModel(
                MainDependencies(
                    settings = settings,
                    repository = EmptyDocumentRepository,
                    speechFactory = { staleEngine },
                    ioDispatcher = dispatcher,
                ),
            )
            advanceUntilIdle()
            viewModel.checkSpeechSupport()
            runCurrent()
            assertThat(staleEngine.supportStarted.isCompleted).isTrue()

            viewModel.acceptVoiceDisclosure(allowed = false)
            runCurrent()

            assertThat(settings.value.voiceDisclosureAccepted).isTrue()
            assertThat(settings.value.onlineFallbackAllowed).isFalse()
            assertThat(staleEngine.destroyCalls).isEqualTo(1)

            staleEngine.supportResult.complete(SpeechAvailability.Available)
            advanceUntilIdle()
            assertThat(viewModel.state.value.speechPreparation)
                .isEqualTo(SpeechPreparationState.Idle)
            assertThat(staleEngine.destroyCalls).isEqualTo(1)
        }

    private fun viewModel(
        settings: RecordingSettingsRepository,
        speech: RecordingSpeechEngine,
    ): SideNoteMainViewModel = SideNoteMainViewModel(
        MainDependencies(
            settings = settings,
            repository = EmptyDocumentRepository,
            speechFactory = { speech },
            ioDispatcher = dispatcher,
        ),
    )
}

private class RecordingSettingsRepository(
    initial: AppSettings = AppSettings(treeUri = null),
) : SettingsRepository {
    private val mutableSettings = MutableStateFlow(initial)
    val value: AppSettings get() = mutableSettings.value
    val configurationWrites = mutableListOf<String>()
    override val settings: Flow<AppSettings> = mutableSettings

    override suspend fun setTreeUri(treeUri: Uri?) {
        configurationWrites += "folder:$treeUri"
        mutableSettings.value = value.copy(treeUri = treeUri)
    }

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) {
        configurationWrites += "voice:$enabled"
        mutableSettings.value = value.copy(voiceOnAtLaunch = enabled)
    }

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) {
        configurationWrites += "fallback:$allowed"
        mutableSettings.value = value.copy(
            voiceDisclosureAccepted = true,
            onlineFallbackAllowed = allowed,
        )
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        configurationWrites += "onboarding:$complete"
        mutableSettings.value = value.copy(onboardingComplete = complete)
    }
}

private class RecordingSpeechEngine : SpeechEngine {
    var supportCalls = 0
    var downloadCalls = 0
    var destroyCalls = 0

    override suspend fun support(): SpeechAvailability {
        supportCalls += 1
        return SpeechAvailability.Available
    }

    override suspend fun requestModelDownloads() {
        downloadCalls += 1
    }

    override fun start(listener: SpeechEngine.Listener) = Unit

    override fun stop() = Unit

    override fun destroy() {
        destroyCalls += 1
    }
}

private class BlockingSupportSpeechEngine(
    private val ignoreCancellation: Boolean = false,
) : SpeechEngine {
    val supportStarted = CompletableDeferred<Unit>()
    val supportResult = CompletableDeferred<SpeechAvailability>()
    var destroyCalls = 0

    override suspend fun support(): SpeechAvailability {
        supportStarted.complete(Unit)
        return if (ignoreCancellation) {
            withContext(NonCancellable) { supportResult.await() }
        } else {
            supportResult.await()
        }
    }

    override suspend fun requestModelDownloads() = Unit

    override fun start(listener: SpeechEngine.Listener) = Unit

    override fun stop() = Unit

    override fun destroy() {
        destroyCalls += 1
    }
}

private object EmptyDocumentRepository : DocumentRepository {
    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult =
        error("Main configuration must not append notes")

    override suspend fun days(): List<ParsedDailyFile> = emptyList()

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = error("Main configuration must not update notes")

    override suspend fun uncheckedCount(): Int = 0
}

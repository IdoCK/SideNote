package com.sidenote.app.capture

import android.content.Intent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftWriter
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.settings.SettingsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CaptureDependencies(
    val settings: SettingsRepository,
    val repository: DocumentRepository,
    val speechFactory: (onlineFallbackAllowed: Boolean) -> SpeechEngine,
    val completionSignals: Flow<CompletionSignal>,
    val clock: Clock,
    val zone: ZoneId,
    val ioDispatcher: CoroutineDispatcher,
    val recoveryHandoff: CaptureRecoveryHandoff,
)

class CaptureViewModel(
    private val dependencies: CaptureDependencies,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    val completionSignals: Flow<CompletionSignal> = dependencies.completionSignals

    private val started = AtomicBoolean(false)
    private val coordinatorReady = CompletableDeferred<CaptureCoordinator>()
    private val eventMutex = Mutex()
    private val host = MutableCaptureHost()
    private val recoverySession = dependencies.recoveryHandoff.openSession()
    private val dispatchedRepository = DispatcherDocumentRepository(
        delegate = dependencies.repository,
        dispatcher = dependencies.ioDispatcher,
    )
    private val externalSetupGate = ExternalSetupLaunchGate(
        disarm = ::disarmBackgroundCompletionForSetup,
        rearm = ::armBackgroundCompletionAfterSetup,
    )

    @Volatile private var captureActive = false
    @Volatile private var setupInFlight = false
    @Volatile private var backgroundCompletionArmed = false
    private var captureLifecycleGeneration = 0L
    private var speechStartupJob: Job? = null
    private var terminalCompletion = false
    private var coordinator: CaptureCoordinator? = null
    private var recoveryWriter: RecoveryDraftWriter? = null
    private var speech: SpeechEngine? = null
    private var detectedLanguageTag: String? = null

    fun attachHost(
        token: Any,
        haptic: HapticConfirmation,
        closer: CaptureCloser,
    ) {
        host.attach(token, haptic, closer)
    }

    fun detachHost(token: Any) {
        host.detach(token)
    }

    @Suppress("UNUSED_PARAMETER")
    fun start(intent: Intent) {
        if (!started.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                val settings = withContext(dependencies.ioDispatcher) {
                    dependencies.settings.settings.first()
                }
                val recovered = when (val result = recoverySession.load()) {
                    is RecoveryLoadResult.Draft -> result.draft
                    RecoveryLoadResult.Empty,
                    is RecoveryLoadResult.CorruptDraft,
                    is RecoveryLoadResult.ReadFailure,
                    -> null
                }
                val currentSpeech = dependencies.speechFactory(settings.onlineFallbackAllowed)
                val writer = RecoveryDraftWriter(
                    store = recoverySession,
                    scope = viewModelScope,
                    debounce = RECOVERY_DEBOUNCE,
                )
                val currentCoordinator = CaptureCoordinator(
                    repository = dispatchedRepository,
                    recovery = recoverySession,
                    saveMutex = Mutex(),
                    clock = dependencies.clock,
                    zone = dependencies.zone,
                    speech = currentSpeech,
                    haptic = host,
                    closer = host,
                )

                currentCoordinator.start(
                    voiceDefaultOn = settings.voiceOnAtLaunch,
                    recovered = recovered,
                )
                speech = currentSpeech
                recoveryWriter = writer
                coordinator = currentCoordinator
                backgroundCompletionArmed =
                    !setupInFlight && settings.onboardingComplete && settings.treeUri != null
                mutableState.value = currentCoordinator.state.value
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoordinator.state.collect { latest -> mutableState.value = latest }
                }
                coordinatorReady.complete(currentCoordinator)
            } catch (error: CancellationException) {
                coordinatorReady.cancel(error)
                throw error
            } catch (error: Exception) {
                coordinatorReady.completeExceptionally(error)
                mutableState.value = mutableState.value.copy(
                    voiceEnabled = false,
                    status = CaptureStatus.SaveFailed,
                )
            }
        }
    }

    fun onCaptureStarted() {
        captureActive = true
        val lifecycleGeneration = ++captureLifecycleGeneration
        speechStartupJob?.cancel()
        speechStartupJob = viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (
                    !captureActive ||
                    lifecycleGeneration != captureLifecycleGeneration
                ) {
                    return@withLock
                }
                startSpeechIfEligible(currentCoordinator)
            }
        }
    }

    fun onCaptureStopped(completeIfBackgrounded: Boolean) {
        captureActive = false
        captureLifecycleGeneration += 1
        speechStartupJob?.cancel()
        speechStartupJob = null
        speech?.stop()
        val shouldCompleteFromThisStop = completeIfBackgrounded && backgroundCompletionArmed
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                flushRecovery(currentCoordinator)
                if (shouldCompleteFromThisStop) {
                    completeLocked(currentCoordinator, CompletionSignal.Backgrounded)
                }
            }
        }
    }

    fun beginExternalSetup(): Long? = externalSetupGate.begin()

    fun activeExternalSetupToken(): Long? = externalSetupGate.activeToken()

    fun onExternalSetupResult(token: Long): Boolean = externalSetupGate.onResult(token)

    fun onExternalSetupLaunchFailed(token: Long): Boolean =
        externalSetupGate.onLaunchFailed(token)

    private fun disarmBackgroundCompletionForSetup() {
        setupInFlight = true
        backgroundCompletionArmed = false
    }

    private fun armBackgroundCompletionAfterSetup() {
        setupInFlight = false
        backgroundCompletionArmed = false
        viewModelScope.launch {
            val settings = withContext(dependencies.ioDispatcher) {
                dependencies.settings.settings.first()
            }
            if (!setupInFlight) {
                backgroundCompletionArmed =
                    settings.onboardingComplete && settings.treeUri != null
            }
        }
    }

    fun complete(signal: CompletionSignal) {
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                flushRecovery(currentCoordinator)
                completeLocked(currentCoordinator, signal)
            }
        }
    }

    fun onUserEdit(value: TextFieldValue) {
        mutateDraft { currentCoordinator -> currentCoordinator.onUserEdit(value) }
    }

    fun onVoiceToggle() {
        mutateDraft { currentCoordinator -> currentCoordinator.onVoiceToggle() }
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                startSpeechIfEligible(currentCoordinator)
            }
        }
    }

    fun discard() {
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                flushRecovery(currentCoordinator)
                currentCoordinator.discard()
                terminalCompletion = true
            }
        }
    }

    override fun onCleared() {
        captureActive = false
        captureLifecycleGeneration += 1
        speechStartupJob?.cancel()
        speechStartupJob = null
        speech?.stop()
        speech?.destroy()
        coordinatorReady.cancel()
        super.onCleared()
    }

    private suspend fun awaitCoordinator(): CaptureCoordinator? =
        runCatching { coordinatorReady.await() }.getOrNull()

    private suspend fun completeLocked(
        currentCoordinator: CaptureCoordinator,
        signal: CompletionSignal,
    ) {
        val blank = currentCoordinator.state.value.draft.text.isBlank()
        currentCoordinator.complete(signal)
        val status = currentCoordinator.state.value.status
        if (blank || status == CaptureStatus.Saved) {
            terminalCompletion = true
        }
    }

    private suspend fun flushRecovery(currentCoordinator: CaptureCoordinator) {
        if (terminalCompletion) return
        val current = currentCoordinator.state.value
        if (current.status == CaptureStatus.Saved || current.status == CaptureStatus.Discarded) return
        recoveryWriter?.flushOnStop(current.toRecoveryDraft())
    }

    private fun mutateDraft(mutation: (CaptureCoordinator) -> Unit) {
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                val before = currentCoordinator.state.value
                mutation(currentCoordinator)
                val after = currentCoordinator.state.value
                if (after != before && after.status != CaptureStatus.Saving) {
                    recoveryWriter?.onDraftChanged(after.toRecoveryDraft())
                }
            }
        }
    }

    private fun startSpeechIfEligible(currentCoordinator: CaptureCoordinator) {
        val current = currentCoordinator.state.value
        if (
            !captureActive ||
            terminalCompletion ||
            !current.voiceEnabled ||
            current.status != CaptureStatus.Ready
        ) {
            return
        }
        speech?.start(speechListener)
    }

    private val speechListener = object : SpeechEngine.Listener {
        override fun onPartial(text: String) {
            mutateDraft { currentCoordinator -> currentCoordinator.onSpeechPartial(text) }
        }

        override fun onFinal(text: String) {
            val locale = detectedLanguageTag
                ?.let(Locale::forLanguageTag)
                ?: Locale.getDefault()
            mutateDraft { currentCoordinator -> currentCoordinator.onSpeechFinal(text, locale) }
        }

        override fun onRms(normalizedRms: Float) = Unit

        override fun onDetectedLanguage(languageTag: String) {
            detectedLanguageTag = languageTag
        }

        override fun onUnavailable(failure: SpeechFailure) {
            mutateDraft(CaptureCoordinator::onSpeechFailure)
        }
    }

    class Factory(
        private val dependencies: CaptureDependencies,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CaptureViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return CaptureViewModel(dependencies) as T
        }
    }

    private companion object {
        val RECOVERY_DEBOUNCE = 250.milliseconds
    }
}

private fun CaptureState.toRecoveryDraft(): RecoveryDraft = RecoveryDraft(
    text = draft.text,
    selection = draft.selection,
    voiceEnabled = voiceEnabled,
)

private class MutableCaptureHost : HapticConfirmation, CaptureCloser {
    @Volatile private var binding: HostBinding? = null

    fun attach(token: Any, haptic: HapticConfirmation, closer: CaptureCloser) {
        binding = HostBinding(token, haptic, closer)
    }

    fun detach(token: Any) {
        if (binding?.token === token) binding = null
    }

    override fun confirm() {
        binding?.haptic?.confirm()
    }

    override fun close() {
        binding?.closer?.close()
    }

    private data class HostBinding(
        val token: Any,
        val haptic: HapticConfirmation,
        val closer: CaptureCloser,
    )
}

private class DispatcherDocumentRepository(
    private val delegate: DocumentRepository,
    private val dispatcher: CoroutineDispatcher,
) : DocumentRepository {
    override suspend fun append(
        text: String,
        committedAt: Instant,
        zone: ZoneId,
    ): AppendResult = withContext(dispatcher) {
        delegate.append(text, committedAt, zone)
    }

    override suspend fun days(): List<ParsedDailyFile> = withContext(dispatcher) {
        delegate.days()
    }

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = withContext(dispatcher) {
        delegate.setProcessed(source, fileName, expectedRaw, processed)
    }

    override suspend fun uncheckedCount(): Int = withContext(dispatcher) {
        delegate.uncheckedCount()
    }
}

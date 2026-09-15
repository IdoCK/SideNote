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
import com.sidenote.app.data.markdown.ProjectToken
import com.sidenote.app.data.recovery.RecoveryDraft
import com.sidenote.app.data.recovery.RecoveryDraftWriter
import com.sidenote.app.data.recovery.RecoveryLoadResult
import com.sidenote.app.data.settings.SettingsRepository
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.notification.NotificationRefreshResult
import com.sidenote.app.notification.NotificationRefresher
import com.sidenote.app.notification.UnavailableNotificationRefresher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeoutOrNull

data class CaptureDependencies(
    val settings: SettingsRepository,
    val repository: DocumentRepository,
    val speechFactory: (onlineFallbackAllowed: Boolean) -> SpeechEngine,
    val completionSignals: Flow<CompletionSignal>,
    val clock: Clock,
    val zone: ZoneId,
    val ioDispatcher: CoroutineDispatcher,
    val recoveryHandoff: CaptureRecoveryHandoff,
    val notificationRefresher: NotificationRefresher = UnavailableNotificationRefresher,
)

class CaptureViewModel(
    private val dependencies: CaptureDependencies,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    val completionSignals: Flow<CompletionSignal> = dependencies.completionSignals

    private val started = AtomicBoolean(false)
    private val speechSessionGeneration = AtomicLong(0L)
    private val suggestionAccessGeneration = AtomicLong(0L)
    private val coordinatorReady = CompletableDeferred<CaptureCoordinator>()
    private val eventMutex = Mutex()
    private val host = MutableCaptureHost()
    private val recoverySession = dependencies.recoveryHandoff.openSession()
    private val dispatchedRepository = DispatcherDocumentRepository(
        delegate = dependencies.repository,
        dispatcher = dependencies.ioDispatcher,
    )
    private val dispatchedNotificationRefresher = DispatcherNotificationRefresher(
        delegate = dependencies.notificationRefresher,
        dispatcher = dependencies.ioDispatcher,
    )
    private val externalSetupGate = ExternalSetupLaunchGate(
        disarm = ::disarmBackgroundCompletionForSetup,
        rearm = ::armBackgroundCompletionAfterSetup,
    )

    @Volatile private var captureActive = false
    private var recognitionRunning = false
    private var transientSpeechRetries = 0
    private var speechSupportReady = false
    @Volatile private var setupInFlight = false
    @Volatile private var backgroundCompletionArmed = false
    @Volatile private var projectSuggestionAccess = false
    private var captureLifecycleGeneration = 0L
    private var speechStartupJob: Job? = null
    private var speechRestartJob: Job? = null
    private var projectSuggestionJob: Job? = null
    private var terminalCompletion = false
    private var coordinator: CaptureCoordinator? = null
    private var recoveryWriter: RecoveryDraftWriter? = null
    private var speech: SpeechEngine? = null
    private var detectedLanguageTag: String? = null
    private var recoveryUnreadable = false
    private var voiceDefaultOn = true

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

    // Read the coordinator directly at close: its StateFlow forwarding collector may still be queued.
    fun closingState(): CaptureState = coordinator?.state?.value ?: state.value

    @Suppress("UNUSED_PARAMETER")
    fun start(intent: Intent, preloadedSettings: AppSettings? = null) {
        if (!started.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                val settings = preloadedSettings ?: withContext(dependencies.ioDispatcher) {
                    dependencies.settings.settings.first()
                }
                voiceDefaultOn = settings.voiceOnAtLaunch
                val loaded = recoverySession.load()
                recoveryUnreadable = loaded is RecoveryLoadResult.ReadFailure
                val recovered = when (val result = loaded) {
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
                    onPersistenceResult = { success -> coordinator?.onRecoveryPersistenceResult(success) },
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
                    notificationRefresher = dispatchedNotificationRefresher,
                    commitLifetime = CaptureCommitLifetime(recoverySession::reconcile),
                )

                currentCoordinator.start(
                    voiceDefaultOn = settings.voiceOnAtLaunch,
                    recovered = recovered,
                )
                if (recoveryUnreadable) currentCoordinator.onRecoveryUnreadable()
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
        val lifecycleGeneration = if (captureActive) captureLifecycleGeneration else ++captureLifecycleGeneration
        captureActive = true
        viewModelScope.launch {
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
        recognitionRunning = false
        captureLifecycleGeneration += 1
        speechStartupJob?.cancel()
        speechStartupJob = null
        speechRestartJob?.cancel()
        val shouldCompleteFromThisStop = completeIfBackgrounded && backgroundCompletionArmed
        val completing = coordinator?.state?.value?.status in
            setOf(CaptureStatus.Finalizing, CaptureStatus.Saving)
        if (!shouldCompleteFromThisStop && !completing) {
            speechSessionGeneration.incrementAndGet()
            coordinator?.onSpeechStopped()
            speech?.stop()
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                if (shouldCompleteFromThisStop) {
                    completeLocked(currentCoordinator, CompletionSignal.Backgrounded)
                } else {
                    flushRecovery(currentCoordinator)
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
                completeLocked(currentCoordinator, signal)
            }
        }
    }

    fun onUserEdit(value: TextFieldValue) {
        mutateDraft { currentCoordinator -> currentCoordinator.onUserEdit(value) }
    }

    /** Protected history is queried only after the host has observed an unlocked state. */
    fun refreshProjectSuggestions(unlocked: Boolean) {
        projectSuggestionJob?.cancel()
        projectSuggestionJob = null
        projectSuggestionAccess = unlocked
        val generation = suggestionAccessGeneration.incrementAndGet()
        if (!unlocked) {
            coordinator?.onProjectSuggestions(emptyList())
            return
        }
        projectSuggestionJob = viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            val projects = try {
                withContext(dependencies.ioDispatcher) {
                    if (
                        !projectSuggestionAccess ||
                        generation != suggestionAccessGeneration.get()
                    ) return@withContext null
                    dependencies.repository.days()
                        .asSequence()
                        .flatMap { day -> day.entries.asSequence() }
                        .flatMap { entry -> entry.projects.asSequence() }
                        .associateBy(ProjectToken::key)
                        .values
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ProjectToken::display))
                } ?: return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            eventMutex.withLock {
                if (
                    projectSuggestionAccess &&
                    generation == suggestionAccessGeneration.get() &&
                    !terminalCompletion
                ) {
                    currentCoordinator.onProjectSuggestions(projects)
                }
            }
        }
    }

    fun retryRecovery() {
        viewModelScope.launch {
            val current = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (!recoveryUnreadable) return@withLock
                val loaded = recoverySession.load()
                if (loaded is RecoveryLoadResult.ReadFailure) return@withLock
                recoveryUnreadable = false
                current.start(voiceDefaultOn, (loaded as? RecoveryLoadResult.Draft)?.draft)
                startSpeechIfEligible(current)
            }
        }
    }

    fun onVoiceToggle() {
        transientSpeechRetries = 0
        mutateDraft { currentCoordinator -> currentCoordinator.onVoiceToggle() }
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                startSpeechIfEligible(currentCoordinator)
            }
        }
    }

    fun onVoicePause() {
        mutateDraft { current ->
            if (current.state.value.voiceEnabled) current.onVoiceToggle()
        }
    }

    fun discard() {
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                recoveryWriter?.cancelPending()
                currentCoordinator.discard()
                terminalCompletion =
                    currentCoordinator.state.value.status == CaptureStatus.Discarded
            }
        }
    }

    override fun onCleared() {
        captureActive = false
        captureLifecycleGeneration += 1
        speechStartupJob?.cancel()
        speechStartupJob = null
        speechRestartJob?.cancel()
        speechSessionGeneration.incrementAndGet()
        suggestionAccessGeneration.incrementAndGet()
        projectSuggestionAccess = false
        coordinator?.onSpeechStopped()
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
        if (recoveryUnreadable) return
        speechStartupJob?.cancel()
        speechStartupJob = null
        speechRestartJob?.cancel()
        // Completion owns the durable mirror + append + clear transaction. A delayed
        // debounce must not run after that clear and resurrect a completed draft.
        recoveryWriter?.cancelPending()
        currentCoordinator.complete(signal)
        val status = currentCoordinator.state.value.status
        if (currentCoordinator.state.value.draft.text.isBlank() || status == CaptureStatus.Saved) {
            terminalCompletion = true
        }
    }

    private suspend fun flushRecovery(currentCoordinator: CaptureCoordinator) {
        if (terminalCompletion || recoveryUnreadable) return
        val current = currentCoordinator.state.value
        if (current.status == CaptureStatus.Saved || current.status == CaptureStatus.Discarded) return
        // Enqueue before the first suspension. The handoff's process scope then owns the
        // finite write even if Activity teardown immediately clears this ViewModel.
        recoveryWriter?.cancelPending()
        val flush = recoverySession.flushInBackground(current.toRecoveryDraft())
        try {
            currentCoordinator.onRecoveryPersistenceResult(flush.await())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoordinator.onRecoveryPersistenceResult(false)
        }
    }

    private fun mutateDraft(mutation: (CaptureCoordinator) -> Unit) {
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (terminalCompletion) return@withLock
                val before = currentCoordinator.state.value
                mutation(currentCoordinator)
                val after = currentCoordinator.state.value
                if (before.voiceEnabled && !after.voiceEnabled) {
                    speechSessionGeneration.incrementAndGet()
                    recognitionRunning = false
                    speechStartupJob?.cancel()
                    speechStartupJob = null
                    speechRestartJob?.cancel()
                }
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
            recognitionRunning ||
            speechStartupJob?.isActive == true ||
            terminalCompletion ||
            !current.voiceEnabled ||
            current.status != CaptureStatus.Ready
        ) {
            return
        }
        val generation = speechSessionGeneration.incrementAndGet()
        val lifecycleGeneration = captureLifecycleGeneration
        currentCoordinator.onSpeechPhase(VoicePhase.Starting)
        // Provider IPC must never hold eventMutex: taps, typing and physical stop
        // signals need to cancel startup immediately, even if the provider hangs.
        speechStartupJob = viewModelScope.launch {
            val available = try {
                if (speechSupportReady) SpeechAvailability.Available
                else withTimeoutOrNull(3000L) { speech?.support() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            eventMutex.withLock {
                if (!captureActive || lifecycleGeneration != captureLifecycleGeneration ||
                    generation != speechSessionGeneration.get() || terminalCompletion ||
                    !currentCoordinator.state.value.voiceEnabled ||
                    currentCoordinator.state.value.status != CaptureStatus.Ready
                ) return@withLock
                if (available == null) {
                    currentCoordinator.onSpeechPhase(VoicePhase.Retrying)
                    restartAfterUtterance(generation, 1500L)
                } else if (available != SpeechAvailability.Available) {
                    currentCoordinator.onSpeechFailure()
                } else {
                    speechSupportReady = true
                    detectedLanguageTag = null
                    recognitionRunning = true
                    speech?.start(speechListener(generation))
                }
            }
        }
    }

    private fun speechListener(generation: Long) = object : SpeechEngine.Listener {
        override fun onReady() {
            mutateSpeech(generation) { it.onSpeechPhase(VoicePhase.Listening) }
        }

        override fun onProcessing() {
            mutateSpeech(generation) { it.onSpeechPhase(VoicePhase.Processing) }
        }

        override fun onPartial(text: String) {
            mutateSpeech(generation) { currentCoordinator ->
                transientSpeechRetries = 0
                currentCoordinator.onSpeechPhase(VoicePhase.Listening)
                currentCoordinator.onSpeechPartial(text)
            }
        }

        override fun onFinal(text: String) {
            val locale = detectedLanguageTag
                ?.let(Locale::forLanguageTag)
                ?: Locale.getDefault()
            mutateSpeech(generation) { currentCoordinator ->
                recognitionRunning = false
                transientSpeechRetries = 0
                currentCoordinator.onSpeechFinal(text, locale)
                currentCoordinator.onSpeechStopped()
                if (speechSessionGeneration.compareAndSet(generation, generation + 1)) {
                    restartAfterUtterance(generation + 1)
                }
            }
        }

        override fun onRms(normalizedRms: Float) {
            mutateRms(generation, normalizedRms)
        }

        override fun onAudioFeatures(features: SpeechAudioFeatures) {
            if (generation != speechSessionGeneration.get()) return
            viewModelScope.launch {
                val current = awaitCoordinator() ?: return@launch
                eventMutex.withLock {
                    if (!terminalCompletion && generation == speechSessionGeneration.get()) {
                        current.onAudioFeatures(features)
                    }
                }
            }
        }

        override fun onDetectedLanguage(languageTag: String) {
            if (generation == speechSessionGeneration.get()) {
                detectedLanguageTag = languageTag
            }
        }

        override fun onUnavailable(failure: SpeechFailure) {
            mutateSpeech(generation) { currentCoordinator ->
                recognitionRunning = false
                val ordinarySilence = failure == SpeechFailure.NoMatch ||
                    failure == SpeechFailure.SpeechTimeout
                val retryTransient = failure in setOf(
                    SpeechFailure.Busy, SpeechFailure.Client, SpeechFailure.Audio,
                    SpeechFailure.Network, SpeechFailure.NetworkTimeout, SpeechFailure.Server,
                    SpeechFailure.TooManyRequests,
                )
                if (retryTransient) transientSpeechRetries = (transientSpeechRetries + 1).coerceAtMost(8)
                if (ordinarySilence || retryTransient) currentCoordinator.onSpeechStopped()
                else currentCoordinator.onSpeechFailure()
                if (retryTransient) currentCoordinator.onSpeechPhase(VoicePhase.Retrying)
                if (speechSessionGeneration.compareAndSet(generation, generation + 1) && (ordinarySilence || retryTransient)) {
                    val retryDelay = when {
                        failure == SpeechFailure.TooManyRequests -> 30_000L
                        retryTransient -> 750L * transientSpeechRetries
                        else -> 300L
                    }
                    restartAfterUtterance(generation + 1, retryDelay)
                }
            }
        }
    }

    private fun restartAfterUtterance(generation: Long, delayMillis: Long = 300L) {
        speechRestartJob?.cancel()
        speechRestartJob = viewModelScope.launch {
            delay(delayMillis)
            val current = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (generation == speechSessionGeneration.get()) startSpeechIfEligible(current)
            }
        }
    }

    private fun mutateSpeech(
        generation: Long,
        mutation: (CaptureCoordinator) -> Unit,
    ) {
        if (generation != speechSessionGeneration.get()) return
        // Completion owns eventMutex while awaiting the recognizer. Terminal callbacks must
        // enter the coordinator's synchronized owned span before that bounded wait returns.
        val current = coordinator
        if (current?.state?.value?.status == CaptureStatus.Finalizing) {
            mutation(current)
            return
        }
        mutateDraft { currentCoordinator ->
            if (generation == speechSessionGeneration.get()) {
                mutation(currentCoordinator)
            }
        }
    }

    private fun mutateRms(generation: Long, normalizedRms: Float) {
        if (generation != speechSessionGeneration.get()) return
        viewModelScope.launch {
            val currentCoordinator = awaitCoordinator() ?: return@launch
            eventMutex.withLock {
                if (
                    terminalCompletion ||
                    generation != speechSessionGeneration.get()
                ) {
                    return@withLock
                }
                currentCoordinator.onSpeechRms(normalizedRms)
            }
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

private class DispatcherNotificationRefresher(
    private val delegate: NotificationRefresher,
    private val dispatcher: CoroutineDispatcher,
) : NotificationRefresher {
    override suspend fun refresh(): NotificationRefreshResult = withContext(dispatcher) {
        delegate.refresh()
    }
}

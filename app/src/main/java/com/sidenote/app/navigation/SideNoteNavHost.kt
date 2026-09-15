package com.sidenote.app.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sidenote.app.MainDependencies
import com.sidenote.app.capture.SpeechAvailability
import com.sidenote.app.capture.SpeechEngine
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.onboarding.OnboardingScreen
import com.sidenote.app.onboarding.OnboardingStep
import com.sidenote.app.onboarding.SpeechPreparationState
import com.sidenote.app.review.ReviewEntry
import com.sidenote.app.review.ReviewState
import com.sidenote.app.review.ui.ReviewScreen
import com.sidenote.app.review.ui.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionState(
    val microphoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
)

data class SideNoteMainState(
    val settings: AppSettings? = null,
    val step: OnboardingStep = OnboardingStep.Folder,
    val speechPreparation: SpeechPreparationState = SpeechPreparationState.Idle,
    val message: String? = null,
)

class SideNoteMainViewModel(
    private val dependencies: MainDependencies,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SideNoteMainState())
    val state: StateFlow<SideNoteMainState> = mutableState.asStateFlow()
    private var onboardingStepInitialized = false
    private var observedSpeechPolicy: SpeechPolicy? = null
    private var speechOperationGeneration = 0L
    private var speechOperationJob: Job? = null
    private var activeSpeechResource: SpeechPreparationResource? = null

    init {
        viewModelScope.launch {
            dependencies.settings.settings.collect { settings ->
                val speechPolicy = settings.speechPolicy()
                val previousSpeechPolicy = observedSpeechPolicy
                observedSpeechPolicy = speechPolicy
                if (previousSpeechPolicy != null && previousSpeechPolicy != speechPolicy) {
                    invalidateSpeechPreparation()
                }
                val current = mutableState.value
                val step = if (!onboardingStepInitialized) {
                    onboardingStepInitialized = true
                    restoredStep(settings)
                } else {
                    current.step
                }
                mutableState.value = current.copy(settings = settings, step = step)
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            runSettingWrite(
                write = { dependencies.settings.setTreeUri(uri) },
                onSuccess = {
                    runCatching { dependencies.notificationRefreshScheduler.enqueue() }
                    mutableState.value = mutableState.value.copy(
                        step = OnboardingStep.Voice,
                        message = null,
                    )
                },
            )
        }
    }

    fun onFolderSelectionFailed() {
        mutableState.value = mutableState.value.copy(
            message = "SideNote could not keep access to that folder. Please choose it again.",
        )
    }

    fun onProtectedActionBlocked() {
        mutableState.value = mutableState.value.copy(
            message = "Unlock your device to change setup or permissions.",
        )
    }

    fun continueOnboarding() {
        val current = mutableState.value
        when (current.step) {
            OnboardingStep.Folder -> {
                if (current.settings?.treeUri != null) {
                    mutableState.value = current.copy(step = OnboardingStep.Voice, message = null)
                }
            }
            OnboardingStep.Voice -> {
                if (current.settings?.voiceDisclosureAccepted == true) {
                    mutableState.value = current.copy(step = OnboardingStep.QuickTap, message = null)
                }
            }
            OnboardingStep.QuickTap -> viewModelScope.launch {
                runSettingWrite(
                    write = { dependencies.settings.setOnboardingComplete(true) },
                )
            }
        }
    }

    fun setVoiceOnAtLaunch(enabled: Boolean) {
        viewModelScope.launch {
            runSettingWrite(
                write = { dependencies.settings.setVoiceOnAtLaunch(enabled) },
            )
        }
    }

    fun acceptVoiceDisclosure(allowed: Boolean) {
        viewModelScope.launch {
            runSettingWrite(
                write = {
                    dependencies.settings.acceptVoiceDisclosureAndSetFallback(allowed)
                },
            )
        }
    }

    fun setOnlineFallbackAllowed(allowed: Boolean) {
        acceptVoiceDisclosure(allowed)
    }

    fun checkSpeechSupport() {
        startSpeechPreparation(
            inProgress = SpeechPreparationState.Checking,
            failureMessage = "Speech support could not be checked. Typing still works.",
        ) { engine ->
            if (engine.support() == SpeechAvailability.Available) {
                SpeechPreparationState.Available
            } else {
                SpeechPreparationState.TypedOnly
            }
        }
    }

    fun requestModelDownloads() {
        startSpeechPreparation(
            inProgress = SpeechPreparationState.Downloading,
            failureMessage = "Speech models could not be requested. Typing still works.",
        ) { engine ->
            engine.requestModelDownloads()
            SpeechPreparationState.DownloadRequested
        }
    }

    private fun startSpeechPreparation(
        inProgress: SpeechPreparationState,
        failureMessage: String,
        operation: suspend (SpeechEngine) -> SpeechPreparationState,
    ) {
        if (speechOperationJob != null || mutableState.value.speechPreparation.isInProgress()) return
        val policySnapshot = mutableState.value.settings?.speechPolicy() ?: SpeechPolicy()
        val generation = ++speechOperationGeneration
        mutableState.value = mutableState.value.copy(
            speechPreparation = inProgress,
            message = null,
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var resource: SpeechPreparationResource? = null
            try {
                resource = SpeechPreparationResource(
                    dependencies.speechFactory(policySnapshot.onlineFallbackAllowed),
                )
                activeSpeechResource = resource
                val result = withContext(dependencies.ioDispatcher) {
                    operation(resource.engine)
                }
                if (isCurrentSpeechOperation(generation, policySnapshot)) {
                    mutableState.value = mutableState.value.copy(speechPreparation = result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentSpeechOperation(generation, policySnapshot)) {
                    mutableState.value = mutableState.value.copy(
                        speechPreparation = SpeechPreparationState.TypedOnly,
                        message = failureMessage,
                    )
                }
            } finally {
                resource?.destroy()
                if (activeSpeechResource === resource) activeSpeechResource = null
                if (
                    generation != speechOperationGeneration &&
                    mutableState.value.speechPreparation.isInProgress()
                ) {
                    mutableState.value = mutableState.value.copy(
                        speechPreparation = SpeechPreparationState.Idle,
                    )
                }
                speechOperationJob = null
            }
        }
        speechOperationJob = job
        job.start()
    }

    private fun invalidateSpeechPreparation() {
        speechOperationGeneration += 1
        activeSpeechResource?.destroy()
        speechOperationJob?.cancel()
        if (speechOperationJob == null) {
            mutableState.value = mutableState.value.copy(
                speechPreparation = SpeechPreparationState.Idle,
            )
        }
    }

    private fun isCurrentSpeechOperation(generation: Long, policySnapshot: SpeechPolicy): Boolean =
        generation == speechOperationGeneration &&
            mutableState.value.settings?.speechPolicy() == policySnapshot

    private suspend fun runSettingWrite(
        write: suspend () -> Unit,
        onSuccess: () -> Unit = {},
    ) {
        try {
            withContext(dependencies.ioDispatcher) { write() }
            onSuccess()
            mutableState.value = mutableState.value.copy(message = null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                message = "That setting could not be saved. Please try again.",
            )
        }
    }

    class Factory(
        private val dependencies: MainDependencies,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SideNoteMainViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return SideNoteMainViewModel(dependencies) as T
        }
    }
}

private data class SpeechPolicy(
    val disclosureAccepted: Boolean = false,
    val onlineFallbackAllowed: Boolean = false,
)

private fun AppSettings.speechPolicy(): SpeechPolicy = SpeechPolicy(
    disclosureAccepted = voiceDisclosureAccepted,
    onlineFallbackAllowed = onlineFallbackAllowed,
)

private class SpeechPreparationResource(
    val engine: SpeechEngine,
) {
    private var destroyed = false

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        engine.destroy()
    }
}

private fun SpeechPreparationState.isInProgress(): Boolean =
    this == SpeechPreparationState.Checking || this == SpeechPreparationState.Downloading

@Composable
fun SideNoteNavHost(
    mainState: SideNoteMainState,
    reviewState: ReviewState,
    permissions: PermissionState,
    onChooseFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
    onContinueOnboarding: () -> Unit,
    onAcceptVoiceDisclosure: (Boolean) -> Unit,
    onVoiceOnAtLaunchChange: (Boolean) -> Unit,
    onOnlineFallbackChange: (Boolean) -> Unit,
    onCheckSpeech: () -> Unit,
    onDownloadModels: () -> Unit,
    onShowDates: () -> Unit,
    onShowProjects: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onSelectDate: (java.time.LocalDate) -> Unit,
    onToggleExpanded: (ReviewEntry) -> Unit,
    onProcessedChange: (ReviewEntry, Boolean) -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSourceDay: (ReviewEntry) -> Unit,
    modifier: Modifier = Modifier,
    reviewLaunchGeneration: Int = 0,
) {
    var destination by rememberSaveable(reviewLaunchGeneration) { mutableStateOf(ProtectedDestination.Review) }
    val settings = mainState.settings
    LaunchedEffect(settings?.onboardingComplete) {
        if (settings?.onboardingComplete != true) destination = ProtectedDestination.Review
    }
    when {
        settings == null -> Surface(
            color = Color(0xFF111111),
            contentColor = Color(0xFFF4F1EA),
            modifier = modifier,
        ) { Text("Loading SideNote…") }
        !settings.onboardingComplete -> OnboardingScreen(
            step = mainState.step,
            settings = settings,
            folderLabel = settings.treeUri?.folderLabel(),
            microphoneGranted = permissions.microphoneGranted,
            notificationsGranted = permissions.notificationsGranted,
            speechPreparation = mainState.speechPreparation,
            message = mainState.message,
            onChooseFolder = onChooseFolder,
            onRequestPermissions = onRequestPermissions,
            onAcceptVoiceDisclosure = onAcceptVoiceDisclosure,
            onOnlineFallbackChange = onOnlineFallbackChange,
            onCheckSpeech = onCheckSpeech,
            onDownloadModels = onDownloadModels,
            onContinue = onContinueOnboarding,
            modifier = modifier,
        )
        else -> {
            BackHandler(enabled = destination == ProtectedDestination.Settings) {
                destination = ProtectedDestination.Review
            }
            // MainActivity removes this entire host when locked. Never animate the unlock gate.
            Surface(color = Color(0xFF111111), modifier = modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = ProtectedPage(destination, mainState, reviewState, permissions),
                    contentKey = { it.destination },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    transitionSpec = {
                        val direction = if (targetState.destination == ProtectedDestination.Settings) 1 else -1
                        (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) {
                            direction * it / 12
                        } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) {
                                -direction * it / 12
                            } + fadeOut(tween(150))) using null
                    },
                    label = "Review and Settings",
                ) { page ->
                    val interactive = page.destination == destination
                    // Consume outgoing input before descendants; guard callbacks as well so a
                    // stale accessibility action cannot mutate state during an interrupted exit.
                    val inputModifier = if (interactive) Modifier else Modifier
                        .clearAndSetSemantics {}
                        .onPreviewKeyEvent { true }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                    Box(Modifier.fillMaxSize().then(inputModifier)) {
                        when (page.destination) {
                            ProtectedDestination.Settings -> {
                                val pageSettings = checkNotNull(page.mainState.settings)
                                SettingsScreen(
                                    settings = pageSettings,
                                    folderLabel = pageSettings.treeUri?.folderLabel().orEmpty(),
                                    microphoneGranted = page.permissions.microphoneGranted,
                                    notificationsGranted = page.permissions.notificationsGranted,
                                    message = page.mainState.message,
                                    onBack = { if (interactive) destination = ProtectedDestination.Review },
                                    onShowDates = {
                                        if (interactive) {
                                            onShowDates()
                                            destination = ProtectedDestination.Review
                                        }
                                    },
                                    onShowProjects = {
                                        if (interactive) {
                                            onShowProjects()
                                            destination = ProtectedDestination.Review
                                        }
                                    },
                                    onChooseFolder = { if (interactive) onChooseFolder() },
                                    onVoiceOnAtLaunchChange = { if (interactive) onVoiceOnAtLaunchChange(it) },
                                    onOnlineFallbackChange = { if (interactive) onOnlineFallbackChange(it) },
                                    onRequestPermissions = { if (interactive) onRequestPermissions() },
                                )
                            }
                            ProtectedDestination.Review -> ReviewScreen(
                                state = page.reviewState,
                                onShowDates = { if (interactive) onShowDates() },
                                onShowProjects = { if (interactive) onShowProjects() },
                                onPreviousDay = { if (interactive) onPreviousDay() },
                                onNextDay = { if (interactive) onNextDay() },
                                onSelectDate = { if (interactive) onSelectDate(it) },
                                onOpenSettings = { if (interactive) destination = ProtectedDestination.Settings },
                                onToggleExpanded = { if (interactive) onToggleExpanded(it) },
                                onProcessedChange = { entry, processed ->
                                    if (interactive) onProcessedChange(entry, processed)
                                },
                                onOpenProject = { if (interactive) onOpenProject(it) },
                                onOpenSourceDay = { if (interactive) onOpenSourceDay(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class ProtectedDestination {
    Review,
    Settings,
}

private data class ProtectedPage(
    val destination: ProtectedDestination,
    val mainState: SideNoteMainState,
    val reviewState: ReviewState,
    val permissions: PermissionState,
)

private fun restoredStep(settings: AppSettings): OnboardingStep = when {
    settings.treeUri == null -> OnboardingStep.Folder
    settings.voiceDisclosureAccepted -> OnboardingStep.QuickTap
    else -> OnboardingStep.Voice
}

private fun Uri.folderLabel(): String = lastPathSegment?.substringAfterLast(':') ?: toString()

package com.sidenote.app.capture

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sidenote.app.AppContainer
import com.sidenote.app.MainActivity
import com.sidenote.app.R
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.ui.CaptureScreen
import com.sidenote.app.capture.ui.LocalReducedMotion
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.privacy.LockState
import com.sidenote.app.privacy.UnlockGate
import com.sidenote.app.ui.theme.SideNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private lateinit var dependencies: CaptureDependencies
    private lateinit var lockState: LockState
    private var viewModel: CaptureViewModel? = null
    private val captureReady = mutableStateOf(false)
    private val onboardingComplete = mutableStateOf<Boolean?>(null)
    private var hostStarted = false
    private var dismissRequested = false
    private var routedToOnboarding = false
    private var completionJob: Job? = null
    private var pendingCompletionSignal: CompletionSignal? = null
    private var initialSettings: AppSettings? = null
    private var reviewLaunch = false
    private var reopenAfterSave = false
    private var completionStartedAt: Long? = null

    private val externalSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val current = viewModel ?: return@registerForActivityResult
        current.activeExternalSetupToken()?.let { token ->
            current.onExternalSetupResult(token)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reopenAfterSave = savedInstanceState?.getBoolean(STATE_REOPEN_AFTER_SAVE, false) == true
        reviewLaunch = savedInstanceState?.getBoolean(STATE_REVIEW_LAUNCH, false) == true ||
            intent?.action == ACTION_REVIEW
        pendingCompletionSignal = savedInstanceState?.getString(STATE_PENDING_COMPLETION)?.let {
            name -> CompletionSignal.entries.firstOrNull { signal -> signal.name == name }
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        container = (application as SideNoteApplication).container
        lockState = container.lockState()
        lockState.refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                lockState.locked.collect { locked ->
                    if (!locked) (application as SideNoteApplication).scheduleNotificationRecovery()
                }
            }
        }

        setContent {
            SideNoteTheme {
                val locked by lockState.locked.collectAsState()
                LaunchedEffect(reviewLaunch, locked) {
                    if (reviewLaunch) {
                        if (locked) requestDismissalOnce() else routeToReview()
                    }
                }
                LaunchedEffect(onboardingComplete.value, locked) {
                    if (!reviewLaunch && onboardingComplete.value == false) {
                        if (locked) requestDismissalOnce() else routeToOnboarding()
                    }
                }
                val current = viewModel
                if (reviewLaunch) {
                    UnlockGate(onRetry = if (locked) ::retryDismissal else null)
                } else if (captureReady.value && current != null) {
                    val state by current.state.collectAsState()
                    LaunchedEffect(locked) {
                        current.refreshProjectSuggestions(unlocked = !locked)
                    }
                    CompositionLocalProvider(
                        LocalReducedMotion provides !ValueAnimator.areAnimatorsEnabled(),
                    ) {
                        CaptureScreen(
                            state = state,
                            onTextChanged = current::onUserEdit,
                            onVoiceToggle = current::onVoiceToggle,
                            onVoicePause = current::onVoicePause,
                            onDiscard = current::discard,
                            onRetryRecovery = current::retryRecovery,
                        )
                    }
                } else {
                    UnlockGate(
                        onRetry = if (onboardingComplete.value == false) {
                            ::retryDismissal
                        } else {
                            null
                        },
                    )
                }
            }
        }

        if (reviewLaunch) return

        dependencies = container.captureDependencies()
        completionJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dependencies.completionSignals.collect(::onCompletionSignal)
            }
        }

        val restoredSettings = savedInstanceState?.restoredSettings()
        if (restoredSettings != null) {
            handleInitialSettings(restoredSettings)
        } else {
            lifecycleScope.launch {
                val settings = withContext(dependencies.ioDispatcher) {
                    dependencies.settings.settings.first()
                }
                handleInitialSettings(settings)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_REVIEW) {
            reopenAfterSave = false
            reviewLaunch = true
            pendingCompletionSignal = null
            lockState.refresh()
            if (lockState.locked.value) requestDismissalOnce() else routeToReview()
        } else if (viewModel?.closingState()?.status in setOf(
                CaptureStatus.Finalizing, CaptureStatus.Saving, CaptureStatus.Saved,
            )) {
            // singleTask delivers rapid assistant invocations to the saving activity.
            // Preserve the request until its durable transaction finishes.
            reopenAfterSave = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        initialSettings?.let { settings -> outState.putSettings(settings) }
        outState.putString(STATE_PENDING_COMPLETION, pendingCompletionSignal?.name)
        outState.putBoolean(STATE_REVIEW_LAUNCH, reviewLaunch)
        outState.putBoolean(STATE_REOPEN_AFTER_SAVE, reopenAfterSave)
        super.onSaveInstanceState(outState)
    }

    fun launchExternalSetup(intent: Intent): Boolean {
        val current = viewModel ?: return false
        val token = current.beginExternalSetup() ?: return false
        return try {
            externalSetupLauncher.launch(intent)
            true
        } catch (_: Exception) {
            current.onExternalSetupLaunchFailed(token)
            false
        }
    }

    override fun onStart() {
        super.onStart()
        hostStarted = true
        viewModel?.onCaptureStarted()
    }

    override fun onResume() {
        super.onResume()
        if (::lockState.isInitialized) {
            lockState.refresh()
            viewModel?.refreshProjectSuggestions(unlocked = !lockState.locked.value)
        }
    }

    override fun onStop() {
        hostStarted = false
        val completeIfBackgrounded = !isChangingConfigurations && !isFinishing
        val current = viewModel
        if (current == null) {
            if (completeIfBackgrounded) onCompletionSignal(CompletionSignal.Backgrounded)
        } else {
            current.onCaptureStopped(completeIfBackgrounded)
        }
        super.onStop()
    }

    override fun onDestroy() {
        completionJob?.cancel()
        viewModel?.detachHost(this)
        super.onDestroy()
    }

    private fun initializeCapture(settings: AppSettings) {
        if (viewModel != null || routedToOnboarding) return
        val current = ViewModelProvider(
            this,
            CaptureViewModel.Factory(dependencies),
        )[CaptureViewModel::class.java]
        current.attachHost(
            token = this,
            haptic = HapticConfirmation {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            },
            closer = CaptureCloser {
                completionStartedAt?.let { started ->
                    android.util.Log.i("SideNoteTiming", "Capture completion: ${android.os.SystemClock.elapsedRealtime() - started} ms")
                }
                val closing = current.closingState()
                val confirmation = when (closing.status) {
                    CaptureStatus.Saved -> closing.savedTime?.let { getString(R.string.capture_saved, it) }
                    CaptureStatus.Discarded -> getString(R.string.capture_discarded)
                    else -> null
                }
                val reopen = reopenAfterSave && !reviewLaunch
                reopenAfterSave = false
                if (!reopen) confirmation?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                finish()
                if (reopen) startActivity(Intent(this, CaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        viewModel = current
        current.start(intent, settings)
        if (hostStarted) current.onCaptureStarted()
        captureReady.value = true
        pendingCompletionSignal?.let(current::complete)
        pendingCompletionSignal = null
    }

    private fun handleInitialSettings(settings: AppSettings) {
        initialSettings = settings
        if (settings.onboardingComplete) {
            initializeCapture(settings)
            onboardingComplete.value = true
        } else {
            pendingCompletionSignal = null
            onboardingComplete.value = false
        }
    }

    private fun onCompletionSignal(signal: CompletionSignal) {
        if (completionStartedAt == null) completionStartedAt = android.os.SystemClock.elapsedRealtime()
        if (signal == CompletionSignal.ScreenOff || signal == CompletionSignal.FaceDown) {
            reopenAfterSave = false
        }
        val current = viewModel
        if (current != null) {
            current.complete(signal)
        } else if (onboardingComplete.value != false && pendingCompletionSignal == null) {
            pendingCompletionSignal = signal
        }
    }

    private fun requestDismissalOnce() {
        if (dismissRequested || !lockState.locked.value) return
        dismissRequested = true
        lockState.requestDismissKeyguard(this)
    }

    private fun retryDismissal() {
        if (!lockState.locked.value) return
        dismissRequested = false
        requestDismissalOnce()
    }

    private fun routeToOnboarding() {
        if (routedToOnboarding || lockState.locked.value) return
        routedToOnboarding = true
        setShowWhenLocked(false)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }

    private fun routeToReview() {
        if (routedToOnboarding || lockState.locked.value) return
        routedToOnboarding = true
        setShowWhenLocked(false)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }

    private fun Bundle.putSettings(settings: AppSettings) {
        putBoolean(STATE_HAS_SETTINGS, true)
        putString(STATE_TREE_URI, settings.treeUri?.toString())
        putBoolean(STATE_VOICE_ON_AT_LAUNCH, settings.voiceOnAtLaunch)
        putBoolean(STATE_ONLINE_FALLBACK, settings.onlineFallbackAllowed)
        putBoolean(STATE_VOICE_DISCLOSURE, settings.voiceDisclosureAccepted)
        putBoolean(STATE_ONBOARDING_COMPLETE, settings.onboardingComplete)
    }

    private fun Bundle.restoredSettings(): AppSettings? {
        if (!getBoolean(STATE_HAS_SETTINGS, false)) return null
        return AppSettings(
            treeUri = getString(STATE_TREE_URI)?.let(Uri::parse),
            voiceOnAtLaunch = getBoolean(STATE_VOICE_ON_AT_LAUNCH),
            onlineFallbackAllowed = getBoolean(STATE_ONLINE_FALLBACK),
            voiceDisclosureAccepted = getBoolean(STATE_VOICE_DISCLOSURE),
            onboardingComplete = getBoolean(STATE_ONBOARDING_COMPLETE),
        )
    }

    companion object {
        private const val STATE_REOPEN_AFTER_SAVE = "capture_reopen_after_save"
        const val ACTION_REVIEW = "com.sidenote.app.REVIEW"
        private const val STATE_PENDING_COMPLETION = "capture_pending_completion"
        private const val STATE_REVIEW_LAUNCH = "capture_review_launch"
        private const val STATE_HAS_SETTINGS = "capture_has_settings"
        private const val STATE_TREE_URI = "capture_tree_uri"
        private const val STATE_VOICE_ON_AT_LAUNCH = "capture_voice_on_at_launch"
        private const val STATE_ONLINE_FALLBACK = "capture_online_fallback"
        private const val STATE_VOICE_DISCLOSURE = "capture_voice_disclosure"
        private const val STATE_ONBOARDING_COMPLETE = "capture_onboarding_complete"
    }
}

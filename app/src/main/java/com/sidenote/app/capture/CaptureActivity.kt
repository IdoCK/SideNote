package com.sidenote.app.capture

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
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
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.ui.CaptureScreen
import com.sidenote.app.capture.ui.LocalReducedMotion
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.privacy.LockState
import com.sidenote.app.privacy.UnlockGate
import com.sidenote.app.ui.theme.SideNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        container = (application as SideNoteApplication).container
        dependencies = container.captureDependencies()
        lockState = container.lockState()
        lockState.refresh()

        setContent {
            SideNoteTheme {
                val locked by lockState.locked.collectAsState()
                LaunchedEffect(onboardingComplete.value, locked) {
                    if (onboardingComplete.value == false) {
                        if (locked) requestDismissalOnce() else routeToOnboarding()
                    }
                }
                val current = viewModel
                if (captureReady.value && current != null) {
                    val state by current.state.collectAsState()
                    CompositionLocalProvider(
                        LocalReducedMotion provides !ValueAnimator.areAnimatorsEnabled(),
                    ) {
                        CaptureScreen(
                            state = state,
                            onTextChanged = current::onUserEdit,
                            onVoiceToggle = current::onVoiceToggle,
                            onDiscard = current::discard,
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
        onCompletionSignal(CompletionSignal.RepeatedLaunch)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        initialSettings?.let { settings -> outState.putSettings(settings) }
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
        if (::lockState.isInitialized) lockState.refresh()
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
            closer = CaptureCloser(::finish),
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
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
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
        private const val STATE_HAS_SETTINGS = "capture_has_settings"
        private const val STATE_TREE_URI = "capture_tree_uri"
        private const val STATE_VOICE_ON_AT_LAUNCH = "capture_voice_on_at_launch"
        private const val STATE_ONLINE_FALLBACK = "capture_online_fallback"
        private const val STATE_VOICE_DISCLOSURE = "capture_voice_disclosure"
        private const val STATE_ONBOARDING_COMPLETE = "capture_onboarding_complete"
    }
}

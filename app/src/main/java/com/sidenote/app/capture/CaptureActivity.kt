package com.sidenote.app.capture

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.ui.CaptureScreen
import com.sidenote.app.capture.ui.LocalReducedMotion
import com.sidenote.app.ui.theme.SideNoteTheme
import kotlinx.coroutines.launch

class CaptureActivity : ComponentActivity() {
    private val viewModel: CaptureViewModel by viewModels {
        val container = (application as SideNoteApplication).container
        CaptureViewModel.Factory(container.captureDependencies())
    }
    private val externalSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.activeExternalSetupToken()?.let { token ->
            viewModel.onExternalSetupResult(token)
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
        viewModel.attachHost(
            token = this,
            haptic = HapticConfirmation {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            },
            closer = CaptureCloser(::finish),
        )
        setContent {
            SideNoteTheme {
                val state by viewModel.state.collectAsState()
                CompositionLocalProvider(
                    LocalReducedMotion provides !ValueAnimator.areAnimatorsEnabled(),
                ) {
                    CaptureScreen(
                        state = state,
                        onTextChanged = viewModel::onUserEdit,
                        onVoiceToggle = viewModel::onVoiceToggle,
                        onDiscard = viewModel::discard,
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.completionSignals.collect(viewModel::complete)
            }
        }
        viewModel.start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.complete(CompletionSignal.RepeatedLaunch)
    }

    fun launchExternalSetup(intent: Intent): Boolean {
        val token = viewModel.beginExternalSetup() ?: return false
        return try {
            externalSetupLauncher.launch(intent)
            true
        } catch (_: Exception) {
            viewModel.onExternalSetupLaunchFailed(token)
            false
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onCaptureStarted()
    }

    override fun onStop() {
        viewModel.onCaptureStopped(
            completeIfBackgrounded = !isChangingConfigurations && !isFinishing,
        )
        super.onStop()
    }

    override fun onDestroy() {
        viewModel.detachHost(this)
        super.onDestroy()
    }
}

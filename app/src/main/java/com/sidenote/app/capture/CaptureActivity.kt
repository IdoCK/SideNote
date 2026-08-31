package com.sidenote.app.capture

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.ui.theme.SideNoteTheme
import kotlinx.coroutines.launch

class CaptureActivity : ComponentActivity() {
    private val viewModel: CaptureViewModel by viewModels {
        val container = (application as SideNoteApplication).container
        CaptureViewModel.Factory(container.captureDependencies())
    }
    private val externalSetupGate = ExternalSetupLaunchGate(
        disarm = { viewModel.disarmBackgroundCompletionForSetup() },
        rearm = { viewModel.armBackgroundCompletionAfterSetup() },
    )
    @Volatile private var externalSetupToken: Long? = null
    private val externalSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        externalSetupToken?.let { token ->
            if (externalSetupGate.onResult(token)) {
                externalSetupToken = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        viewModel.attachHost(
            token = this,
            haptic = HapticConfirmation {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            },
            closer = CaptureCloser(::finish),
        )
        setContent {
            SideNoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF050505),
                ) {
                    Text(
                        text = "Capture",
                        color = Color(0xFFF5F2EA),
                        modifier = Modifier.semantics { heading() },
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
        val token = externalSetupGate.begin() ?: return false
        externalSetupToken = token
        return try {
            externalSetupLauncher.launch(intent)
            true
        } catch (_: Exception) {
            if (externalSetupGate.onLaunchFailed(token)) {
                externalSetupToken = null
            }
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

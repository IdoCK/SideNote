package com.sidenote.app.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sidenote.app.data.settings.AppSettings

const val ONBOARDING_ROOT_TAG = "onboarding-root"

enum class OnboardingStep(val number: Int) {
    Folder(1),
    Voice(2),
    QuickTap(3),
}

enum class SpeechPreparationState {
    Idle,
    Checking,
    Available,
    TypedOnly,
    Downloading,
    DownloadRequested,
}

@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    settings: AppSettings,
    folderLabel: String?,
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    speechPreparation: SpeechPreparationState,
    message: String?,
    onChooseFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
    onAcceptVoiceDisclosure: (Boolean) -> Unit,
    onOnlineFallbackChange: (Boolean) -> Unit,
    onCheckSpeech: () -> Unit,
    onDownloadModels: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF111111),
        contentColor = Color(0xFFF4F1EA),
        modifier = modifier.fillMaxSize().testTag(ONBOARDING_ROOT_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "SideNote",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text("Step ${step.number} of 3", color = Color(0xFFAAA7A0))
            when (step) {
                OnboardingStep.Folder -> FolderStep(
                    folderLabel = folderLabel,
                    onChooseFolder = onChooseFolder,
                    onContinue = onContinue,
                )
                OnboardingStep.Voice -> VoiceStep(
                    settings = settings,
                    microphoneGranted = microphoneGranted,
                    notificationsGranted = notificationsGranted,
                    speechPreparation = speechPreparation,
                    onRequestPermissions = onRequestPermissions,
                    onAcceptVoiceDisclosure = onAcceptVoiceDisclosure,
                    onOnlineFallbackChange = onOnlineFallbackChange,
                    onCheckSpeech = onCheckSpeech,
                    onDownloadModels = onDownloadModels,
                    onContinue = onContinue,
                )
                OnboardingStep.QuickTap -> QuickTapStep(onContinue)
            }
            message?.let {
                Text(
                    it,
                    color = Color(0xFFF4F1EA),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun FolderStep(
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onContinue: () -> Unit,
) {
    Text("Choose where SideNote should keep your daily Markdown files.")
    Button(onClick = onChooseFolder, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
        Text("Choose notes folder")
    }
    if (folderLabel != null) Text("Selected: $folderLabel")
    Button(
        onClick = onContinue,
        enabled = folderLabel != null,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text("Continue")
    }
}

@Composable
private fun VoiceStep(
    settings: AppSettings,
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    speechPreparation: SpeechPreparationState,
    onRequestPermissions: () -> Unit,
    onAcceptVoiceDisclosure: (Boolean) -> Unit,
    onOnlineFallbackChange: (Boolean) -> Unit,
    onCheckSpeech: () -> Unit,
    onDownloadModels: () -> Unit,
    onContinue: () -> Unit,
) {
    Text("Allow voice capture and reminders")
    Text("Microphone: ${if (microphoneGranted) "Allowed" else "Not allowed"}")
    Text("Notifications: ${if (notificationsGranted) "Allowed" else "Not allowed"}")
    Button(onClick = onRequestPermissions, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
        Text("Allow microphone & notifications")
    }
    Text(
        "Voice is processed on this phone when available. When necessary, Android's speech service may process it online.",
    )
    Text("SideNote has no backend. Online recognition is not end-to-end private.")
    if (!settings.voiceDisclosureAccepted) {
        Button(
            onClick = { onAcceptVoiceDisclosure(true) },
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) {
            Text("I understand")
        }
    }
    OnlineFallbackToggle(
        checked = settings.onlineFallbackAllowed,
        enabled = settings.voiceDisclosureAccepted,
        onCheckedChange = onOnlineFallbackChange,
    )
    Text("SideNote checks English (US) and Hebrew speech support on this device.")
    val speechPreparationInProgress =
        speechPreparation == SpeechPreparationState.Checking ||
            speechPreparation == SpeechPreparationState.Downloading
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onCheckSpeech,
            enabled = !speechPreparationInProgress,
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
        ) {
            Text("Check English + Hebrew")
        }
        Button(
            onClick = onDownloadModels,
            enabled = !speechPreparationInProgress,
            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
        ) {
            Text("Download speech models")
        }
    }
    Text(speechPreparation.description(), color = Color(0xFFAAA7A0))
    Button(
        onClick = onContinue,
        enabled = settings.voiceDisclosureAccepted,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text("Continue")
    }
}

@Composable
private fun OnlineFallbackToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Allow online voice recognition", modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun QuickTapStep(onContinue: () -> Unit) {
    Text("Set up Quick Tap on your Pixel")
    Text("Settings → System → Gestures → Quick Tap → Open app → SideNote → Capture")
    Text("Choose the Capture shortcut using the settings icon next to SideNote. The SideNote app icon opens Review. Pixel controls whether Quick Tap is available while locked or with the screen off.", color = Color(0xFFAAA7A0))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
    ) {
        Text("Finish setup")
    }
}

private fun SpeechPreparationState.description(): String = when (this) {
    SpeechPreparationState.Idle -> "Speech support has not been checked yet."
    SpeechPreparationState.Checking -> "Checking English and Hebrew…"
    SpeechPreparationState.Available -> "Speech service ready. Try English and Hebrew in Capture."
    SpeechPreparationState.TypedOnly -> "One or both languages need a model or service. Typing still works."
    SpeechPreparationState.Downloading -> "Requesting supported speech model downloads…"
    SpeechPreparationState.DownloadRequested -> "Supported speech model downloads were requested."
}

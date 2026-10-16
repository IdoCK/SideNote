package com.sidenote.app.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sidenote.app.data.settings.AppSettings

private const val VoiceDisclosure =
    "Voice is processed on this phone when available. When necessary, Android's speech service may process it online."

@Composable
fun SettingsScreen(
    settings: AppSettings,
    folderLabel: String,
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onVoiceOnAtLaunchChange: (Boolean) -> Unit,
    onOnlineFallbackChange: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    onShowDates: () -> Unit = onBack,
    onShowProjects: () -> Unit = onBack,
) {
    var showDisclosure by remember { mutableStateOf(false) }
    Surface(
        color = ReviewBackground,
        contentColor = ReviewText,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReviewNavigation(
                selected = null,
                onShowDates = onShowDates,
                onShowProjects = onShowProjects,
                onOpenSettings = {},
            )
            HorizontalDivider(color = Color(0xFF3A3A3A))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message?.let { feedback ->
                    Text(
                        text = feedback,
                        color = ReviewText,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                SettingSection("Notes folder") {
                    Text(folderLabel.ifBlank { "Not selected" }, color = ReviewSubdued)
                    Text(
                        "Folder access is granted through Android's folder picker. Choose a folder and tap Use this folder to allow saving.",
                        color = ReviewSubdued,
                    )
                    Button(onClick = onChooseFolder, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text("Change folder")
                    }
                    Text(
                        "Changing folders does not move files from the old folder.",
                        color = ReviewSubdued,
                    )
                }
                SettingSection("Capture") {
                    Text(
                        "Punctuation is added automatically when supported. You can also say “insert period”, “insert question mark”, “insert comma”, “new line”, or “new paragraph”.",
                        color = ReviewSubdued,
                    )
                    SettingsToggle(
                        label = "Voice on at launch",
                        checked = settings.voiceOnAtLaunch,
                        onCheckedChange = onVoiceOnAtLaunchChange,
                    )
                    SettingsToggle(
                        label = "Allow online voice recognition",
                        checked = settings.onlineFallbackAllowed,
                        onCheckedChange = { checked ->
                            if (checked && !settings.voiceDisclosureAccepted) {
                                showDisclosure = true
                            } else {
                                onOnlineFallbackChange(checked)
                            }
                        },
                    )
                }
                SettingSection("Permissions") {
                    Text("Microphone: ${permissionLabel(microphoneGranted)}")
                    Text("Notifications: ${permissionLabel(notificationsGranted)}")
                    if (!notificationsGranted) {
                        Text(
                            "The unprocessed-note reminder is unavailable. Capture and Review still work.",
                            color = ReviewSubdued,
                        )
                    }
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                        Text("Review permissions")
                    }
                }
                SettingSection("Power button capture") {
                    Text("Choose SideNote in Android Settings → Apps → Default apps → Digital assistant app. This replaces Gemini as your default assistant.")
                    Text("Then choose Digital assistant under Settings → System → Gestures → Press and hold power button. Hold Power to open Capture, including from the lock screen.")
                    Text("SideNote does not listen for a wake word or monitor motion in the background.", color = ReviewSubdued)
                }
                SettingSection("Pixel Quick Tap") {
                    Text("Settings → System → Gestures → Quick Tap → Open app → SideNote → Capture")
                }
                SettingSection("Your notes stay yours") {
                    Text(
                        "Markdown files are the source of truth. SideNote reads and updates those files directly; it does not keep a separate note or project database.",
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Online voice recognition") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(VoiceDisclosure)
                    Text("SideNote has no backend. Online recognition is not end-to-end private.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        onOnlineFallbackChange(true)
                    },
                ) {
                    Text("Allow online voice")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) { Text("Keep off") }
            },
        )
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        content()
        HorizontalDivider(color = Color(0xFF3A3A3A))
    }
}

@Composable
internal fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun permissionLabel(granted: Boolean): String = if (granted) "Allowed" else "Not allowed"

package com.sidenote.app.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidenote.app.R
import com.sidenote.app.capture.CaptureState
import com.sidenote.app.capture.CaptureStatus
import com.sidenote.app.data.markdown.ProjectSyntax

const val CAPTURE_ROOT_TAG = "capture-root"
const val CAPTURE_BLOB_REGION_TAG = "capture-blob-region"
const val CAPTURE_WRITING_REGION_TAG = "capture-writing-region"
const val CAPTURE_CARD_TAG = "capture-card"
const val CAPTURE_INPUT_TAG = "capture-input"
const val PROJECT_CHIP_TAG_PREFIX = "project-chip"
const val PROJECT_SUGGESTION_TAG_PREFIX = "project-suggestion"

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun CaptureSelectionScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides CaptureVisualContract.TextSelectionColors,
        content = content,
    )
}

@Composable
fun CaptureScreen(
    state: CaptureState,
    onTextChanged: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onVoiceToggle: () -> Unit,
    onDiscard: () -> Unit,
    onRetryRecovery: () -> Unit = {},
) {
    CaptureSelectionScope {
        val projects = remember(state.draft.text) {
            ProjectSyntax.tokens(state.draft.text)
        }
        val suggestions = remember(state.draft, state.projectSuggestions) {
            ProjectSuggestionEditor.matches(state.draft, state.projectSuggestions)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CaptureVisualContract.Background)
                .testTag(CAPTURE_ROOT_TAG)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(CaptureVisualContract.UpperRegionWeight)
                    .testTag(CAPTURE_BLOB_REGION_TAG),
                contentAlignment = Alignment.Center,
            ) {
                VoiceBlob(
                    enabled = state.voiceEnabled,
                    rms = state.rms,
                    reducedMotion = LocalReducedMotion.current,
                    onToggle = onVoiceToggle,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(CaptureVisualContract.LowerRegionWeight)
                    .testTag(CAPTURE_WRITING_REGION_TAG)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                val errorMessage = when (state.status) {
                    CaptureStatus.RecoveryUnreadable -> R.string.capture_recovery_unreadable
                    CaptureStatus.SaveFailed -> if (state.recoveryWriteFailed) {
                        R.string.capture_save_and_recovery_failed
                    } else {
                        R.string.capture_save_failed
                    }
                    CaptureStatus.SaveUncertain -> if (state.recoveryWriteFailed) {
                        R.string.capture_save_uncertain_without_recovery
                    } else {
                        R.string.capture_save_uncertain
                    }
                    CaptureStatus.SpeechUnavailable -> R.string.capture_voice_unavailable
                    else -> if (state.recoveryWriteFailed) R.string.capture_recovery_write_failed else null
                }
                errorMessage?.let { message ->
                    Text(
                        text = stringResource(message),
                        color = CaptureVisualContract.Paper,
                        modifier = Modifier
                            .fillMaxWidth(CaptureVisualContract.CardWidthFraction)
                            .padding(bottom = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (state.status == CaptureStatus.RecoveryUnreadable) {
                    TextButton(onClick = onRetryRecovery, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.capture_retry_recovery), color = CaptureVisualContract.Paper)
                    }
                }
                if (projects.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(CaptureVisualContract.CardWidthFraction)
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        projects.forEach { project ->
                            ProjectChip(project.display)
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(CaptureVisualContract.CardWidthFraction)
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { project ->
                            TextButton(
                                onClick = {
                                    onTextChanged(ProjectSuggestionEditor.apply(state.draft, project))
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("$PROJECT_SUGGESTION_TAG_PREFIX.${project.key}"),
                            ) {
                                Text("@${project.display}", color = CaptureVisualContract.Paper)
                            }
                        }
                    }
                }
                WritingCard(
                    state = state,
                    onTextChanged = onTextChanged,
                )
                if (state.draft.text.isNotEmpty() || state.status == CaptureStatus.RecoveryUnreadable) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDiscard,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = CaptureVisualContract.Paper,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.discard),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WritingCard(
    state: CaptureState,
    onTextChanged: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(CaptureVisualContract.CardWidthFraction)
            .heightIn(min = CaptureVisualContract.CardMinimumHeight)
            .testTag(CAPTURE_CARD_TAG),
        color = CaptureVisualContract.Paper,
        contentColor = CaptureVisualContract.Ink,
        shape = CaptureVisualContract.CardShape,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (state.draft.text.isEmpty()) {
                Text(
                    text = stringResource(R.string.capture_placeholder),
                    color = CaptureVisualContract.MutedInk,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Start,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
            }
            BasicTextField(
                readOnly = state.status == CaptureStatus.RecoveryUnreadable ||
                    state.status == CaptureStatus.Finalizing || state.status == CaptureStatus.Saving,
                value = state.draft,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 116.dp, max = 224.dp)
                    .testTag(CAPTURE_INPUT_TAG),
                textStyle = TextStyle(
                    color = CaptureVisualContract.Ink,
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Start,
                    textDirection = TextDirection.Content,
                ),
                cursorBrush = SolidColor(CaptureVisualContract.Ink),
                minLines = 4,
                maxLines = 8,
            )
        }
    }
}

@Composable
private fun ProjectChip(project: String) {
    Surface(
        // Bound the paragraph before RTL alignment; otherwise a shrink-wrapped label can
        // align to FlowRow's loose maximum width and draw outside its measured chip.
        modifier = Modifier.width(IntrinsicSize.Max).testTag("$PROJECT_CHIP_TAG_PREFIX.$project"),
        color = CaptureVisualContract.Chip,
        contentColor = CaptureVisualContract.Paper,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    ) {
        Text(
            text = "@$project",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 13.sp,
            textAlign = TextAlign.Start,
            style = TextStyle(textDirection = TextDirection.Content),
        )
    }
}

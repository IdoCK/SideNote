package com.sidenote.app.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidenote.app.R
import com.sidenote.app.capture.CaptureState
import com.sidenote.app.data.markdown.ProjectSyntax

const val CAPTURE_ROOT_TAG = "capture-root"
const val CAPTURE_CARD_TAG = "capture-card"
const val CAPTURE_INPUT_TAG = "capture-input"
const val PROJECT_CHIP_TAG_PREFIX = "project-chip"

val CaptureCardCornerRadius = SemanticsPropertyKey<Float>("Capture card corner radius dp")
val LocalReducedMotion = staticCompositionLocalOf { false }

private val Background = Color(0xFF050505)
private val Paper = Color(0xFFF3F0E8)
private val Ink = Color(0xFF0A0A0A)
private val MutedInk = Color(0xFF5E5C57)
private val Chip = Color(0xFF252525)

@Composable
fun CaptureScreen(
    state: CaptureState,
    onTextChanged: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onVoiceToggle: () -> Unit,
    onDiscard: () -> Unit,
) {
    val projects = ProjectSyntax.tokens(state.draft.text)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .testTag(CAPTURE_ROOT_TAG)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            if (projects.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    projects.forEach { project ->
                        ProjectChip(project.display)
                    }
                }
            }
            WritingCard(
                state = state,
                onTextChanged = onTextChanged,
            )
            if (state.draft.text.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDiscard,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Paper),
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

@Composable
private fun WritingCard(
    state: CaptureState,
    onTextChanged: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .heightIn(min = 144.dp)
            .testTag(CAPTURE_CARD_TAG)
            .semantics { this[CaptureCardCornerRadius] = 4f },
        color = Paper,
        contentColor = Ink,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (state.draft.text.isEmpty()) {
                Text(
                    text = stringResource(R.string.capture_placeholder),
                    color = MutedInk,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Start,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
            }
            BasicTextField(
                value = state.draft,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 116.dp, max = 224.dp)
                    .testTag(CAPTURE_INPUT_TAG),
                textStyle = TextStyle(
                    color = Ink,
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Start,
                    textDirection = TextDirection.Content,
                ),
                cursorBrush = SolidColor(Ink),
                minLines = 4,
                maxLines = 8,
            )
        }
    }
}

@Composable
private fun ProjectChip(project: String) {
    Surface(
        modifier = Modifier.testTag("$PROJECT_CHIP_TAG_PREFIX.$project"),
        color = Chip,
        contentColor = Paper,
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

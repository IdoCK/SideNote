package com.sidenote.app.capture

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

data class CaptureState(
    val draft: TextFieldValue = TextFieldValue(),
    val voiceEnabled: Boolean = true,
    val speechOwnedRange: TextRange? = null,
    val rms: Float = 0f,
    val status: CaptureStatus = CaptureStatus.Ready,
)

enum class CaptureStatus {
    Ready,
    Saving,
    SaveFailed,
    SpeechUnavailable,
    Saved,
    Discarded,
}

enum class CompletionSignal {
    ScreenOff,
    FaceDown,
    RepeatedLaunch,
    Backgrounded,
}

fun interface SpeechControl {
    fun stop()
}

fun interface HapticConfirmation {
    fun confirm()
}

fun interface CaptureCloser {
    fun close()
}

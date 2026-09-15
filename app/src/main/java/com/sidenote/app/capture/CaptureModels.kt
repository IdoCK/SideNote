package com.sidenote.app.capture

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.sidenote.app.data.markdown.ProjectToken

data class CaptureState(
    val draft: TextFieldValue = TextFieldValue(),
    val voiceEnabled: Boolean = true,
    val voicePhase: VoicePhase = VoicePhase.Starting,
    val speechOwnedRange: TextRange? = null,
    val rms: Float = 0f,
    val speechPitch: Float = 0f,
    val speechTone: Float = 0f,
    val status: CaptureStatus = CaptureStatus.Ready,
    val savedTime: String? = null,
    val recoveryWriteFailed: Boolean = false,
    val projectSuggestions: List<ProjectToken> = emptyList(),
)

enum class VoicePhase { Starting, Listening, Processing, Retrying }

enum class CaptureStatus {
    Ready,
    Finalizing,
    RecoveryUnreadable,
    Saving,
    SaveFailed,
    SaveUncertain,
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

    /** Stop acquisition, but give the current utterance a bounded chance to finish. */
    suspend fun finish() = stop()
}

fun interface HapticConfirmation {
    fun confirm()
}

fun interface CaptureCloser {
    fun close()
}

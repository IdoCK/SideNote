package com.sidenote.app.capture.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object CaptureVisualContract {
    val Background = Color(0xFF050505)
    val Paper = Color(0xFFF3F0E8)
    val Ink = Color(0xFFF3F0E8)
    val MutedInk = Color(0xFFA6A6A6)
    val CardBorder = Color(0xFF727272)
    val Chip = Color(0xFF252525)
    val EnabledBlob = Color(0xFFD8D6D0)
    val DisabledBlob = Color(0xFF626262)

    val TextSelectionColors = TextSelectionColors(
        handleColor = Paper,
        backgroundColor = Color(0x665E5E5E),
    )

    val BlobSize = 220.dp
    val CardMinimumHeight = 144.dp
    val CardShape = RoundedCornerShape(4.dp)
    const val CardWidthFraction = 0.90f

    const val BlobPointCount = 48
    const val BlobSpeechThreshold = 0.20f
    const val BlobMinimumScale = 0.88f
    const val BlobMaximumScale = 1.28f
}

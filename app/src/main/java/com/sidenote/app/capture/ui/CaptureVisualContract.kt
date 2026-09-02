package com.sidenote.app.capture.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object CaptureVisualContract {
    val Background = Color(0xFF050505)
    val Paper = Color(0xFFF3F0E8)
    val Ink = Color(0xFF0A0A0A)
    val MutedInk = Color(0xFF5E5C57)
    val Chip = Color(0xFF252525)
    val EnabledBlob = Color(0xFFD8D6D0)
    val DisabledBlob = Color(0xFF626262)

    val TextSelectionColors = TextSelectionColors(
        handleColor = Paper,
        backgroundColor = Color(0x665E5E5E),
    )

    val BlobSize = 132.dp
    val CardMinimumHeight = 144.dp
    val CardShape = RoundedCornerShape(4.dp)
    const val CardWidthFraction = 0.84f
    const val UpperRegionWeight = 1f
    const val LowerRegionWeight = 1f

    const val BlobPointCount = 16
    const val BlobLobeCount = 4
    const val BlobSpeechThreshold = 0.08f
    const val BlobMinimumScale = 0.96f
    const val BlobMaximumScale = 1.08f
}

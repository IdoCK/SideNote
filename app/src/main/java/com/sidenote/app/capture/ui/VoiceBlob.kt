package com.sidenote.app.capture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sidenote.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class BlobPaint {
    Filled,
    Hollow,
}

data class BlobGeometry(
    val radiusScales: List<Float>,
    val paint: BlobPaint,
) {
    val isCircle: Boolean
        get() = radiusScales.all { it == 1f }

    companion object {
        const val POINT_COUNT = 16
        private const val SPEECH_THRESHOLD = 0.08f

        fun from(
            rms: Float,
            phase: Float,
            enabled: Boolean,
            reducedMotion: Boolean,
        ): BlobGeometry {
            if (!enabled) return circle(BlobPaint.Hollow)
            val normalizedRms = rms.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            if (reducedMotion || normalizedRms <= SPEECH_THRESHOLD) {
                return circle(BlobPaint.Filled)
            }

            val speech = ((normalizedRms - SPEECH_THRESHOLD) / (1f - SPEECH_THRESHOLD))
                .coerceIn(0f, 1f)
            val baseScale = 1f + (0.02f * speech)
            val lobeScale = 0.06f * speech
            return BlobGeometry(
                radiusScales = List(POINT_COUNT) { index ->
                    val angle = TWO_PI * index / POINT_COUNT
                    baseScale + lobeScale * cos((4f * angle) + phase)
                },
                paint = BlobPaint.Filled,
            )
        }

        private fun circle(paint: BlobPaint) = BlobGeometry(
            radiusScales = List(POINT_COUNT) { 1f },
            paint = paint,
        )

        private const val TWO_PI = (2.0 * PI).toFloat()
    }
}

@Composable
fun VoiceBlob(
    enabled: Boolean,
    rms: Float,
    reducedMotion: Boolean,
    onToggle: () -> Unit,
) {
    val description = stringResource(
        if (enabled) R.string.voice_input_on else R.string.voice_input_off,
    )
    val geometry = BlobGeometry.from(
        rms = rms,
        phase = rms.coerceIn(0f, 1f) * PI.toFloat(),
        enabled = enabled,
        reducedMotion = reducedMotion,
    )
    Canvas(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(132.dp)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .semantics { contentDescription = description },
    ) {
        val strokeWidth = 3.dp.toPx()
        val baseRadius = (min(size.width, size.height) / 2f - strokeWidth) / MAX_SCALE
        val center = this.center
        if (geometry.isCircle) {
            drawCircle(
                color = if (geometry.paint == BlobPaint.Filled) {
                    EnabledBlob
                } else {
                    DisabledBlob
                },
                radius = baseRadius,
                center = center,
                style = if (geometry.paint == BlobPaint.Filled) {
                    androidx.compose.ui.graphics.drawscope.Fill
                } else {
                    Stroke(width = strokeWidth)
                },
            )
        } else {
            val points = geometry.radiusScales.mapIndexed { index, scale ->
                val angle = TWO_PI * index / BlobGeometry.POINT_COUNT
                Offset(
                    x = center.x + cos(angle) * baseRadius * scale,
                    y = center.y + sin(angle) * baseRadius * scale,
                )
            }
            drawPath(
                path = points.toClosedCubicPath(),
                color = EnabledBlob,
            )
        }
    }
}

private fun List<Offset>.toClosedCubicPath(): Path = Path().apply {
    if (isEmpty()) return@apply
    moveTo(first().x, first().y)
    indices.forEach { index ->
        val previous = get((index - 1 + size) % size)
        val current = get(index)
        val next = get((index + 1) % size)
        val following = get((index + 2) % size)
        cubicTo(
            current.x + (next.x - previous.x) / 6f,
            current.y + (next.y - previous.y) / 6f,
            next.x - (following.x - current.x) / 6f,
            next.y - (following.y - current.y) / 6f,
            next.x,
            next.y,
        )
    }
    close()
}

private val EnabledBlob = Color(0xFFD8D6D0)
private val DisabledBlob = Color(0xFF626262)
private const val MAX_SCALE = 1.08f
private const val TWO_PI = (2.0 * PI).toFloat()

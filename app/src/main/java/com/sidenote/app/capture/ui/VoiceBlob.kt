package com.sidenote.app.capture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

    val cubicSegments: List<BlobCubicSegment>
        get() {
            val anchors = radiusScales.mapIndexed { index, scale ->
                val angle = TWO_PI * index / POINT_COUNT
                BlobPoint(
                    x = cos(angle) * scale,
                    y = sin(angle) * scale,
                )
            }
            return anchors.mapIndexed { index, current ->
                val previous = anchors[(index - 1 + anchors.size) % anchors.size]
                val next = anchors[(index + 1) % anchors.size]
                val following = anchors[(index + 2) % anchors.size]
                BlobCubicSegment(
                    start = current,
                    control1 = BlobPoint(
                        x = current.x + (next.x - previous.x) / 6f,
                        y = current.y + (next.y - previous.y) / 6f,
                    ).bounded(),
                    control2 = BlobPoint(
                        x = next.x - (following.x - current.x) / 6f,
                        y = next.y - (following.y - current.y) / 6f,
                    ).bounded(),
                    end = next,
                )
            }
        }

    companion object {
        const val POINT_COUNT = CaptureVisualContract.BlobPointCount

        fun from(
            rms: Float,
            phase: Float,
            enabled: Boolean,
            reducedMotion: Boolean,
        ): BlobGeometry {
            if (!enabled) return circle(BlobPaint.Hollow)
            val normalizedRms = rms.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            if (
                reducedMotion ||
                normalizedRms <= CaptureVisualContract.BlobSpeechThreshold
            ) {
                return circle(BlobPaint.Filled)
            }

            val speech = (
                (normalizedRms - CaptureVisualContract.BlobSpeechThreshold) /
                    (1f - CaptureVisualContract.BlobSpeechThreshold)
                ).coerceIn(0f, 1f)
            val fullSpeechBaseScale =
                (CaptureVisualContract.BlobMinimumScale +
                    CaptureVisualContract.BlobMaximumScale) / 2f
            val fullSpeechLobeScale =
                (CaptureVisualContract.BlobMaximumScale -
                    CaptureVisualContract.BlobMinimumScale) / 2f
            val baseScale = 1f + ((fullSpeechBaseScale - 1f) * speech)
            val lobeScale = fullSpeechLobeScale * speech
            return BlobGeometry(
                radiusScales = List(POINT_COUNT) { index ->
                    val angle = TWO_PI * index / POINT_COUNT
                    (
                        baseScale + lobeScale * cos(
                            (CaptureVisualContract.BlobLobeCount * angle) + phase,
                        )
                        ).coerceIn(
                        CaptureVisualContract.BlobMinimumScale,
                        CaptureVisualContract.BlobMaximumScale,
                    )
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

data class BlobPoint(
    val x: Float,
    val y: Float,
)

data class BlobCubicSegment(
    val start: BlobPoint,
    val control1: BlobPoint,
    val control2: BlobPoint,
    val end: BlobPoint,
)

private fun BlobPoint.bounded(): BlobPoint = BlobPoint(
    x = x.coerceIn(
        -CaptureVisualContract.BlobMaximumScale,
        CaptureVisualContract.BlobMaximumScale,
    ),
    y = y.coerceIn(
        -CaptureVisualContract.BlobMaximumScale,
        CaptureVisualContract.BlobMaximumScale,
    ),
)

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
            .size(CaptureVisualContract.BlobSize)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .semantics { contentDescription = description },
    ) {
        val strokeWidth = 3.dp.toPx()
        val baseRadius =
            (min(size.width, size.height) / 2f - strokeWidth) /
                CaptureVisualContract.BlobMaximumScale
        val center = this.center
        if (geometry.isCircle) {
            drawCircle(
                color = if (geometry.paint == BlobPaint.Filled) {
                    CaptureVisualContract.EnabledBlob
                } else {
                    CaptureVisualContract.DisabledBlob
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
            drawPath(
                path = geometry.cubicSegments.toPath(center, baseRadius),
                color = CaptureVisualContract.EnabledBlob,
            )
        }
    }
}

private fun List<BlobCubicSegment>.toPath(center: Offset, radius: Float): Path = Path().apply {
    if (isEmpty()) return@apply
    val first = first().start.toOffset(center, radius)
    moveTo(first.x, first.y)
    forEach { segment ->
        val control1 = segment.control1.toOffset(center, radius)
        val control2 = segment.control2.toOffset(center, radius)
        val end = segment.end.toOffset(center, radius)
        cubicTo(
            control1.x,
            control1.y,
            control2.x,
            control2.y,
            end.x,
            end.y,
        )
    }
    close()
}

private fun BlobPoint.toOffset(center: Offset, radius: Float): Offset = Offset(
    x = center.x + x * radius,
    y = center.y + y * radius,
)

private const val TWO_PI = (2.0 * PI).toFloat()

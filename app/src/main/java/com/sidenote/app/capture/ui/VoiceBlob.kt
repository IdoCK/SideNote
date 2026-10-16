package com.sidenote.app.capture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
            pitch: Float = 0f,
            tone: Float = 0f,
        ): BlobGeometry {
            if (!enabled) return circle(BlobPaint.Hollow)
            val normalizedRms = rms.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            if (reducedMotion || normalizedRms <= 0.001f) return circle(BlobPaint.Filled)
            val time = phase.takeIf(Float::isFinite) ?: 0f
            val speech = kotlin.math.sqrt(normalizedRms)
            val brightness = tone.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            val vocalPitch = pitch.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            // A shared implicit field makes the three liquid lobes melt into the
            // body as their centers move, instead of rotating a regular waveform.
            val reach = 0.34f + 0.10f * brightness
            val droplets = List(3) { index ->
                val offset = index * TWO_PI / 3f
                val angle = offset + (0.5f + 0.2f * vocalPitch) * sin(time + index * 1.8f)
                val distance = 0.30f + reach * (0.5f + 0.5f * sin(2f * time + index * 2.1f))
                Triple(cos(angle) * distance, sin(angle) * distance, 0.26f + 0.06f * brightness)
            }
            val coreRadius = 0.67f + 0.035f * sin(time)
            return BlobGeometry(
                radiusScales = List(POINT_COUNT) { index ->
                    val angle = TWO_PI * index / POINT_COUNT
                    val dx = cos(angle)
                    val dy = sin(angle)
                    var low = 0.7f
                    var high = CaptureVisualContract.BlobMaximumScale
                    repeat(14) {
                        val radius = (low + high) * 0.5f
                        val x = dx * radius
                        val y = dy * radius
                        var field = coreRadius * coreRadius / (radius * radius)
                        droplets.forEach { (cx, cy, size) ->
                            val ox = x - cx
                            val oy = y - cy
                            field += size * size / (ox * ox + oy * oy).coerceAtLeast(0.0001f)
                        }
                        if (field > 1f) low = radius else high = radius
                    }
                    (1f + (((low + high) * 0.5f) - 1f) * speech * 1.8f).coerceIn(0.72f, CaptureVisualContract.BlobMaximumScale)
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
    phaseDescription: String = if (enabled) "Listening" else "Off",
    interactive: Boolean = true,
    pitch: Float = 0f,
    tone: Float = 0f,
) {
    val description = stringResource(
        if (enabled) R.string.voice_input_on else R.string.voice_input_off,
    )
    val targetLevel = if (enabled) rms.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f else 0f
    val level by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(if (reducedMotion) 0 else if (targetLevel == 0f) 140 else 55),
        label = "Microphone level",
    )
    var phase by remember { mutableFloatStateOf(0f) }
    val currentPitch by rememberUpdatedState(pitch)
    val currentLevel by rememberUpdatedState(targetLevel)
    val moving = enabled && !reducedMotion && (targetLevel > 0f || level > 0.001f)
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var previous = withInfiniteAnimationFrameNanos { it }
        val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) return@LaunchedEffect
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val elapsed = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f) / durationScale
                phase = (phase + elapsed * (2.5f + 4f * currentPitch + 2f * currentLevel)) % TWO_PI
                previous = now
            }
        }
    }
    Canvas(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(CaptureVisualContract.BlobSize)
            .toggleable(
                value = enabled,
                enabled = interactive,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .semantics {
                contentDescription = description
                stateDescription = phaseDescription
            },
    ) {
        val geometry = BlobGeometry.from(level, if (moving) phase else 0f, enabled, reducedMotion, pitch, tone)
        val strokeWidth = 3.dp.toPx()
        val baseRadius =
            (min(size.width, size.height) / 2f - strokeWidth) /
                CaptureVisualContract.BlobMaximumScale
        val center = this.center
        val fill = Brush.radialGradient(
            colors = listOf(CaptureVisualContract.Paper, CaptureVisualContract.EnabledBlob),
            center = center - Offset(baseRadius * 0.25f, baseRadius * 0.35f),
            radius = baseRadius * 1.5f,
        )
        if (geometry.isCircle) {
            if (geometry.paint == BlobPaint.Filled) {
                drawCircle(brush = fill, radius = baseRadius, center = center)
            } else {
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
            }
        } else {
            drawPath(
                path = geometry.cubicSegments.toPath(center, baseRadius),
                brush = fill,
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

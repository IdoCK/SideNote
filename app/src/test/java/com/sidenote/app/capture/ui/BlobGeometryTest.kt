package com.sidenote.app.capture.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class BlobGeometryTest {
    @Test
    fun measuredPitchAndToneChangeTheContourAtTheSameVolume() {
        val low = BlobGeometry.from(0.6f, 1.2f, true, false, pitch = 0.1f, tone = 0.1f)
        val high = BlobGeometry.from(0.6f, 1.2f, true, false, pitch = 0.9f, tone = 0.1f)
        val bright = BlobGeometry.from(0.6f, 1.2f, true, false, pitch = 0.1f, tone = 0.9f)
        assertThat(low.radiusScales).isNotEqualTo(high.radiusScales)
        assertThat(low.radiusScales).isNotEqualTo(bright.radiusScales)
    }
    @Test
    fun quietListeningIsStatic() {
        val first = BlobGeometry.from(0f, 0f, true, false)
        val next = BlobGeometry.from(0f, 1.2f, true, false)
        assertThat(first.paint).isEqualTo(BlobPaint.Filled)
        assertThat(first.isCircle).isTrue()
        assertThat(next.radiusScales).isEqualTo(first.radiusScales)
        (first.radiusScales + next.radiusScales).forEach {
            assertThat(it).isAtMost(1.28f)
            assertThat(it).isGreaterThan(0.7f)
        }
    }
    @Test
    fun disabledVoiceIsAHollowCircleRegardlessOfRmsOrPhase() {
        val geometry = BlobGeometry.from(
            rms = 1f,
            phase = 1.3f,
            enabled = false,
            reducedMotion = false,
        )

        assertThat(geometry.paint).isEqualTo(BlobPaint.Hollow)
        assertThat(geometry.isCircle).isTrue()
    }

    @Test
    fun speechRmsChangesControlPointsWithinApprovedScaleBounds() {
        val geometry = BlobGeometry.from(
            rms = 1f,
            phase = 0f,
            enabled = true,
            reducedMotion = false,
        )

        assertThat(geometry.isCircle).isFalse()
        assertThat(geometry.radiusScales.distinct().size).isGreaterThan(1)
        geometry.radiusScales.forEach { scale ->
            assertThat(scale).isAtLeast(0.72f)
            assertThat(scale).isAtMost(1.28f)
        }
    }

    @Test
    fun crossingSpeechThresholdDoesNotSnapTheLiquidContour() {
        val below = BlobGeometry.from(0.199f, 1f, true, false)
        val above = BlobGeometry.from(0.201f, 1f, true, false)
        below.radiusScales.zip(above.radiusScales).forEach { (a, b) ->
            assertThat(abs(a - b)).isLessThan(0.01f)
        }
    }
    @Test
    fun liquidContourIsClosedSmoothAndBoundedThroughoutItsCycle() {
        repeat(80) { frame ->
            val geometry = BlobGeometry.from(1f, frame * 0.08f, true, false)
            val segments = geometry.cubicSegments
            assertThat(segments.last().end).isEqualTo(segments.first().start)
            segments.indices.forEach { index ->
                val a = segments[index]
                val b = segments[(index + 1) % segments.size]
                assertThat(a.end).isEqualTo(b.start)
                assertThat(a.end.x - a.control2.x).isWithin(0.0001f).of(b.control1.x - b.start.x)
                assertThat(a.end.y - a.control2.y).isWithin(0.0001f).of(b.control1.y - b.start.y)
                listOf(a.start, a.control1, a.control2).forEach { point ->
                    assertThat(abs(point.x)).isAtMost(1.28f)
                    assertThat(abs(point.y)).isAtMost(1.28f)
                }
            }
        }
    }
    @Test
    fun sharedVisualContractProducesApprovedBlobCardAndFourDpOutline() {
        assertThat(CaptureVisualContract.BlobSize.value).isEqualTo(220f)
        assertThat(CaptureVisualContract.CardWidthFraction).isEqualTo(0.90f)
        assertThat(CaptureVisualContract.TextSelectionColors.handleColor)
            .isEqualTo(Color(0xFFF3F0E8))
        assertThat(CaptureVisualContract.TextSelectionColors.backgroundColor)
            .isEqualTo(Color(0x665E5E5E))

        val outline = CaptureVisualContract.CardShape.createOutline(
            size = Size(100f, 100f),
            layoutDirection = LayoutDirection.Ltr,
            density = Density(1f),
        )
        assertThat(outline).isInstanceOf(Outline.Rounded::class.java)
        val roundRect = (outline as Outline.Rounded).roundRect
        assertThat(roundRect.topLeftCornerRadius.x).isEqualTo(4f)
        assertThat(roundRect.topRightCornerRadius.x).isEqualTo(4f)
        assertThat(roundRect.bottomRightCornerRadius.x).isEqualTo(4f)
        assertThat(roundRect.bottomLeftCornerRadius.x).isEqualTo(4f)
    }

    @Test
    fun reducedMotionNeverChangesGeometry() {
        val first = BlobGeometry.from(
            rms = 0.2f,
            phase = 0f,
            enabled = true,
            reducedMotion = true,
        )
        val second = BlobGeometry.from(
            rms = 1f,
            phase = 2.8f,
            enabled = true,
            reducedMotion = true,
        )

        assertThat(first.isCircle).isTrue()
        assertThat(second).isEqualTo(first)
    }
}

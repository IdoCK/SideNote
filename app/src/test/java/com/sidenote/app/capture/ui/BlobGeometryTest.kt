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
    fun zeroRmsIsAPerfectFilledCircleAtEveryPhase() {
        listOf(0f, 0.7f, 2.4f).forEach { phase ->
            val geometry = BlobGeometry.from(
                rms = 0f,
                phase = phase,
                enabled = true,
                reducedMotion = false,
            )

            assertThat(geometry.paint).isEqualTo(BlobPaint.Filled)
            assertThat(geometry.isCircle).isTrue()
            assertThat(geometry.radiusScales).containsExactlyElementsIn(
                List(BlobGeometry.POINT_COUNT) { 1f },
            ).inOrder()
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
            assertThat(scale).isAtLeast(0.96f)
            assertThat(scale).isAtMost(1.08f)
        }
    }

    @Test
    fun belowAndAtSpeechThresholdStayCircularWhileAboveThresholdDeforms() {
        listOf(
            0f,
            CaptureVisualContract.BlobSpeechThreshold - 0.001f,
            CaptureVisualContract.BlobSpeechThreshold,
        ).forEach { rms ->
            assertThat(
                BlobGeometry.from(
                    rms = rms,
                    phase = 0f,
                    enabled = true,
                    reducedMotion = false,
                ).isCircle,
            ).isTrue()
        }

        assertThat(
            BlobGeometry.from(
                rms = CaptureVisualContract.BlobSpeechThreshold + 0.001f,
                phase = 0f,
                enabled = true,
                reducedMotion = false,
            ).isCircle,
        ).isFalse()
    }

    @Test
    fun fullSpeechGeometryHasExactlyFourLobesAndClosedCubicControls() {
        val geometry = BlobGeometry.from(
            rms = 1f,
            phase = 0f,
            enabled = true,
            reducedMotion = false,
        )

        val maxima = geometry.radiusScales.indices.filter { index ->
            val previous = geometry.radiusScales[
                (index - 1 + geometry.radiusScales.size) % geometry.radiusScales.size
            ]
            val next = geometry.radiusScales[(index + 1) % geometry.radiusScales.size]
            geometry.radiusScales[index] > previous && geometry.radiusScales[index] > next
        }
        val minima = geometry.radiusScales.indices.filter { index ->
            val previous = geometry.radiusScales[
                (index - 1 + geometry.radiusScales.size) % geometry.radiusScales.size
            ]
            val next = geometry.radiusScales[(index + 1) % geometry.radiusScales.size]
            geometry.radiusScales[index] < previous && geometry.radiusScales[index] < next
        }
        assertThat(maxima).containsExactly(0, 4, 8, 12).inOrder()
        assertThat(minima).containsExactly(2, 6, 10, 14).inOrder()
        maxima.forEach { index ->
            assertThat(geometry.radiusScales[index]).isWithin(0.0001f).of(1.08f)
        }
        minima.forEach { index ->
            assertThat(geometry.radiusScales[index]).isWithin(0.0001f).of(0.96f)
        }

        val segments = geometry.cubicSegments
        assertThat(segments).hasSize(16)
        assertThat(segments.last().end).isEqualTo(segments.first().start)
        segments.indices.forEach { index ->
            assertThat(segments[index].end)
                .isEqualTo(segments[(index + 1) % segments.size].start)
        }
        assertThat(segments.first().start.x).isWithin(0.0001f).of(1.08f)
        assertThat(segments.first().start.y).isWithin(0.0001f).of(0f)
        assertThat(segments.first().control1.x).isWithin(0.0001f).of(1.08f)
        assertThat(segments.first().control1.y).isWithin(0.0005f).of(0.1301f)
        assertThat(segments.first().control2.x).isWithin(0.0005f).of(1.0092f)
        assertThat(segments.first().control2.y).isWithin(0.0005f).of(0.2772f)
        segments.flatMap { segment ->
            listOf(segment.start, segment.control1, segment.control2, segment.end)
        }.forEach { point ->
            assertThat(abs(point.x)).isAtMost(1.08f)
            assertThat(abs(point.y)).isAtMost(1.08f)
        }
    }

    @Test
    fun sharedVisualContractProducesApprovedBlobCardAndFourDpOutline() {
        assertThat(CaptureVisualContract.BlobSize.value).isEqualTo(132f)
        assertThat(CaptureVisualContract.CardWidthFraction).isEqualTo(0.84f)
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

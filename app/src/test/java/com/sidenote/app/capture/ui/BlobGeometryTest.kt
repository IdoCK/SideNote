package com.sidenote.app.capture.ui

import com.google.common.truth.Truth.assertThat
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

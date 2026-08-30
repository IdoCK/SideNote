package com.sidenote.app.capture

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FaceDownDetectorTest {
    @Test
    fun emitsOnceAfterStableFaceDownWindow() = runTest {
        val detector = detector()

        detector.onZ(-9.2f, 0.milliseconds)
        detector.onZ(-9.0f, 749.milliseconds)

        assertThat(detector.events).isEmpty()

        detector.onZ(-9.1f, 750.milliseconds)

        assertThat(detector.events).containsExactly(CompletionSignal.FaceDown)
    }

    @Test
    fun movementCancelsCandidateAndRequiresANewStableWindow() = runTest {
        val detector = detector()

        detector.onZ(-9.2f, 0.milliseconds)
        detector.onZ(-8.0f, 500.milliseconds)
        detector.onZ(-9.1f, 600.milliseconds)
        detector.onZ(-9.0f, 1_349.milliseconds)

        assertThat(detector.events).isEmpty()

        detector.onZ(-9.0f, 1_350.milliseconds)

        assertThat(detector.events).containsExactly(CompletionSignal.FaceDown)
    }

    @Test
    fun hysteresisRequiresCrossingReleaseThresholdBeforeRearming() = runTest {
        val detector = detector()

        detector.onZ(-9.2f, 0.milliseconds)
        detector.onZ(-9.1f, 750.milliseconds)
        detector.onZ(-7.5f, 900.milliseconds)
        detector.onZ(-9.0f, 1_700.milliseconds)

        assertThat(detector.events).containsExactly(CompletionSignal.FaceDown)

        detector.onZ(-6.9f, 1_701.milliseconds)
        detector.onZ(-9.0f, 1_800.milliseconds)
        detector.onZ(-9.1f, 2_550.milliseconds)

        assertThat(detector.events).containsExactly(
            CompletionSignal.FaceDown,
            CompletionSignal.FaceDown,
        ).inOrder()
    }

    @Test
    fun stableFaceDownDoesNotEmitAgainUntilReleased() = runTest {
        val detector = detector()

        detector.onZ(-9.2f, 0.milliseconds)
        detector.onZ(-9.1f, 750.milliseconds)
        detector.onZ(-9.0f, 1_500.milliseconds)
        detector.onZ(-9.3f, 3_000.milliseconds)

        assertThat(detector.events).containsExactly(CompletionSignal.FaceDown)
    }

    @Test
    fun portraitAndFlatFaceUpReadingsNeverStartFaceDownDetection() = runTest {
        val detector = detector()

        detector.onZ(0f, 0.milliseconds)
        detector.onZ(1.5f, 800.milliseconds)
        detector.onZ(9.8f, 1_600.milliseconds)
        detector.onZ(8.9f, 2_400.milliseconds)

        assertThat(detector.events).isEmpty()
    }

    private fun detector() = FaceDownDetector(
        threshold = -8.5f,
        releaseThreshold = -7.0f,
        debounce = 750.milliseconds,
    )
}

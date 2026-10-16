package com.sidenote.app.capture

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Normalized acoustic measurements. Tone describes brightness, not emotion. */
data class SpeechAudioFeatures(
    val level: Float = 0f,
    val pitch: Float = 0f,
    val tone: Float = 0f,
)

/**
 * Thread-confined analyzer for mono 16 kHz PCM16. Processes at most 1024 samples
 * per call, with no temporal smoothing or retained audio between calls.
 */
internal class SpeechAudioAnalyzer {
    fun analyze(samples: ShortArray, count: Int = samples.size): SpeechAudioFeatures {
        val size = count.coerceIn(0, samples.size).coerceAtMost(MAX_SAMPLES)
        if (size < 2) return SpeechAudioFeatures()

        var mean = 0.0
        for (i in 0 until size) mean += samples[i].toDouble()
        mean /= size

        var energy = 0.0
        var differenceEnergy = 0.0
        for (i in 0 until size) {
            val value = samples[i] - mean
            energy += value * value
            if (i > 0) {
                val difference = samples[i].toDouble() - samples[i - 1].toDouble()
                differenceEnergy += difference * difference
            }
        }
        val rms = sqrt(energy / size) / 32768.0
        // About -50 dBFS: reject digital silence, DC and very quiet input exactly.
        if (rms <= NOISE_FLOOR) return SpeechAudioFeatures()

        val level = ((20.0 * log10(rms / NOISE_FLOOR)) / 42.0).coerceIn(0.0, 1.0)
        // First-difference energy emphasizes upper harmonics. Divide by signal
        // energy so changing volume alone does not change the brightness.
        val tone = (sqrt(differenceEnergy * size / ((size - 1) * energy)) / 0.6)
            .coerceIn(0.0, 1.0)
        val pitch = if (size >= 2 * MAX_LAG) estimatePitch(samples, size, mean) else 0.0
        return SpeechAudioFeatures(level.toFloat(), pitch.toFloat(), tone.toFloat())
    }

    private fun estimatePitch(samples: ShortArray, size: Int, mean: Double): Double {
        // Fixed lag range bounds this to fewer than 180k sample comparisons.
        // The temporary array contains correlations only, never PCM samples.
        val correlations = DoubleArray(MAX_LAG + 2)
        var strongest = 0.0
        for (lag in MIN_LAG - 1..MAX_LAG + 1) {
            var cross = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (i in 0 until size - lag) {
                val left = samples[i] - mean
                val right = samples[i + lag] - mean
                cross += left * right
                leftEnergy += left * left
                rightEnergy += right * right
            }
            val denominator = sqrt(leftEnergy * rightEnergy)
            val correlation = if (denominator > 0.0) cross / denominator else 0.0
            correlations[lag] = correlation
            if (lag in MIN_LAG..MAX_LAG) strongest = maxOf(strongest, correlation)
        }
        // Unvoiced noise has no sufficiently periodic peak.
        if (strongest < 0.65) return 0.0
        for (lag in MIN_LAG..MAX_LAG) {
            val previous = correlations[lag - 1]
            val current = correlations[lag]
            val next = correlations[lag + 1]
            if (current < strongest * 0.9 || current < 0.65 || current < previous || current < next) continue
            // Prefer the first strong peak, avoiding integer-multiple periods.
            val curvature = previous - 2 * current + next
            val offset = if (curvature < -1e-12) {
                (0.5 * (previous - next) / curvature).coerceIn(-0.5, 0.5)
            } else 0.0
            val hz = (SAMPLE_RATE / (lag + offset)).coerceIn(80.0, 500.0)
            return (ln(hz / 80.0) / ln(500.0 / 80.0)).coerceIn(0.0, 1.0)
        }
        return 0.0
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0
        const val MAX_SAMPLES = 1024
        const val MIN_LAG = 32
        const val MAX_LAG = 200
        const val NOISE_FLOOR = 0.003
    }
}

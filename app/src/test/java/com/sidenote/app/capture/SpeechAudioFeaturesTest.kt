package com.sidenote.app.capture

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAudioFeaturesTest {
    private val analyzer = SpeechAudioAnalyzer()

    @Test fun silenceBackgroundFloorAndDcReturnExactZero() {
        assertEquals(SpeechAudioFeatures(), analyzer.analyze(ShortArray(1024)))
        assertEquals(SpeechAudioFeatures(), analyzer.analyze(ShortArray(1024) { 12000 }))
        assertEquals(SpeechAudioFeatures(), analyzer.analyze(wave(120.0, 0.001)))
    }

    @Test fun louderSpeechHasHigherLevelAndSilenceImmediatelyResetsIt() {
        val soft = analyzer.analyze(wave(180.0, 0.04))
        val loud = analyzer.analyze(wave(180.0, 0.4))
        assertTrue(soft.level > 0f)
        assertTrue(loud.level > soft.level + 0.2f)
        assertEquals(SpeechAudioFeatures(), analyzer.analyze(ShortArray(1024)))
    }

    @Test fun pitchTracksKnownVoicedFrequenciesOnLogScale() {
        for (hz in listOf(80.0, 120.0, 180.0, 300.0, 500.0)) {
            val expected = (ln(hz / 80.0) / ln(500.0 / 80.0)).toFloat()
            assertEquals("pitch for $hz Hz", expected, analyzer.analyze(wave(hz, 0.3)).pitch, 0.035f)
        }
    }

    @Test fun harmonicRichSoundHasBrighterToneAtSameFundamental() {
        val pure = analyzer.analyze(wave(160.0, 0.3))
        val bright = analyzer.analyze(ShortArray(1024) { i ->
            val phase = 2 * PI * 160 * i / 16000
            ((0.2 * sin(phase) + 0.15 * sin(8 * phase)) * 32767).toInt().toShort()
        })
        assertTrue(bright.tone > pure.tone + 0.1f)
        assertEquals(pure.pitch, bright.pitch, 0.04f)
    }

    @Test fun randomNoiseIsUnvoicedAndAllFeaturesAreFinite() {
        val random = Random(123)
        val noise = ShortArray(1024) { random.nextInt(-12000, 12001).toShort() }
        val result = analyzer.analyze(noise)
        assertEquals(0f, result.pitch, 0f)
        assertTrue(result.level > 0f)
        checkNormalized(result)
    }

    @Test fun shortAndInvalidCountsAreSafeAndTailIsNotRead() {
        for (size in listOf(0, 1, 2, 16, 63, 128)) {
            checkNormalized(analyzer.analyze(ShortArray(size) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }))
        }
        assertEquals(SpeechAudioFeatures(), analyzer.analyze(wave(120.0, 0.3), -1))
        val samples = wave(120.0, 0.3)
        assertEquals(analyzer.analyze(samples), analyzer.analyze(samples, Int.MAX_VALUE))
        assertEquals(SpeechAudioFeatures(), analyzer.analyze(ShortArray(2048) { if (it < 1024) 0 else 20000 }, 1024))
    }

    private fun wave(hz: Double, amplitude: Double) = ShortArray(1024) { i ->
        (sin(2 * PI * hz * i / 16000) * amplitude * 32767).toInt().toShort()
    }

    private fun checkNormalized(features: SpeechAudioFeatures) {
        for (value in listOf(features.level, features.pitch, features.tone)) {
            assertTrue("finite normalized feature: $value", value.isFinite() && value in 0f..1f)
        }
    }
}

package com.aicustomer.voice

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AudioPreprocessorTest {

    @Test
    fun `crest factor of constant signal is ~0 dB`() {
        val preprocessor = AudioPreprocessor()
        // DC-like signal: peak ≈ RMS, crest factor ≈ 0
        val samples = ShortArray(512) { 1000 }
        val cf = preprocessor.computeCrestFactor(samples)
        assertTrue(cf < 1f, "Constant signal should have CF ~0 dB, got $cf")
    }

    @Test
    fun `crest factor of sine wave is ~3 dB`() {
        val preprocessor = AudioPreprocessor()
        // Pure sine: peak = amplitude, RMS = amplitude/sqrt(2)
        // CF = 20*log10(sqrt(2)) ≈ 3.01 dB
        val samples = ShortArray(512) { i ->
            (Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 16000.0).toInt().toShort()
        }
        val cf = preprocessor.computeCrestFactor(samples)
        assertTrue(cf in 2.5f..4.0f, "Sine wave should have CF ~3 dB, got $cf")
    }

    @Test
    fun `crest factor of impulse is high`() {
        val preprocessor = AudioPreprocessor()
        val samples = ShortArray(512) { 0 }
        samples[256] = Short.MAX_VALUE // max possible signal
        samples[257] = Short.MIN_VALUE
        val cf = preprocessor.computeCrestFactor(samples)
        assertTrue(cf > 22f, "Impulse should have very high CF, got $cf")
    }

    @Test
    fun `isImpulseNoise detects impulse`() {
        val preprocessor = AudioPreprocessor()
        preprocessor.crestFactorMaxDb = 22f

        val impulse = ShortArray(512) { 0 }
        impulse[256] = 30000
        assertTrue(preprocessor.isImpulseNoise(impulse), "Should detect high CF as impulse")

        val speechLike = ShortArray(512) { i ->
            val s = Math.sin(2.0 * Math.PI * 500.0 * i / 16000.0) +
                Math.sin(2.0 * Math.PI * 1200.0 * i / 16000.0) * 0.7 +
                Math.sin(2.0 * Math.PI * 2500.0 * i / 16000.0) * 0.5
            (s * 8000.0).toInt().toShort()
        }
        assertFalse(preprocessor.isImpulseNoise(speechLike), "Speech-like should not be impulse")
    }

    @Test
    fun `crest factor of empty array is 0`() {
        val preprocessor = AudioPreprocessor()
        val cf = preprocessor.computeCrestFactor(ShortArray(0))
        assertEquals(0f, cf)
    }

    @Test
    fun `crest factor custom max threshold`() {
        val preprocessor = AudioPreprocessor()
        preprocessor.crestFactorMaxDb = 10f

        val samples = ShortArray(512) { i ->
            (Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 16000.0).toInt().toShort()
        }
        val cf = preprocessor.computeCrestFactor(samples)
        assertTrue(preprocessor.isImpulseNoise(samples) == (cf > 10f),
            "isImpulseNoise should match CF > crestFactorMaxDb")
    }
}

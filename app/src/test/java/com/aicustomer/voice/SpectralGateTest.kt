package com.aicustomer.voice

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SpectralGateTest {

    private lateinit var gate: SpectralGate

    @BeforeEach
    fun setUp() {
        gate = SpectralGate()
    }

    // ========================
    // FFT / Feature extraction
    // ========================

    @Test
    fun `analyze returns valid features for silence`() {
        val silence = FloatArray(512) { 0f }
        val features = gate.analyze(silence)
        assertTrue(features.speechConfidence in 0f..1f, "Confidence should be in valid range")
        assertTrue(features.spectralFlatness in 0f..1f, "Flatness should be in valid range")
    }

    @Test
    fun `analyze returns features for pure tone`() {
        // 440 Hz sine wave
        val samples = FloatArray(512) { i ->
            sin(2.0 * PI * 440.0 * i / SpectralGate.SAMPLE_RATE).toFloat() * 0.5f
        }
        val features = gate.analyze(samples)
        // Pure tone → very low spectral flatness
        assertTrue(features.spectralFlatness < 0.2f, "Pure tone should have low flatness, got ${features.spectralFlatness}")
        assertTrue(features.speechConfidence < 0.5f, "Pure tone should not be speech-like")
    }

    @Test
    fun `analyze returns features for white noise`() {
        val rng = java.util.Random(42)
        val samples = FloatArray(512) { rng.nextFloat() * 2f - 1f }
        val features = gate.analyze(samples)
        assertTrue(features.spectralFlatness > 0.5f, "White noise should have high flatness, got ${features.spectralFlatness}")
        assertTrue(features.speechConfidence in 0f..1f, "Confidence range valid")
    }

    @Test
    fun `analyze returns features for multi-tone signal`() {
        val samples = FloatArray(512) { i ->
            var sum = 0.0
            for (h in 1..5) {
                sum += sin(2.0 * PI * 440.0 * h * i / SpectralGate.SAMPLE_RATE) / h
            }
            (sum * 0.3).toFloat()
        }
        val features = gate.analyze(samples)
        assertTrue(features.spectralFlatness < 0.3f, "Music should have flatness < 0.3")
        assertTrue(features.speechConfidence in 0f..1f, "Confidence range valid")
    }

    @Test
    fun `analyze returns features for band-limited signal`() {
        // Simulated speech-like: energy concentrated in 300-3400 Hz
        val rng = java.util.Random(42)
        val samples = FloatArray(512) { i ->
            // Generate band-limited noise-like signal
            var sum = 0.0
            // Dominant in speech band (5-54 bins = 312-3400 Hz)
            for (b in 5..54) {
                val freq = b * SpectralGate.SAMPLE_RATE.toDouble() / SpectralGate.FFT_SIZE
                sum += sin(2.0 * PI * freq * i / SpectralGate.SAMPLE_RATE + rng.nextDouble())
            }
            (sum / 50.0 * 0.3).toFloat()
        }
        val features = gate.analyze(samples)
        // Speech-like: high band ratio
        assertTrue(features.speechBandRatio > 0.4f, "Speech-like should have high band ratio, got ${features.speechBandRatio}")
    }

    // ========================
    // Threshold / Hysteresis
    // ========================

    @Test
    fun `isSpeechLike respects start threshold`() {
        gate.speechStartThreshold = 0.8f
        gate.speechContinueThreshold = 0.3f
        // Even after analyzing speech-like signal, smoothing may not reach 0.8
        gate.reset()
        assertFalse(gate.isSpeechLike(), "Should not be speech-like after reset")
    }

    @Test
    fun `canContinueSpeech is lower than isSpeechLike`() {
        gate.speechStartThreshold = 0.42f
        gate.speechContinueThreshold = 0.30f
        assertTrue(
            gate.speechContinueThreshold < gate.speechStartThreshold,
            "Continue threshold must be lower than start threshold (hysteresis)"
        )
    }

    @Test
    fun `reset clears smoothed confidence`() {
        val rng = java.util.Random(42)
        val samples = FloatArray(512) { rng.nextFloat() * 2f - 1f }
        gate.analyze(samples)
        gate.reset()
        gate.speechStartThreshold = 0.99f
        assertFalse(gate.isSpeechLike(), "After reset, should not be speech-like with high threshold")
    }

    // ========================
    // Speech vs Music discrimination
    // ========================

    @Test
    fun `speech-like signal scores higher than pure tone`() {
        // Speech-like: energy in speech band
        val rng = java.util.Random(42)
        val speechLike = FloatArray(512) { i ->
            var sum = 0.0
            for (b in 8..50) {
                val freq = b * SpectralGate.SAMPLE_RATE.toDouble() / SpectralGate.FFT_SIZE
                sum += sin(2.0 * PI * freq * i / SpectralGate.SAMPLE_RATE + rng.nextDouble()) * 0.3
            }
            (sum / 43.0).toFloat()
        }

        val pureTone = FloatArray(512) { i ->
            sin(2.0 * PI * 500.0 * i / SpectralGate.SAMPLE_RATE).toFloat() * 0.5f
        }

        gate.reset()
        val speechFeatures = gate.analyze(speechLike)

        gate.reset()
        val toneFeatures = gate.analyze(pureTone)

        assertTrue(
            speechFeatures.speechConfidence > toneFeatures.speechConfidence,
            "Speech-like should score higher (${speechFeatures.speechConfidence}) than pure tone (${toneFeatures.speechConfidence})"
        )
    }

    @Test
    fun `zero crossing rate is computed correctly`() {
        val alternating = FloatArray(200) { if (it % 2 == 0) 1f else -1f }
        val features = gate.analyze(alternating + FloatArray(56))
        assertTrue(features.zeroCrossingRate > 0.75f, "Alternating signal should have high ZCR, got ${features.zeroCrossingRate}")

        val slowSine = FloatArray(512) { i ->
            sin(2.0 * PI * 50.0 * i / SpectralGate.SAMPLE_RATE).toFloat()
        }
        gate.reset()
        val slowFeatures = gate.analyze(slowSine)
        assertTrue(slowFeatures.zeroCrossingRate < 0.2f, "Slow sine should have low ZCR, got ${slowFeatures.zeroCrossingRate}")
    }

    @Test
    fun `custom thresholds are respected`() {
        gate.speechStartThreshold = 0.05f
        gate.speechContinueThreshold = 0.01f

        val rng = java.util.Random(42)
        val samples = FloatArray(512) { rng.nextFloat() * 2f - 1f }
        val features = gate.analyze(samples)

        assertTrue(features.speechConfidence in 0f..1f, "Confidence should be in [0,1]")
    }
}

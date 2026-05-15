package com.aicustomer.voice

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioPreprocessor {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val DC_ALPHA = 0.995f

        private const val NOISE_FRAME_SAMPLES = 160
        private const val NOISE_ALPHA = 0.999f
        private const val NOISE_GATE_THRESHOLD_DB = 3.0f
        private const val NOISE_GATE_KNEE_DB = 6.0f
        private const val NOISE_GAIN_SMOOTH = 0.2f

        private const val AGC_FRAME_SAMPLES = 320
        const val AGC_TARGET_RMS = 0.3f
        private const val AGC_MAX_GAIN = 100.0f
        private const val AGC_MIN_GAIN = 0.5f
        private const val AGC_ATTACK = 0.02f
        private const val AGC_RELEASE = 0.002f
        private const val AGC_RMS_SMOOTH_UP = 0.2f
        private const val AGC_RMS_SMOOTH_DOWN = 0.05f
        private const val AGC_CLIP_THRESHOLD = 0.95f

        const val DEF_CREST_FACTOR_MAX_DB = 22f
    }

    private var dcPrevInput = 0f
    private var dcPrevOutput = 0f

    private var noiseFloor = 1e-6f
    private var noiseGateGain = 1.0f

    private var agcSmoothedRms = 0.01f
    private var agcAppRms = 0.01f
    private var agcSmoothedGain = 1.0f

    var crestFactorMaxDb: Float = DEF_CREST_FACTOR_MAX_DB

    fun reset() {
        dcPrevInput = 0f
        dcPrevOutput = 0f
        noiseFloor = 1e-6f
        noiseGateGain = 1.0f
        agcSmoothedRms = 0.01f
        agcAppRms = 0.01f
        agcSmoothedGain = 1.0f
    }

    fun process(input: FloatArray): FloatArray {
        val dcBlocked = applyDcBlocker(input)
        val gated = applyNoiseGate(dcBlocked)
        return applyAgc(gated)
    }

    fun processShortToFloat(samples: ShortArray): FloatArray {
        val dc = samples.map { it.toDouble() }.sum() / samples.size
        val result = FloatArray(samples.size) {
            ((samples[it] - dc) / 32768.0).toFloat().coerceIn(-1f, 1f)
        }
        return process(result)
    }

    /**
     * Compute crest factor for a frame of raw audio samples.
     * CFR = 20 * log10(peak / RMS). Returns value in dB.
     * Speech: 12-20dB, Music: 6-12dB, Impulse (gunshots): >25dB
     */
    fun computeCrestFactor(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var peak = 0f
        var sumSq = 0.0
        for (s in samples) {
            val abs = abs(s.toFloat())
            if (abs > peak) peak = abs
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sumSq / samples.size).toFloat()
        if (rms < 1f) return 0f
        val cf = 20f * (ln(peak / rms) / ln(10f))
        return cf.coerceIn(0f, 60f)
    }

    /**
     * Check if frame is an impulse sound (e.g. gunshot, door slam, keyboard click).
     * Impulse sounds have very high crest factor and should be rejected.
     */
    fun isImpulseNoise(samples: ShortArray): Boolean {
        val cf = computeCrestFactor(samples)
        return cf > crestFactorMaxDb
    }

    private fun abs(x: Float): Float = if (x < 0f) -x else x

    private fun applyDcBlocker(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        for (i in input.indices) {
            val sample = if (i == 0) {
                dcPrevOutput * DC_ALPHA + DC_ALPHA * (input[i] - dcPrevInput)
            } else {
                output[i - 1] * DC_ALPHA + DC_ALPHA * (input[i] - input[i - 1])
            }
            output[i] = sample.coerceIn(-1f, 1f)
        }
        dcPrevInput = input.last()
        dcPrevOutput = output.last()
        return output
    }

    private fun applyNoiseGate(input: FloatArray): FloatArray {
        val output = input.copyOf()

        var i = 0
        while (i + NOISE_FRAME_SAMPLES <= input.size) {
            val frameRms = computeRms(input, i, NOISE_FRAME_SAMPLES)
            val rmsClamped = max(frameRms, 1e-10f)

            noiseFloor = NOISE_ALPHA * noiseFloor + (1f - NOISE_ALPHA) * rmsClamped
            noiseFloor = min(noiseFloor, rmsClamped)

            val snrDb = if (noiseFloor > 1e-10f) {
                20f * ln(rmsClamped / noiseFloor) / ln(10f)
            } else {
                60f
            }

            val lowKnee = NOISE_GATE_THRESHOLD_DB - NOISE_GATE_KNEE_DB / 2f
            val highKnee = NOISE_GATE_THRESHOLD_DB + NOISE_GATE_KNEE_DB / 2f

            val targetGain = when {
                snrDb >= highKnee -> 1.0f
                snrDb <= lowKnee -> 0.0f
                else -> smoothstep(snrDb, lowKnee, highKnee)
            }

            noiseGateGain += NOISE_GAIN_SMOOTH * (targetGain - noiseGateGain)

            for (j in 0 until NOISE_FRAME_SAMPLES) {
                output[i + j] = input[i + j] * noiseGateGain
            }

            i += NOISE_FRAME_SAMPLES
        }

        return output
    }

    private fun applyAgc(input: FloatArray): FloatArray {
        val output = input.copyOf()

        var i = 0
        while (i + AGC_FRAME_SAMPLES <= input.size) {
            val frameRms = computeRms(input, i, AGC_FRAME_SAMPLES)
            val rmsClamped = max(frameRms, 1e-10f)

            val smooth = if (rmsClamped > agcSmoothedRms) AGC_RMS_SMOOTH_UP else AGC_RMS_SMOOTH_DOWN
            agcSmoothedRms += smooth * (rmsClamped - agcSmoothedRms)

            val appSmooth = if (rmsClamped > agcAppRms) AGC_ATTACK else AGC_RELEASE
            agcAppRms += appSmooth * (rmsClamped - agcAppRms)

            val targetGain = (AGC_TARGET_RMS / max(agcAppRms, 1e-10f))
                .coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)

            val gainSmooth = if (targetGain < agcSmoothedGain) AGC_RELEASE else AGC_ATTACK
            agcSmoothedGain += gainSmooth * (targetGain - agcSmoothedGain)

            val frameGain = agcSmoothedGain
            for (j in 0 until AGC_FRAME_SAMPLES) {
                val amplified = input[i + j] * frameGain
                output[i + j] = softClipper(amplified, AGC_CLIP_THRESHOLD)
            }

            i += AGC_FRAME_SAMPLES
        }

        return output
    }

    private fun computeRms(buffer: FloatArray, offset: Int, length: Int): Float {
        var sum = 0.0
        for (i in offset until min(offset + length, buffer.size)) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return sqrt(sum / length).toFloat()
    }

    private fun smoothstep(x: Float, edge0: Float, edge1: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun softClipper(sample: Float, threshold: Float): Float {
        val absSample = if (sample < 0) -sample else sample
        if (absSample <= threshold) return sample
        val sign = if (sample >= 0) 1f else -1f
        val excess = (absSample - threshold) / (1f - threshold)
        val tanhArg = 2f * excess
        val tanhVal = if (tanhArg > 10f) 1f else {
            val e2x = exp(2.0 * tanhArg)
            ((e2x - 1.0) / (e2x + 1.0)).toFloat()
        }
        return sign * (threshold + (1f - threshold) * tanhVal)
    }
}

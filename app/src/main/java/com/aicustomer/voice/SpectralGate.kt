package com.aicustomer.voice

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SpectralGate(
    var speechStartThreshold: Float = DEFAULT_SPEECH_START_THRESHOLD,
    var speechContinueThreshold: Float = DEFAULT_SPEECH_CONTINUE_THRESHOLD
) {

    companion object {
        const val FFT_SIZE = 256
        const val SAMPLE_RATE = 16000
        const val DEFAULT_SPEECH_START_THRESHOLD = 0.42f
        const val DEFAULT_SPEECH_CONTINUE_THRESHOLD = 0.30f

        private val HANN_WINDOW = FloatArray(FFT_SIZE) {
            (0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat()
        }

        private val TWIDDLE_RE: FloatArray
        private val TWIDDLE_IM: FloatArray
        private val BIT_REVERSE: IntArray

        init {
            val halfN = FFT_SIZE / 2
            TWIDDLE_RE = FloatArray(halfN)
            TWIDDLE_IM = FloatArray(halfN)
            for (k in 0 until halfN) {
                val angle = -2.0 * Math.PI * k / FFT_SIZE
                TWIDDLE_RE[k] = cos(angle).toFloat()
                TWIDDLE_IM[k] = sin(angle).toFloat()
            }

            BIT_REVERSE = IntArray(FFT_SIZE)
            var bits = 0
            var n = FFT_SIZE
            while (n > 1) { bits++; n = n shr 1 }
            for (i in 0 until FFT_SIZE) {
                var rev = 0
                var temp = i
                for (j in 0 until bits) {
                    rev = (rev shl 1) or (temp and 1)
                    temp = temp shr 1
                }
                BIT_REVERSE[i] = rev
            }
        }

        // Frequency bins for speech band (300-3400 Hz @ 16kHz)
        // bin = freq * FFT_SIZE / SAMPLE_RATE
        private const val SPEECH_BAND_LOW_BIN = 5    // 300 Hz ~= bin 4.8
        private const val SPEECH_BAND_HIGH_BIN = 54   // 3400 Hz ~= bin 54.4

        private const val EPSILON = 1e-10f
    }

    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val magnitude = FloatArray(FFT_SIZE / 2)

    // Smoothed feature history for stability
    private var smoothedConfidence = 0.5f
    private val CONFIDENCE_SMOOTH_UP = 0.15f
    private val CONFIDENCE_SMOOTH_DOWN = 0.05f

    data class SpectralFeatures(
        val speechConfidence: Float,
        val spectralFlatness: Float,
        val spectralCentroid: Float,
        val spectralRolloff: Float,
        val speechBandRatio: Float,
        val zeroCrossingRate: Float,
        val isSpeechLike: Boolean
    )

    fun analyze(rawAudio: FloatArray): SpectralFeatures {
        val windowed = applyWindow(rawAudio)
        fft(windowed)
        computeMagnitude()

        val sfm = spectralFlatness()
        val centroid = spectralCentroid()
        val rolloff = spectralRolloff()
        val bandRatio = speechBandRatio()
        val zcr = zeroCrossingRate(rawAudio)

        val speechConf = computeSpeechConfidence(sfm, centroid, rolloff, bandRatio, zcr)

        val smooth = if (speechConf > smoothedConfidence) CONFIDENCE_SMOOTH_UP
                     else CONFIDENCE_SMOOTH_DOWN
        smoothedConfidence += smooth * (speechConf - smoothedConfidence)

        return SpectralFeatures(
            speechConfidence = smoothedConfidence.coerceIn(0f, 1f),
            spectralFlatness = sfm,
            spectralCentroid = centroid,
            spectralRolloff = rolloff,
            speechBandRatio = bandRatio,
            zeroCrossingRate = zcr,
            isSpeechLike = smoothedConfidence >= speechStartThreshold
        )
    }

    fun isSpeechLike(): Boolean = smoothedConfidence >= speechStartThreshold

    fun canContinueSpeech(): Boolean = smoothedConfidence >= speechContinueThreshold

    fun reset() {
        smoothedConfidence = 0.5f
        real.fill(0f)
        imag.fill(0f)
        magnitude.fill(0f)
    }

    private fun applyWindow(input: FloatArray): FloatArray {
        val result = FloatArray(FFT_SIZE)
        // Use center 256 samples from input (if larger than FFT_SIZE)
        val offset = max(0, (input.size - FFT_SIZE) / 2)
        for (i in 0 until min(FFT_SIZE, input.size - offset)) {
            result[i] = input[offset + i] * HANN_WINDOW[i]
        }
        return result
    }

    // Radix-2 DIT complex FFT
    private fun fft(input: FloatArray) {
        // Bit-reversal permutation
        for (i in 0 until FFT_SIZE) {
            real[i] = input[BIT_REVERSE[i]]
            imag[i] = 0f
        }

        // FFT butterflies
        var step = 1
        while (step < FFT_SIZE) {
            val jump = step shl 1
            val twiddleStep = FFT_SIZE / jump
            for (group in 0 until step) {
                val twiddleIdx = group * twiddleStep
                val wRe = TWIDDLE_RE[twiddleIdx]
                val wIm = TWIDDLE_IM[twiddleIdx]
                for (pair in group until FFT_SIZE step jump) {
                    val match = pair + step
                    val tRe = wRe * real[match] - wIm * imag[match]
                    val tIm = wRe * imag[match] + wIm * real[match]
                    real[match] = real[pair] - tRe
                    imag[match] = imag[pair] - tIm
                    real[pair] = real[pair] + tRe
                    imag[pair] = imag[pair] + tIm
                }
            }
            step = jump
        }
    }

    private fun computeMagnitude() {
        for (i in 0 until FFT_SIZE / 2) {
            val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
            magnitude[i] = max(mag, EPSILON)
        }
    }

    private fun spectralFlatness(): Float {
        var geoMean = 0f
        var ariMean = 0f
        var count = 0
        for (i in 1 until magnitude.size) { // skip DC
            val mag = magnitude[i]
            geoMean += ln(max(mag, EPSILON))
            ariMean += mag
            count++
        }
        if (count == 0 || ariMean <= EPSILON) return 1.0f
        geoMean = (exp(geoMean / count)).toFloat()
        ariMean /= count
        val sfm = geoMean / max(ariMean, EPSILON)
        return sfm.coerceIn(0f, 1f)
    }

    private fun spectralCentroid(): Float {
        var weightedSum = 0f
        var totalMag = 0f
        for (i in 1 until magnitude.size) {
            val mag = magnitude[i]
            weightedSum += i.toFloat() * mag
            totalMag += mag
        }
        if (totalMag <= EPSILON) return 0f
        // Normalize to [0, 1]
        return (weightedSum / (totalMag * (magnitude.size - 1))).coerceIn(0f, 1f)
    }

    private fun spectralRolloff(): Float {
        var totalEnergy = 0f
        for (i in 0 until magnitude.size) {
            totalEnergy += magnitude[i] * magnitude[i]
        }
        val threshold = totalEnergy * 0.85f
        var cumulative = 0f
        for (i in 0 until magnitude.size) {
            cumulative += magnitude[i] * magnitude[i]
            if (cumulative >= threshold) {
                return i.toFloat() / (magnitude.size - 1)
            }
        }
        return 1f
    }

    private fun speechBandRatio(): Float {
        var speechEnergy = 0f
        var totalEnergy = 0f
        for (i in 0 until magnitude.size) {
            val energy = magnitude[i] * magnitude[i]
            totalEnergy += energy
            if (i in SPEECH_BAND_LOW_BIN..SPEECH_BAND_HIGH_BIN) {
                speechEnergy += energy
            }
        }
        if (totalEnergy <= EPSILON) return 0f
        return (speechEnergy / totalEnergy).coerceIn(0f, 1f)
    }

    private fun zeroCrossingRate(samples: FloatArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0f) != (samples[i - 1] >= 0f)) {
                crossings++
            }
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    private fun computeSpeechConfidence(
        sfm: Float,
        centroid: Float,
        rolloff: Float,
        bandRatio: Float,
        zcr: Float
    ): Float {
        // Speech characteristics:
        // - SFM: 0.15-0.55 (moderate flatness; music <0.15, noise >0.6)
        val sfmScore = when {
            sfm < 0.05f -> 0.1f          // very tonal (pure tones, music)
            sfm < 0.12f -> 0.3f          // tonal (music)
            sfm > 0.65f -> 0.15f         // noise-like
            sfm > 0.50f -> map01(sfm, 0.50f, 0.65f).let { 1f - it }
            else -> map01(sfm, 0.12f, 0.30f)
        }.coerceIn(0f, 1f) * 0.85f + 0.15f  // peak at SFM ~0.25

        // - Centroid: 0.06-0.25 (1000-4000Hz typical for speech)
        val centroidScore = when {
            centroid < 0.03f -> 0.2f     // too low frequency
            centroid > 0.40f -> 0.3f     // too high (hissy)
            centroid > 0.30f -> map01(centroid, 0.30f, 0.40f).let { 1f - it }
            else -> map01(centroid, 0.06f, 0.20f)
        }.coerceIn(0f, 1f)

        // - Speech band ratio: >0.45 typically speech
        val bandScore = map01(bandRatio, 0.35f, 0.55f).coerceIn(0f, 1f)

        // - ZCR: speech 0.08-0.25, music <0.08, noise >0.30
        val zcrScore = when {
            zcr < 0.04f -> 0.15f         // very low (pure tones)
            zcr > 0.35f -> 0.2f          // noise
            zcr > 0.28f -> map01(zcr, 0.28f, 0.35f).let { 1f - it }
            else -> map01(zcr, 0.06f, 0.18f)
        }.coerceIn(0f, 1f)

        // - Rolloff: speech 0.15-0.35
        val rolloffScore = when {
            rolloff > 0.50f -> map01(rolloff, 0.50f, 0.70f).let { 1f - it }
            rolloff < 0.05f -> 0.2f
            else -> map01(rolloff, 0.10f, 0.30f)
        }.coerceIn(0f, 1f)

        // Weighted fusion: band ratio is strongest discriminator
        val confidence = (
            bandScore * 0.35f +
            sfmScore * 0.25f +
            centroidScore * 0.20f +
            zcrScore * 0.10f +
            rolloffScore * 0.10f
        )

        return confidence.coerceIn(0f, 1f)
    }

    private fun map01(x: Float, low: Float, high: Float): Float {
        if (high <= low) return 0.5f
        return ((x - low) / (high - low)).coerceIn(0f, 1f)
    }
}

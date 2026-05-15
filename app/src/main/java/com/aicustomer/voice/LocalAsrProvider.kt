package com.aicustomer.voice

import android.content.Context
import android.util.Log
import com.aicustomer.engine.DeviceTier
import com.aicustomer.engine.SenseVoiceSttEngine

class LocalAsrProvider(private val context: Context) : AsrProvider {

    private var stt: SenseVoiceSttEngine? = null
    private var vad: VadDetector? = null
    private var spectralGate: SpectralGate? = null
    private var spectralEnabled = true
    private var speechActive = false
    private var ready = false

    private val audioBuffer = mutableListOf<Float>()
    private var energyHighFrames = 0
    private var speechFrameCount = 0
    private var energySilenceFrames = 0

    private var adaptiveEnergyThreshold = 5000.0
    private val noiseFloorHistory = ArrayDeque<Double>(50)
    private var currentSpeechConf = 0f

    private val audioPreprocessor = AudioPreprocessor()

    // Debug configurable thresholds
    private var energyMultiplier = 3.0
    private var minChineseRatio = DebugConfig.DEF_TEXT_MIN_CHINESE_RATIO

    companion object {
        private const val TAG = "LocalAsrProvider"
        private const val SPEECH_START_FRAMES = 3
        private const val MIN_SPEECH_FRAMES = 8
        private const val SILENCE_FRAMES_FOR_END = 8
        private const val CALIBRATION_FRAMES = 50

        private const val NOISE_FLOOR_CAP_FACTOR = 5.0
    }

    override fun isSpectralGateEnabled(): Boolean = spectralEnabled

    override fun getSpeechConfidence(): Float = currentSpeechConf

    override fun init(): Boolean {
        // Model reuse: if STT/VAD already loaded from a previous call, skip reload.
        // This makes subsequent calls instant (~1ms) instead of 2-3s.
        if (stt?.isLoaded() == true && vad != null) {
            reset()
            calibrateEnergyThreshold()
            ready = true
            Log.i(TAG, "init SKIP (models reused), adaptiveThreshold=$adaptiveEnergyThreshold")
            return true
        }

        stt = SenseVoiceSttEngine(context)
        vad = VadDetector(context)

        val tier = DeviceTier.detect(context)
        spectralEnabled = tier.supportsSpectralGate

        try {
            vad?.initBlocking()
            vad?.speechStartThreshold = VadDetector.DEF_SPEECH_START_THRESHOLD
            vad?.confirmFrames = VadDetector.DEF_CONFIRM_FRAMES
            stt?.loadBlocking()

            if (spectralEnabled) {
                spectralGate = SpectralGate(
                    speechStartThreshold = SpectralGate.DEFAULT_SPEECH_START_THRESHOLD,
                    speechContinueThreshold = SpectralGate.DEFAULT_SPEECH_CONTINUE_THRESHOLD
                )
            }
            calibrateEnergyThreshold()
            ready = true
            Log.i(TAG, "init OK (models loaded), spectral=$spectralEnabled, adaptiveThreshold=$adaptiveEnergyThreshold")
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            ready = false
            return false
        }
        return true
    }

    private fun calibrateEnergyThreshold() {
        noiseFloorHistory.clear()
        for (i in 0 until CALIBRATION_FRAMES) noiseFloorHistory.addLast(5000.0)
        adaptiveEnergyThreshold = 5000.0
    }

    private fun updateAdaptiveThreshold(energy: Double) {
        noiseFloorHistory.addLast(energy)
        if (noiseFloorHistory.size > CALIBRATION_FRAMES) noiseFloorHistory.removeFirst()
        val sorted = noiseFloorHistory.sorted()
        val noiseFloor = sorted[(sorted.size * 0.2).toInt().coerceIn(0, sorted.size - 1)]
        // Cap floor to prevent sustained music from raising threshold indefinitely
        val cappedFloor = noiseFloor.coerceAtMost(noiseFloorHistory.min() * NOISE_FLOOR_CAP_FACTOR)
        adaptiveEnergyThreshold = (cappedFloor * energyMultiplier).coerceAtLeast(1000.0)
    }

    override fun process(rawChunk: FloatArray, preprocessedChunk: FloatArray): AsrProvider.ProcessingResult {
        if (!ready) return AsrProvider.ProcessingResult.SILENCE

        val shortForVad = ShortArray(rawChunk.size) {
            (rawChunk[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // L1: Crest factor check — reject impulse noise (gunshots, door slams, keyboard clicks)
        if (!speechActive && audioPreprocessor.isImpulseNoise(shortForVad)) {
            currentSpeechConf = 0f
            return AsrProvider.ProcessingResult.SILENCE
        }

        val energy = shortForVad.map { it.toDouble() * it.toDouble() }.average()
        if (!speechActive) updateAdaptiveThreshold(energy)

        val isHighEnergy = energy > adaptiveEnergyThreshold

        // L2: Spectral gate — run for confidence reference only, not as a hard gate.
        // Hard gating is too aggressive for normal speech on mobile mics.
        // The L5 text quality check handles false triggers from music/noise.
        if (spectralEnabled && spectralGate != null) {
            val features = spectralGate!!.analyze(rawChunk)
            currentSpeechConf = features.speechConfidence
        }

        // Speech detection: energy-based primary, VAD as backup.
        // Accept SPEECH_CANDIDATE to avoid the 3-frame VAD confirmation delay.
        val isSpeechLike = isHighEnergy
        val vadState = vad?.process(shortForVad) ?: VadDetector.VadState.SILENCE
        val vadSpeechOk = vadState == VadDetector.VadState.SPEECH ||
                          vadState == VadDetector.VadState.SPEECH_CANDIDATE

        if (isSpeechLike || vadSpeechOk) {
            energyHighFrames++
            energySilenceFrames = 0

            if (speechActive) {
                speechFrameCount++
                rawChunk.forEach { audioBuffer.add(it) }
                return AsrProvider.ProcessingResult.speech(currentSpeechConf)
            }

            if (energyHighFrames >= SPEECH_START_FRAMES) {
                speechActive = true
                speechFrameCount = SPEECH_START_FRAMES
                audioBuffer.clear()
                rawChunk.forEach { audioBuffer.add(it) }
                Log.d(TAG, "speech start conf=$currentSpeechConf energy=$adaptiveEnergyThreshold")
                return AsrProvider.ProcessingResult.speech(currentSpeechConf)
            }

            return AsrProvider.ProcessingResult.SILENCE
        }

        energyHighFrames = 0

        if (!speechActive) {
            return AsrProvider.ProcessingResult.SILENCE
        }

        speechFrameCount++
        energySilenceFrames++

        rawChunk.forEach { audioBuffer.add(it) }

        val hasMinDuration = speechFrameCount >= MIN_SPEECH_FRAMES
        val isEndpoint = (hasMinDuration && vadState == VadDetector.VadState.SPEECH_END) ||
            (hasMinDuration && energySilenceFrames >= SILENCE_FRAMES_FOR_END)

        if (isEndpoint) {
            speechActive = false
            energyHighFrames = 0
            speechFrameCount = 0
            energySilenceFrames = 0
            Log.d(TAG, "speech end, buffered frames=${audioBuffer.size / 512}")
            return AsrProvider.ProcessingResult(false, true, currentSpeechConf)
        }

        return AsrProvider.ProcessingResult.speech(currentSpeechConf)
    }

    override fun finalize(): String {
        val samples = audioBuffer.toFloatArray()
        audioBuffer.clear()
        if (samples.isEmpty()) return ""

        val rawText = stt?.recognize(samples) ?: ""
        val corrected = HomophoneCorrector.correct(rawText)

        // L5: Post-STT text quality verification
        if (!isTextQualityPassing(corrected)) {
            Log.i(TAG, "finalize REJECTED by text quality check: raw='$rawText' corrected='$corrected'")
            return ""
        }

        Log.i(TAG, "finalize raw='$rawText' corrected='$corrected'")
        return corrected
    }

    /**
     * L5: Verify STT output is meaningful Chinese text, not noise artifacts.
     * Music/game sounds produce garbled output that must be rejected.
     */
    fun isTextQualityPassing(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length < 2) return false

        val chars = text.toCharArray()
        var chineseCount = 0
        var uniqueChars = mutableSetOf<Char>()
        var consecutiveRepeat = 0
        var maxConsecutive = 0
        var prev = '\u0000'

        for (c in chars) {
            if (c in '\u4e00'..'\u9fff' ||
                c in '\u3400'..'\u4dbf' ||
                c in '\uf900'..'\ufaff'
            ) {
                chineseCount++
            }
            uniqueChars.add(c)

            if (c == prev) {
                consecutiveRepeat++
                if (consecutiveRepeat > maxConsecutive) maxConsecutive = consecutiveRepeat
            } else {
                consecutiveRepeat = 1
            }
            prev = c
        }

        val totalChars = chars.size
        val chineseRatio = chineseCount.toFloat() / totalChars

        if (chineseRatio < minChineseRatio) return false
        if (totalChars >= 3 && uniqueChars.size < 2) return false
        if (maxConsecutive > 3) return false

        return true
    }

    override fun checkVad(audio: ShortArray): VadDetector.VadState {
        return vad?.process(audio) ?: VadDetector.VadState.SILENCE
    }

    override fun checkVadQuick(audio: ShortArray): VadDetector.VadState {
        return vad?.processQuick(audio) ?: VadDetector.VadState.SILENCE
    }

    override fun reset() {
        vad?.reset()
        spectralGate?.reset()
        speechActive = false
        audioBuffer.clear()
        energyHighFrames = 0
        speechFrameCount = 0
        energySilenceFrames = 0
        currentSpeechConf = 0f
    }

    override fun destroy() {
        // Soft destroy: clear internal state only. Keep STT/VAD models loaded
        // for instant re-init on next call.
        reset()
        spectralGate?.reset()
        ready = false
    }

    fun hardDestroy() {
        // Full destroy: unload all ONNX models. Call only on app termination.
        stt?.unload()
        vad?.destroy()
        spectralGate = null
        stt = null
        vad = null
        ready = false
        speechActive = false
        audioBuffer.clear()
        currentSpeechConf = 0f
    }

    override fun isReady(): Boolean = ready
}

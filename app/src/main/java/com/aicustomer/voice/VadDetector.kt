package com.aicustomer.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.File

class VadDetector(private val context: Context) {

    private var vad: Vad? = null
    private var initialized = false
    private var inSpeech = false
    private var silenceFrames = 0
    private var speechConfirmFrames = 0

    companion object {
        private const val MODEL_DIR = "vad-silero"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512
        private const val SILENCE_END_FRAMES = 10

        const val DEF_SPEECH_START_THRESHOLD = 0.70f
        const val DEF_SPEECH_CONTINUE_THRESHOLD = 0.50f
        const val DEF_CONFIRM_FRAMES = 3
    }

    var speechStartThreshold: Float = DEF_SPEECH_START_THRESHOLD
    var speechContinueThreshold: Float = DEF_SPEECH_CONTINUE_THRESHOLD
    var confirmFrames: Int = DEF_CONFIRM_FRAMES
    var minSilenceDurationSec: Float = 0.15f

    enum class VadState { SILENCE, SPEECH, SPEECH_END, SPEECH_CANDIDATE }

    fun initBlocking(): Boolean {
        if (initialized) return true

        val filesModelDir = File(context.filesDir, "models/$MODEL_DIR")
        val filesModelFile = File(filesModelDir, "silero_vad.onnx")

        val modelPath: String
        val useAssets: Boolean
        if (filesModelFile.exists()) {
            modelPath = filesModelFile.absolutePath
            useAssets = false
        } else {
            try {
                context.assets.open("models/$MODEL_DIR/silero_vad.onnx").close()
                modelPath = "models/$MODEL_DIR/silero_vad.onnx"
            } catch (_: Exception) {
                return false
            }
            useAssets = true
        }

        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = speechContinueThreshold,
                minSilenceDuration = minSilenceDurationSec,
                minSpeechDuration = 0.15f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 600f
            ),
            tenVadModelConfig = TenVadModelConfig(),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )

        vad = Vad(if (useAssets) context.assets else null, config)
        initialized = true
        return true
    }

    /**
     * Quick VAD check for barge-in — returns Sherpa VAD raw result immediately.
     * No 5-frame confirmation delay. False positives are acceptable since the
     * 5-layer ASR pipeline (L1-L5) will filter out non-speech afterwards.
     */
    fun processQuick(audioData: ShortArray): VadState {
        val v = vad ?: return VadState.SILENCE

        val floatSamples = FloatArray(audioData.size) { audioData[it] / 32768.0f }
        v.acceptWaveform(floatSamples)

        var hasSpeech = false
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            if (segment.samples.isNotEmpty()) hasSpeech = true
        }
        return if (hasSpeech) VadState.SPEECH else VadState.SILENCE
    }

    fun process(audioData: ShortArray): VadState {
        val v = vad ?: return VadState.SILENCE

        val floatSamples = FloatArray(audioData.size) { audioData[it] / 32768.0f }
        v.acceptWaveform(floatSamples)

        var hasSpeech = false
        var hasEndSegment = false

        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            if (segment.samples.isNotEmpty()) {
                hasSpeech = true
            } else {
                hasEndSegment = true
            }
        }

        if (hasSpeech) {
            if (!inSpeech) {
                // Speech candidate: require confirmFrames before entering speech state
                speechConfirmFrames++
                if (speechConfirmFrames >= confirmFrames) {
                    inSpeech = true
                    speechConfirmFrames = 0
                    silenceFrames = 0
                    return VadState.SPEECH
                }
                return VadState.SPEECH_CANDIDATE
            }
            inSpeech = true
            speechConfirmFrames = 0
            silenceFrames = 0
            return VadState.SPEECH
        }

        // No speech detected this frame
        speechConfirmFrames = 0

        if (hasEndSegment && inSpeech) {
            inSpeech = false
            silenceFrames = 0
            return VadState.SPEECH_END
        }

        if (inSpeech) {
            silenceFrames++
            if (silenceFrames >= SILENCE_END_FRAMES) {
                inSpeech = false
                silenceFrames = 0
                return VadState.SPEECH_END
            }
            return VadState.SPEECH
        }

        return VadState.SILENCE
    }

    fun reset() {
        vad?.reset()
        inSpeech = false
        silenceFrames = 0
        speechConfirmFrames = 0
    }

    fun destroy() {
        vad?.release()
        vad = null
        initialized = false
        inSpeech = false
        silenceFrames = 0
        speechConfirmFrames = 0
    }
}

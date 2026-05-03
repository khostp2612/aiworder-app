package com.aicustomer.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VadDetector(private val context: Context) {

    private var vad: Vad? = null
    private var isSpeaking = false
    private var silenceDurationMs: Long = 0
    private var initialized = false

    companion object {
        private const val MODEL_DIR = "vad-silero"
        private const val SILENCE_THRESHOLD_MS = 800L
        private const val SPEECH_THRESHOLD = 0.5f
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 30
    }

    fun isInitialized(): Boolean = initialized

    fun initBlocking() {
        if (initialized) return

        val modelDir = File(context.filesDir, "models/$MODEL_DIR")
        if (!File(modelDir, "silero_vad.onnx").exists()) {
            throw IllegalStateException("VAD model not found")
        }

        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "${modelDir.absolutePath}/silero_vad.onnx",
                threshold = SPEECH_THRESHOLD,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 600f
            ),
            tenVadModelConfig = TenVadModelConfig(),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )

        vad = Vad(context.assets, config)
        initialized = true
    }

    fun process(audioData: ShortArray): VadState {
        val v = vad ?: return VadState.SILENCE

        val floatSamples = FloatArray(audioData.size) { audioData[it] / 32768.0f }
        v.acceptWaveform(floatSamples)

        // 1.12.40 API: empty() not isEmpty(), front/pop
        while (!v.empty()) {
            val speech = v.front()
            v.pop()

            if (speech.samples.isNotEmpty()) {
                isSpeaking = true
                silenceDurationMs = 0
                return VadState.SPEECH
            }
        }

        val energy = computeEnergy(audioData)
        val isSpeechDetected = energy > 500

        if (isSpeechDetected) {
            isSpeaking = true
            silenceDurationMs = 0
            return VadState.SPEECH
        } else if (isSpeaking) {
            silenceDurationMs += WINDOW_SIZE
            if (silenceDurationMs >= SILENCE_THRESHOLD_MS) {
                isSpeaking = false
                silenceDurationMs = 0
                return VadState.SPEECH_END
            }
            return VadState.SPEECH
        }

        return VadState.SILENCE
    }

    private fun computeEnergy(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) {
            sum += s.toDouble() * s.toDouble()
        }
        return sum / samples.size
    }

    fun reset() {
        vad?.reset()
        isSpeaking = false
        silenceDurationMs = 0
    }

    fun destroy() {
        vad?.release()
        vad = null
        isSpeaking = false
        initialized = false
    }

    fun isSpeaking(): Boolean = isSpeaking

    enum class VadState {
        SILENCE, SPEECH, SPEECH_END
    }
}

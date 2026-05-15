package com.aicustomer.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

class SenseVoiceSttEngine(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var loaded = false

    companion object {
        private const val TAG = "SenseVoiceSttEngine"
        private const val MODEL_DIR = "sense-voice"
        private const val SAMPLE_RATE = 16000
    }

    fun loadBlocking() {
        if (loaded) return

        val filesModelDir = File(context.filesDir, "models/$MODEL_DIR")
        val useFilesDir = filesModelDir.exists() &&
            File(filesModelDir, "model.int8.onnx").exists()

        val modelPath: String
        val tokensPath: String
        val hotwordsPath: String

        if (useFilesDir) {
            val base = filesModelDir.absolutePath
            modelPath = "$base/model.int8.onnx"
            tokensPath = "$base/tokens.txt"
            hotwordsPath = "$base/hotwords.txt"
        } else {
            val assetBase = "models/$MODEL_DIR"
            modelPath = "$assetBase/model.int8.onnx"
            tokensPath = "$assetBase/tokens.txt"
            hotwordsPath = "$assetBase/hotwords.txt"
        }

        val hasHotwords = if (useFilesDir) {
            File(hotwordsPath).exists()
        } else {
            try { context.assets.open(hotwordsPath).close(); true } catch (_: Exception) { false }
        }

        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelPath,
                    language = "zh",
                    useInverseTextNormalization = true
                ),
                tokens = tokensPath,
                numThreads = 4,
                provider = "cpu"
            ),
            hotwordsFile = if (hasHotwords) hotwordsPath else "",
            hotwordsScore = 1.5f
        )

        try {
            val rec = OfflineRecognizer(
                if (useFilesDir) null else context.assets, config
            )

            val testStream = rec.createStream()
            testStream.release()

            recognizer = rec
            loaded = true
            Log.i(TAG, "SenseVoice loaded OK (numThreads=4, ITN=enabled, useFilesDir=$useFilesDir)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SenseVoice: ${e.javaClass.simpleName} ${e.message}", e)
            try { recognizer?.release() } catch (_: Exception) {}
            recognizer = null
            loaded = false
        }
    }

    fun recognize(samples: FloatArray): String {
        val rec = recognizer
        if (rec == null || !loaded || samples.isEmpty()) return ""

        try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val result = rec.getResult(stream).text
                Log.i(TAG, "recognized: '$result'")
                return result
            } finally {
                try { stream.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed: ${e.javaClass.simpleName} ${e.message}", e)
            loaded = false
            try { rec.release() } catch (_: Exception) {}
            recognizer = null
            return ""
        }
    }

    fun isLoaded(): Boolean = loaded

    fun unload() {
        try {
            recognizer?.release()
        } catch (_: Exception) {}
        recognizer = null
        loaded = false
    }
}

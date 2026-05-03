package com.aicustomer.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SttEngine(private val context: Context) {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var isLoaded = false

    companion object {
        private const val MODEL_DIR = "stt-zipformer-zh"
        private const val SAMPLE_RATE = 16000
    }

    fun loadBlocking() {
        if (isLoaded) return

        val modelDir = java.io.File(context.filesDir, "models/$MODEL_DIR")
        if (!modelDir.exists()) {
            throw IllegalStateException("STT模型文件不存在: ${modelDir.absolutePath}")
        }

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "${modelDir.absolutePath}/encoder-epoch-99-avg-1.onnx",
                    decoder = "${modelDir.absolutePath}/decoder-epoch-99-avg-1.onnx",
                    joiner = "${modelDir.absolutePath}/joiner-epoch-99-avg-1.onnx"
                ),
                tokens = "${modelDir.absolutePath}/tokens.txt",
                numThreads = 2,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )

        recognizer = OnlineRecognizer(context.assets, config)
        stream = recognizer?.createStream()
        isLoaded = true
    }

    fun acceptWaveform(floatSamples: FloatArray) {
        val s = stream ?: return
        s.acceptWaveform(floatSamples, SAMPLE_RATE)
    }

    fun getPartialResult(): String {
        val rec = recognizer ?: return ""
        val s = stream ?: return ""

        if (rec.isReady(s)) {
            rec.decode(s)
        }
        return rec.getResult(s).text
    }

    fun isEndpoint(): Boolean {
        val rec = recognizer ?: return false
        val s = stream ?: return false
        return rec.isEndpoint(s)
    }

    fun getFinalResultAndReset(): String {
        val rec = recognizer ?: return ""
        val s = stream ?: return ""

        val text = rec.getResult(s).text
        rec.reset(s)
        return text
    }

    fun getResult(): String {
        val rec = recognizer ?: return ""
        val s = stream ?: return ""
        return rec.getResult(s).text
    }

    fun recognize(audioData: ShortArray, isEnd: Boolean = false): String {
        val rec = recognizer ?: return ""
        val s = stream ?: return ""

        val floatSamples = FloatArray(audioData.size) { audioData[it] / 32768.0f }
        s.acceptWaveform(floatSamples, SAMPLE_RATE)

        return if (rec.isReady(s)) {
            rec.decode(s)
            rec.getResult(s).text
        } else {
            ""
        }
    }

    fun getFinalResult(): String {
        val rec = recognizer ?: return ""
        val s = stream ?: return ""

        if (rec.isEndpoint(s)) {
            val text = rec.getResult(s).text
            rec.reset(s)
            return text
        }
        return rec.getResult(s).text
    }

    fun reset() {
        stream?.let { recognizer?.reset(it) }
    }

    fun unload() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
        isLoaded = false
    }

    fun isLoaded(): Boolean = isLoaded
}

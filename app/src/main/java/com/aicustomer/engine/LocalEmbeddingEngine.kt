package com.aicustomer.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * 本地 ONNX 嵌入模型引擎
 *
 * 使用 GTE-small-zh 或类似小型中文嵌入模型进行本地向量嵌入，
 * 避免云端依赖，使长期记忆的向量检索在离线状态下也具备语义能力。
 *
 * 模型要求：
 * - model.onnx: ONNX 格式嵌入模型
 * - vocab.txt: BERT 分词器词表
 * - 放置在 filesDir/models/embed-gte-small-zh/ 目录
 */
class LocalEmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalEmbeddingEngine"
        private const val MODEL_DIR = "embed-gte-small-zh"
        private const val DIM = 384
        private const val MAX_SEQ_LEN = 512
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loaded = false

    /**
     * 加载 ONNX 嵌入模型
     */
    suspend fun load(): Result<Unit> = runCatching {
        val modelDir = File(context.filesDir, "models/$MODEL_DIR")
        if (!modelDir.exists()) {
            Log.w(TAG, "Embedding model not found at ${modelDir.absolutePath}")
            throw IllegalStateException("嵌入模型目录不存在: $MODEL_DIR")
        }

        val modelFile = File(modelDir, "model.onnx")
        if (!modelFile.exists()) {
            throw IllegalStateException("模型文件不存在: model.onnx")
        }

        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op_num_threads", "2")
        }
        session = env?.createSession(modelFile.absolutePath, opts)
        loaded = true
        Log.i(TAG, "Local embedding model loaded successfully")
    }

    /**
     * 对文本进行向量嵌入
     * @return 384维 FloatArray，如果未加载则返回空数组
     */
    suspend fun embed(text: String): FloatArray {
        if (!loaded || session == null || env == null) return FloatArray(DIM)

        return try {
            val tokens = tokenize(text)
            val seqLen = tokens.size.toLong()
            val shape = longArrayOf(1, seqLen)

            // 使用 LongBuffer 创建 ONNX 输入张量
            val inputIdsBuf = LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() })
            val attentionMaskBuf = LongBuffer.wrap(LongArray(tokens.size) { 1L })
            val tokenTypeIdsBuf = LongBuffer.wrap(LongArray(tokens.size) { 0L })

            val inputMap = mapOf(
                "input_ids" to OnnxTensor.createTensor(env, inputIdsBuf, shape),
                "attention_mask" to OnnxTensor.createTensor(env, attentionMaskBuf, shape),
                "token_type_ids" to OnnxTensor.createTensor(env, tokenTypeIdsBuf, shape)
            )

            val output = session?.run(inputMap)
            val result = output?.get(0)?.value as? Array<FloatArray>
            if (result != null && result.isNotEmpty()) {
                normalize(meanPool(result, LongArray(tokens.size) { 1L }))
            } else {
                FloatArray(DIM)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed", e)
            FloatArray(DIM)
        }
    }

    /**
     * 简单字符级分词
     * 注意：这是简化版，生产环境应使用 BERT WordPiece tokenizer
     */
    private fun tokenize(text: String, maxLen: Int = MAX_SEQ_LEN): IntArray {
        // [CLS]=101, [SEP]=102
        val tokens = mutableListOf(101)
        for (char in text) {
            val code = char.code
            tokens.add(if (code in 0x4E00..0x9FFF) code else (code % 30000 + 1000))
            if (tokens.size >= maxLen - 1) break
        }
        tokens.add(102)
        return tokens.toIntArray()
    }

    private fun meanPool(tokenEmbeddings: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = if (tokenEmbeddings.isNotEmpty()) tokenEmbeddings[0].size else DIM
        val result = FloatArray(dim)
        var count = 0
        for (i in mask.indices) {
            if (mask[i] == 1L && i < tokenEmbeddings.size) {
                for (j in 0 until minOf(dim, tokenEmbeddings[i].size)) {
                    result[j] += tokenEmbeddings[i][j]
                }
                count++
            }
        }
        if (count > 0) {
            val countF = count.toFloat()
            for (j in 0 until dim) result[j] /= countF
        }
        return result
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum)
        return if (n < 1e-8f) v else FloatArray(v.size) { v[it] / n }
    }

    fun isLoaded(): Boolean = loaded
    fun getDimension(): Int = DIM

    fun unload() {
        session?.close()
        env?.close()
        session = null
        env = null
        loaded = false
    }
}

package com.aicustomer.engine

import android.content.Context

class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val EMBEDDING_DIM = 384
    }

    private var loaded = false
    private var localEngine: LocalEmbeddingEngine? = null

    suspend fun load(): Result<Unit> {
        try {
            localEngine = LocalEmbeddingEngine(context)
            val localResult = localEngine!!.load()
            if (!localResult.isSuccess || !localEngine!!.isLoaded()) {
                localEngine = null
            }
        } catch (e: Exception) {
            localEngine = null
        }

        loaded = true
        return Result.success(Unit)
    }

    suspend fun embed(text: String): FloatArray {
        if (localEngine?.isLoaded() == true) return localEngine!!.embed(text)
        return hashBasedEmbedding(text)
    }

    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (localEngine?.isLoaded() == true) return texts.map { localEngine!!.embed(it) }
        return texts.map { hashBasedEmbedding(it) }
    }

    private fun hashBasedEmbedding(text: String): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)
        var h0 = text.hashCode()
        var h1 = (text + "salt1").hashCode()
        var h2 = (text + "salt2").hashCode()
        for (i in result.indices) {
            h0 = h0 * 31 + i + h1
            h1 = h1 * 17 + i + h2
            h2 = h2 * 13 + i
            val mixed = (h0 xor (h1 shl 8) xor (h2 shl 16)).toInt()
            result[i] = ((mixed and 0xFFFF) / 65535f - 0.5f) * 0.2f
        }
        var norm = 0f
        for (v in result) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-8f) {
            for (i in result.indices) result[i] /= norm
        }
        return result
    }

    fun getDimension(): Int = EMBEDDING_DIM
    fun isLoaded(): Boolean = loaded
    fun isLocalEngineAvailable(): Boolean = localEngine?.isLoaded() == true
}

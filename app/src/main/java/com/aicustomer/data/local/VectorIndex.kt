package com.aicustomer.data.local

import kotlin.math.sqrt

/**
 * 轻量向量索引 - 预归一化余弦相似度搜索
 * 插入时预归一化，搜索时只需点积，避免每次重复计算 norm
 */
class VectorIndex {

    private val entries = mutableListOf<VectorEntry>()

    data class VectorEntry(
        val id: Long,
        val embedding: FloatArray,
        val normalizedEmbedding: FloatArray,
        val content: String
    )

    data class SearchResult(
        val id: Long,
        val content: String,
        val score: Float
    )

    @Synchronized
    fun add(id: Long, embedding: FloatArray, content: String) {
        entries.removeIf { it.id == id }
        val normalized = normalize(embedding)
        entries.add(VectorEntry(id, embedding, normalized, content))
    }

    @Synchronized
    fun remove(id: Long) {
        entries.removeIf { it.id == id }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    /**
     * 余弦相似度搜索，返回Top-K结果
     * 预归一化后余弦相似度 = 点积，无需每次算 norm
     */
    @Synchronized
    fun search(query: FloatArray, topK: Int = 5): List<SearchResult> {
        if (entries.isEmpty()) return emptyList()

        val queryNorm = normalize(query)
        val queryNormValue = norm(query)
        if (queryNormValue < 1e-8f) return emptyList()

        return entries.map { entry ->
            // 归一化后余弦相似度 = 点积
            var dot = 0f
            for (i in queryNorm.indices) {
                dot += queryNorm[i] * entry.normalizedEmbedding[i]
            }
            SearchResult(entry.id, entry.content, dot)
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        return if (n < 1e-8f) FloatArray(v.size)
        else FloatArray(v.size) { v[it] / n }
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (value in v) {
            sum += value * value
        }
        return sqrt(sum)
    }
}

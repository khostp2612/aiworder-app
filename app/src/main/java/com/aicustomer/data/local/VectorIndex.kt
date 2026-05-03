package com.aicustomer.data.local

import kotlin.math.sqrt

/**
 * 轻量向量索引 - 暴力余弦相似度搜索
 * 1000条以内记忆足够快，无需HNSW等复杂索引
 */
class VectorIndex {

    private val entries = mutableListOf<VectorEntry>()

    data class VectorEntry(
        val id: Long,
        val embedding: FloatArray,
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
        entries.add(VectorEntry(id, embedding, content))
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
     */
    @Synchronized
    fun search(query: FloatArray, topK: Int = 5): List<SearchResult> {
        if (entries.isEmpty()) return emptyList()

        val queryNorm = norm(query)
        if (queryNorm < 1e-8f) return emptyList()

        return entries.map { entry ->
            val score = cosineSimilarity(query, queryNorm, entry.embedding)
            SearchResult(entry.id, entry.content, score)
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, aNorm: Float, b: FloatArray): Float {
        if (a.size != b.size) return 0f  // dimension mismatch protection
        val bNorm = norm(b)
        if (bNorm < 1e-8f) return 0f
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot / (aNorm * bNorm)
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (value in v) {
            sum += value * value
        }
        return sqrt(sum)
    }
}

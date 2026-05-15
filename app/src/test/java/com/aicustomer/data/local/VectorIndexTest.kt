package com.aicustomer.data.local

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class VectorIndexTest {
    private lateinit var index: VectorIndex

    @BeforeEach
    fun setup() { index = VectorIndex() }

    @Test
    fun `search returns empty when no entries`() {
        val query = FloatArray(384) { it * 0.01f }
        assertTrue(index.search(query).isEmpty())
    }

    @Test
    fun `search finds exact match`() {
        val vec = FloatArray(384) { if (it < 10) 1f else 0f }
        index.add(1, vec, "test content")
        val results = index.search(vec, topK = 1)
        assertEquals(1, results.size)
        assertEquals(1L, results[0].id)
        assertTrue(results[0].score > 0.99f, "Score should be near 1.0 for exact match, got ${results[0].score}")
    }

    @Test
    fun `search returns top-K by similarity`() {
        val base = FloatArray(384) { 1f }
        val similar = FloatArray(384) { 0.9f }
        val different = FloatArray(384) { if (it % 2 == 0) 1f else -1f }

        index.add(1, similar, "similar")
        index.add(2, different, "different")
        index.add(3, base, "base")

        val results = index.search(base, topK = 2)
        assertEquals(2, results.size)
        // base 自身最相似（score=1.0），similar 第二
        assertEquals(3L, results[0].id)
        assertEquals(1L, results[1].id)
    }

    @Test
    fun `remove eliminates entry`() {
        val vec = FloatArray(384) { 1f }
        index.add(1, vec, "to remove")
        assertEquals(1, index.size())
        index.remove(1)
        assertEquals(0, index.size())
    }

    @Test
    fun `add with same id replaces entry`() {
        val vec1 = FloatArray(384) { 1f }
        val vec2 = FloatArray(384) { -1f }
        index.add(1, vec1, "first")
        index.add(1, vec2, "second")
        assertEquals(1, index.size())
        // 应该是 vec2（与 vec2 相似度最高）
        val results = index.search(vec2, topK = 1)
        assertEquals(1L, results[0].id)
        assertTrue(results[0].score > 0.99f)
    }

    @Test
    fun `clear removes all entries`() {
        for (i in 1..5) {
            index.add(i.toLong(), FloatArray(384) { i * 0.1f }, "item $i")
        }
        assertEquals(5, index.size())
        index.clear()
        assertEquals(0, index.size())
    }

    @Test
    fun `zero vector query returns empty`() {
        index.add(1, FloatArray(384) { 1f }, "entry")
        val results = index.search(FloatArray(384), topK = 5)
        assertTrue(results.isEmpty(), "Zero norm query should return empty")
    }

    @Test
    fun `dimension mismatch handled gracefully`() {
        val vec384 = FloatArray(384) { 1f }
        val vec128 = FloatArray(128) { 1f }
        index.add(1, vec384, "384-dim")
        // 不同维度的查询不应崩溃
        val results = index.search(vec128, topK = 1)
        // 应该返回结果（分数可能为0）
        assertTrue(results.isEmpty() || results[0].score == 0f,
            "Dimension mismatch should return empty or zero-score results")
    }

    @Test
    fun `pre-normalized search is consistent with cosine similarity`() {
        val a = FloatArray(8) { floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)[it] }
        val b = FloatArray(8) { floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)[it] }

        // 手动计算余弦相似度
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val expected = dot / (sqrt(normA) * sqrt(normB))

        // 使用小维度 VectorIndex
        val smallIndex = VectorIndex()
        smallIndex.add(1, a, "a")
        val results = smallIndex.search(b, topK = 1)
        assertTrue(results.isNotEmpty())
        // 允许浮点误差
        assertEquals(expected, results[0].score, 0.001f,
            "Pre-normalized search should match cosine similarity")
    }
}

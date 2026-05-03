package com.aicustomer.data.local

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VectorIndexTest {

    private lateinit var index: VectorIndex

    @Before
    fun setUp() {
        index = VectorIndex()
    }

    @Test
    fun `add and search returns correct results`() {
        val v1 = floatArrayOf(1f, 0f, 0f)
        val v2 = floatArrayOf(0f, 1f, 0f)
        val v3 = floatArrayOf(1f, 1f, 0f)

        index.add(1, v1, "content 1")
        index.add(2, v2, "content 2")
        index.add(3, v3, "content 3")

        val results = index.search(floatArrayOf(1f, 0f, 0f), topK = 2)
        assertEquals(2, results.size)
        assertEquals(1L, results[0].id)
        assertEquals("content 1", results[0].content)
    }

    @Test
    fun `search respects topK limit`() {
        for (i in 1..10) {
            index.add(i.toLong(), floatArrayOf(i.toFloat(), 0f), "content $i")
        }

        val results = index.search(floatArrayOf(1f, 0f), topK = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `search on empty index returns empty`() {
        val results = index.search(floatArrayOf(1f, 0f), topK = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search with zero query norm returns empty`() {
        index.add(1, floatArrayOf(1f, 0f), "content")
        val results = index.search(floatArrayOf(0f, 0f, 0f), topK = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `remove removes entry`() {
        index.add(1, floatArrayOf(1f, 0f), "content 1")
        index.add(2, floatArrayOf(0f, 1f), "content 2")

        index.remove(1)
        assertEquals(1, index.size())

        val results = index.search(floatArrayOf(1f, 0f), topK = 5)
        assertEquals(1, results.size)
        assertEquals(2L, results[0].id)
    }

    @Test
    fun `add with same id overwrites`() {
        index.add(1, floatArrayOf(1f, 0f), "original")
        index.add(1, floatArrayOf(0f, 1f), "updated")

        assertEquals(1, index.size())

        val results = index.search(floatArrayOf(0f, 1f), topK = 1)
        assertEquals("updated", results[0].content)
    }

    @Test
    fun `clear removes all entries`() {
        index.add(1, floatArrayOf(1f, 0f), "content 1")
        index.add(2, floatArrayOf(0f, 1f), "content 2")

        index.clear()
        assertEquals(0, index.size())
        assertTrue(index.search(floatArrayOf(1f, 0f)).isEmpty())
    }

    @Test
    fun `cosine similarity identical vectors returns 1`() {
        val v = floatArrayOf(3f, 4f)
        index.add(1, v, "test")

        val results = index.search(v, topK = 1)
        assertEquals(1f, results[0].score, 0.001f)
    }

    @Test
    fun `cosine similarity orthogonal vectors returns near 0`() {
        index.add(1, floatArrayOf(1f, 0f), "test")

        val results = index.search(floatArrayOf(0f, 1f), topK = 1)
        assertEquals(0f, results[0].score, 0.001f)
    }

    @Test
    fun `results sorted by descending score`() {
        index.add(1, floatArrayOf(1f, 0f, 0f), "best match")
        index.add(2, floatArrayOf(0f, 1f, 0f), "medium match")
        index.add(3, floatArrayOf(0f, 0f, 1f), "worst match")

        val results = index.search(floatArrayOf(1f, 0.5f, 0f), topK = 3)
        assertEquals("best match", results[0].content)
        assertTrue(results[0].score >= results[1].score)
        assertTrue(results[1].score >= results[2].score)
    }

    @Test
    fun `remove nonexistent id does not crash`() {
        index.add(1, floatArrayOf(1f, 0f), "content")
        index.remove(999)
        assertEquals(1, index.size())
    }
}

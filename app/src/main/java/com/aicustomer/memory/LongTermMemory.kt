package com.aicustomer.memory

import com.aicustomer.data.local.MemoryDao
import com.aicustomer.data.local.VectorIndex
import com.aicustomer.data.model.Memory
import com.aicustomer.data.model.Memory.Companion.toByteArray
import com.aicustomer.engine.EmbeddingEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LongTermMemory(
    private val memoryDao: MemoryDao,
    private val embeddingEngine: EmbeddingEngine,
    private val vectorIndex: VectorIndex
) {
    private val storeMutex = Mutex()

    companion object {
        private const val IMPORTANCE_THRESHOLD = 6.0f
        private const val MAX_ENTRIES = 1000
        private const val DECAY_DAYS = 30
        private const val RELEVANCE_THRESHOLD = 0.4f
        const val DOC_CHUNK_SIZE = 400
        const val TYPE_KNOWLEDGE = "knowledge"
    }

    suspend fun store(content: String, source: String, importanceScore: Float, tags: String = "") {
        storeMutex.withLock {
            doStore(content, source, importanceScore, tags)
        }
    }

    private suspend fun doStore(content: String, source: String, importanceScore: Float, tags: String) {
        if (importanceScore < IMPORTANCE_THRESHOLD) return

        val embedding = if (embeddingEngine.isLoaded()) {
            embeddingEngine.embed(content)
        } else null

        val memory = Memory(
            type = "long_term",
            content = content,
            source = source,
            importanceScore = importanceScore,
            embedding = embedding?.toByteArray(),
            tags = tags
        )
        val id = memoryDao.insert(memory)
        if (embedding != null) {
            vectorIndex.add(id, embedding, content)
        }
        evictIfNeeded()
    }

    suspend fun search(query: String, topK: Int = 5): List<Memory> {
        if (!embeddingEngine.isLoaded()) {
            return memoryDao.getByMinImportance(IMPORTANCE_THRESHOLD).take(topK)
        }
        val queryEmbedding = embeddingEngine.embed(query)
        val results = vectorIndex.search(queryEmbedding, topK)
            .filter { it.score >= RELEVANCE_THRESHOLD }
        if (results.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val resultIds = results.map { it.id }
        val dbMemories = memoryDao.getByIds(resultIds)
        val dbMemoryMap = dbMemories.associateBy { it.id }
        for (id in resultIds) {
            memoryDao.touchAccess(id, now)
        }
        return results.mapNotNull { dbMemoryMap[it.id] }
    }

    suspend fun formatForPrompt(query: String, topK: Int = 5): String {
        val memories = search(query, topK)
        if (memories.isEmpty()) return ""
        return memories.joinToString("\n") { mem ->
            "\u2022 ${mem.content}（重要性：${"%.1f".format(mem.importanceScore)}）"
        }.let { "【关于用户的长期记忆】\n$it" }
    }

    suspend fun count(): Int = memoryDao.countByType("long_term")

    suspend fun storeDocument(title: String, content: String) {
        val chunks = splitDocument(content)
        for ((i, chunk) in chunks.withIndex()) {
            val label = if (chunks.size == 1) title else "$title (第${i + 1}部分)"
            val embedding = if (embeddingEngine.isLoaded()) {
                embeddingEngine.embed(chunk)
            } else null
            val memory = Memory(
                type = TYPE_KNOWLEDGE,
                content = chunk,
                source = label,
                importanceScore = 9f,
                embedding = embedding?.toByteArray(),
                tags = "document"
            )
            val id = memoryDao.insert(memory)
            if (embedding != null) {
                vectorIndex.add(id, embedding, chunk)
            }
        }
    }

    suspend fun searchDocuments(query: String, topK: Int = 3): List<Memory> {
        if (!embeddingEngine.isLoaded()) return emptyList()
        val queryEmbedding = embeddingEngine.embed(query)
        val results = vectorIndex.search(queryEmbedding, topK)
            .filter { it.score >= RELEVANCE_THRESHOLD }
        if (results.isEmpty()) return emptyList()
        val resultIds = results.map { it.id }
        val all = memoryDao.getByIds(resultIds)
        return all.filter { it.type == TYPE_KNOWLEDGE }
    }

    suspend fun formatDocuments(query: String, topK: Int = 3): String {
        val docs = searchDocuments(query, topK)
        if (docs.isEmpty()) return ""
        return docs.joinToString("\n\n") { "【${it.source}】\n${it.content}" }
            .let { "【参考文档知识】\n$it" }
    }

    private fun splitDocument(content: String): List<String> {
        val paragraphs = content.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.length <= DOC_CHUNK_SIZE) {
                chunks.add(para.trim())
            } else {
                val sentences = para.split(Regex("(?<=[。！？；])"))
                var current = ""
                for (s in sentences) {
                    if (current.length + s.length > DOC_CHUNK_SIZE && current.isNotBlank()) {
                        chunks.add(current.trim())
                        current = s
                    } else {
                        current += s
                    }
                }
                if (current.isNotBlank()) chunks.add(current.trim())
            }
        }
        return chunks.ifEmpty { listOf(content.take(DOC_CHUNK_SIZE)) }
    }

    private suspend fun evictIfNeeded() {
        val count = count()
        if (count <= MAX_ENTRIES) return

        val allMemories = memoryDao.getTopLongTerm(MAX_ENTRIES + 100)
        val now = System.currentTimeMillis()
        // 复合评分：重要性 50% + 时间衰减 50%（30天窗口）
        val scored = allMemories.map { mem ->
            val daysSinceAccess = (now - mem.accessedAt) / (24 * 3600_000L)
            val decay = if (daysSinceAccess >= DECAY_DAYS) 0f else (1f - daysSinceAccess.toFloat() / DECAY_DAYS)
            val compositeScore = mem.importanceScore * 0.5f + decay * 5f  // decay normalized to 0-5
            mem to compositeScore
        }.sortedByDescending { it.second }

        val toDelete = scored.drop(MAX_ENTRIES)
        for ((memory, _) in toDelete) {
            vectorIndex.remove(memory.id)
            memoryDao.delete(memory)
        }
    }
}

package com.aicustomer.memory

import android.util.Log
import com.aicustomer.data.local.AppDatabase
import com.aicustomer.data.local.MemoryDao
import com.aicustomer.data.local.VectorIndex
import com.aicustomer.data.model.Memory
import com.aicustomer.data.model.Memory.Companion.toByteArray
import com.aicustomer.data.model.Memory.Companion.toFloatArray
import com.aicustomer.data.model.Message
import com.aicustomer.engine.EmbeddingEngine
import com.aicustomer.engine.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MemoryManager(
    private val context: android.content.Context,
    private val embeddingEngine: EmbeddingEngine,
    private val llmEngine: LlmEngine,
    database: AppDatabase
) {
    private val memoryDao: MemoryDao = database.memoryDao()
    private val vectorIndex = VectorIndex()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val workingMemory = WorkingMemory()
    private val shortTermMemory = ShortTermMemory(memoryDao)
    private val longTermMemory = LongTermMemory(memoryDao, embeddingEngine, vectorIndex)
    private val importanceScorer = ImportanceScorer(llmEngine)
    private val memoryCompressor = MemoryCompressor(llmEngine)

    private var currentConversationId: String = generateConversationId()

    fun onUserMessage(message: Message) {
        workingMemory.add(message)
        Log.i("MemoryManager", "onUser: workSize=${workingMemory.size()}")
        scope.launch {
            val score = importanceScorer.score(message.content)
            if (score >= 6f) {
                longTermMemory.store(message.content, currentConversationId, score, "user_preference")
            }
        }
    }

    fun onAssistantMessage(message: Message) {
        workingMemory.add(message)
        Log.i("MemoryManager", "onAssistant: workSize=${workingMemory.size()}")
    }

    suspend fun onConversationEnd() {
        val messages = workingMemory.getMessages()
        if (messages.size < 2) return
        val formatted = workingMemory.formatForPrompt()
        try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(30_000L) {
                    val summary = memoryCompressor.compress(formatted)
                    shortTermMemory.store(summary, currentConversationId)
                    val facts = memoryCompressor.extractFacts(formatted)
                    for (fact in facts) {
                        longTermMemory.store(fact, currentConversationId, importanceScorer.score(fact), "extracted_fact")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MemoryManager", "Compression failed", e)
        }
        shortTermMemory.cleanup()
    }

    suspend fun deleteMemory(memory: Memory) {
        vectorIndex.remove(memory.id)
        memoryDao.delete(memory)
    }

    suspend fun importMemory(content: String, source: String, importanceScore: Float, tags: String = "") {
        val embedding = if (embeddingEngine.isLoaded()) embeddingEngine.embed(content) else null
        val memory = Memory(type = "long_term", content = content, source = source, importanceScore = importanceScore, embedding = embedding?.toByteArray(), tags = tags)
        val id = memoryDao.insert(memory)
        if (embedding != null) vectorIndex.add(id, embedding, content)
    }

    fun startNewConversation() {
        currentConversationId = generateConversationId()
        workingMemory.clear()
    }

    suspend fun buildMemoryContext(currentQuery: String): String {
        val parts = mutableListOf<String>()
        // 知识文档检索（最优先）
        val docs = longTermMemory.formatDocuments(currentQuery, topK = 3)
        if (docs.isNotEmpty()) parts.add(docs)
        if (embeddingEngine.isLoaded()) {
            val ltc = longTermMemory.formatForPrompt(currentQuery, topK = 3)
            if (ltc.isNotEmpty()) parts.add(ltc)
        }
        val stc = shortTermMemory.formatForPrompt(limit = 3)
        if (stc.isNotEmpty()) parts.add(stc)
        return parts.joinToString("\n\n")
    }

    /** 添加知识文档到长期记忆 */
    suspend fun addDocument(title: String, content: String) {
        longTermMemory.storeDocument(title, content)
    }

    /** 清空知识文档 */
    suspend fun clearDocuments() {
        val docs = memoryDao.getByType(LongTermMemory.TYPE_KNOWLEDGE)
        for (doc in docs) {
            vectorIndex.remove(doc.id)
            memoryDao.delete(doc)
        }
    }

    suspend fun initVectorIndex() {
        val all = memoryDao.getTopLongTerm(1000)
        for (m in all) { if (m.embedding != null) vectorIndex.add(m.id, m.embedding.toFloatArray(), m.content) }
    }

    fun getDao() = memoryDao
    fun getLongTermMemory() = longTermMemory
    fun getShortTermMemory() = shortTermMemory
    fun getWorkingMemory() = workingMemory

    fun destroy() { scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    private fun generateConversationId() = "conv_${System.currentTimeMillis()}"
}

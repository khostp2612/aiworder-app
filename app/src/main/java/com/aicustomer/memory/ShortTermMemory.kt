package com.aicustomer.memory

import com.aicustomer.data.local.MemoryDao
import com.aicustomer.data.model.Memory

/**
 * 短期记忆 - 对话摘要
 *
 * 每段对话结束后，将对话压缩为摘要存储
 * 保留最近30天，自动清理过期记忆
 */
class ShortTermMemory(
    private val memoryDao: MemoryDao
) {

    companion object {
        private const val RETENTION_DAYS = 30L
        private const val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000L
    }

    /**
     * 存储一段对话摘要
     */
    suspend fun store(summary: String, sourceConversationId: String, importanceScore: Float = 3f) {
        val memory = Memory(
            type = "short_term",
            content = summary,
            source = sourceConversationId,
            importanceScore = importanceScore,
            tags = "summary"
        )
        memoryDao.insert(memory)
    }

    /**
     * 获取最近N条短期记忆
     */
    suspend fun getRecent(limit: Int = 20): List<Memory> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return memoryDao.getRecentShortTerm(cutoff, limit)
    }

    /**
     * 清理过期短期记忆
     */
    suspend fun cleanup() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        memoryDao.cleanOldShortTerm(cutoff)
    }

    /**
     * 格式化短期记忆用于Prompt注入
     */
    suspend fun formatForPrompt(limit: Int = 10): String {
        val memories = getRecent(limit)
        if (memories.isEmpty()) return ""

        return memories.joinToString("\n") { mem ->
            "• ${mem.content}"
        }.let { "【近期对话摘要】\n$it" }
    }
}

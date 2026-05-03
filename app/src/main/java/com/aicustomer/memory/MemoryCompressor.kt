package com.aicustomer.memory

import com.aicustomer.engine.LlmEngine

/**
 * 记忆压缩器 - 将对话压缩为摘要
 *
 * 对话结束后，将工作记忆中的对话压缩为简短摘要
 * 用于短期记忆存储
 */
class MemoryCompressor(
    private val llmEngine: LlmEngine
) {

    companion object {
        private const val COMPRESS_PROMPT = """请将以下对话压缩为一段简短摘要（50字以内），保留关键信息。

要求：
- 只保留重要事实、用户偏好、关键决策
- 忽略寒暄和重复内容
- 使用简洁的陈述句

对话内容："""
    }

    /**
     * 将对话压缩为摘要
     * @param messages 对话消息列表（格式化后的文本）
     * @return 摘要文本
     */
    suspend fun compress(messages: String): String {
        if (!llmEngine.isLoaded()) {
            // 降级：取第一个完整句子
            val firstSentence = messages.split(Regex("[。！？]")).firstOrNull()?.trim() ?: messages.take(50)
            return "$firstSentence。"
        }

        val prompt = "$COMPRESS_PROMPT\n$messages"
        return llmEngine.generate(prompt, temperature = 0.3f, maxTokens = 128)
    }

    suspend fun extractFacts(messages: String): List<String> {
        if (!llmEngine.isLoaded()) return emptyList()

        val prompt = """请从以下对话中提取关键事实，每行一条，格式为"事实：xxx"。

对话内容：
$messages"""

        val result = llmEngine.generate(prompt, temperature = 0.2f, maxTokens = 256)
        return result.lines()
            .filter { line ->
                val t = line.trim()
                t.startsWith("事实：") || t.startsWith("事实:") ||
                t.startsWith("- ") || t.startsWith("• ") ||
                t.startsWith("1.") || t.startsWith("2.") ||
                t.startsWith("3.") || t.startsWith("4.") ||
                t.startsWith("5.")
            }
            .map { line ->
                line.trim()
                    .removePrefix("事实：").removePrefix("事实:")
                    .removePrefix("- ").removePrefix("• ")
                    .removePrefix("1.").removePrefix("2.")
                    .removePrefix("3.").removePrefix("4.")
                    .removePrefix("5.").trim()
            }
            .filter { it.isNotBlank() }
    }
}

package com.aicustomer.memory

import com.aicustomer.data.model.Message

class WorkingMemory(
    private val maxChars: Int = 800   // 0.6B 模型上下文有限，严格限制
) {
    private val messages = mutableListOf<Message>()
    private val maxSystemMessages = 3  // 限制system消息数量

    fun add(message: Message) {
        messages.add(message)
        // 限制system消息数
        if (message.role == "system") {
            val systemMsgs = messages.filter { it.role == "system" }
            if (systemMsgs.size > maxSystemMessages) {
                messages.remove(systemMsgs.first())
            }
        }
        // 按字符数淘汰最早的非system消息
        var totalChars = messages.sumOf { it.content.length }
        while (totalChars > maxChars) {
            val firstNonSystem = messages.indexOfFirst { it.role != "system" }
            if (firstNonSystem >= 0) {
                totalChars -= messages[firstNonSystem].content.length
                messages.removeAt(firstNonSystem)
            } else break
        }
    }

    fun getMessages(): List<Message> = messages.toList()

    fun formatForPrompt(): String {
        val parts = messages.filter { it.role in listOf("user", "assistant") }
        if (parts.isEmpty()) return ""
        val recent = parts.takeLast(6)
        val lines = recent.joinToString("；") { msg ->
            if (msg.role == "user") "用户刚才说：${msg.content.take(60)}"
            else "你刚回复：${msg.content.take(60)}"
        }
        return "【当前对话】\n$lines"
    }

    fun clear() { messages.clear() }
    fun size(): Int = messages.size
}

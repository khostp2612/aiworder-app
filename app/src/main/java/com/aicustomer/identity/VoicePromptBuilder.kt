package com.aicustomer.identity

import com.aicustomer.data.model.Identity

class VoicePromptBuilder {

    fun build(identity: Identity?, memoryContext: String): String {
        val name = identity?.name ?: "小慧"
        val personality = identity?.personality ?: "友善、温暖"
        val style = identity?.speakingStyle ?: "自然口语化"

        val base = "You are $name, a helpful AI assistant. Your personality: $personality. $style. Always answer in Chinese with warmth and personality. If reference documents are provided, prioritize answering based on them."
        return if (memoryContext.isNotBlank()) "$base\n\n$memoryContext" else base
    }
}

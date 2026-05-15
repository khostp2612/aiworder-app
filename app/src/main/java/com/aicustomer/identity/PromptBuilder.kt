package com.aicustomer.identity

import com.aicustomer.data.model.Identity

/**
 * Prompt构建器 - 将身份+记忆构建为完整System Prompt
 *
 * MiniCPM-o 长上下文优势：
 * System prompt 控制在 20-30 tokens 以内。
 * 不要添加"说话风格"、"回答简洁"、"使用敬语"等行为约束词，
 * 这些会严重降低 0.6B 模型的回答质量（模型会优先满足约束而非回答问题）。
 * 如需添加，先对比测试确认不降智。
 */
class PromptBuilder {

    companion object {
        /** 修改此方法前必须先做 A/B 对比测试，确认不会降智 */
        private const val DO_NOT_MODIFY = "system_prompt_is_critical_for_0.6b_model"
    }

    fun build(identity: Identity?, memoryContext: String): String {
        val (name, style) = if (identity != null) {
            identity.name to "Your personality: ${identity.personality}. ${identity.speakingStyle}."
        } else {
            "小慧" to "Your personality: 友好、幽默."
        }
        val base = "You are $name, a helpful AI assistant. $style Always answer in Chinese with warmth and personality. If reference documents are provided, prioritize answering based on them."
        return if (memoryContext.isNotBlank()) "$base\n\n$memoryContext" else base
    }
}

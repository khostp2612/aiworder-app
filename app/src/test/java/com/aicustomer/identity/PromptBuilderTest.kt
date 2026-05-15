package com.aicustomer.identity

import com.aicustomer.data.model.Identity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PromptBuilderTest {
    private val promptBuilder = PromptBuilder()

    @Test
    fun `build includes identity personality`() {
        val identity = Identity(
            id = 1L,
            name = "测试助手",
            personality = "友善且专业",
            speakingStyle = "简洁",
            expertise = "客服咨询",
            greeting = "你好，有什么可以帮您？"
        )
        val prompt = promptBuilder.build(identity, "")
        assertTrue(prompt.contains("友善且专业"), "Prompt should contain personality: $prompt")
    }

    @Test
    fun `build includes identity name`() {
        val identity = Identity(
            id = 1L,
            name = "客服小助手",
            personality = "友善",
            speakingStyle = "简洁",
            expertise = "咨询",
            greeting = "你好"
        )
        val prompt = promptBuilder.build(identity, "")
        assertTrue(prompt.contains("客服小助手"), "Prompt should contain identity name")
    }

    @Test
    fun `build includes memory context when provided`() {
        val identity = Identity(
            id = 1L,
            name = "助手",
            personality = "友善",
            speakingStyle = "简洁",
            expertise = "咨询",
            greeting = "你好"
        )
        val prompt = promptBuilder.build(identity, "用户喜欢苹果和香蕉")
        assertTrue(prompt.contains("苹果") || prompt.contains("香蕉"),
            "Prompt should contain memory context")
    }

    @Test
    fun `build handles null identity`() {
        val prompt = promptBuilder.build(null, "")
        assertNotNull(prompt, "Prompt should not be null even without identity")
        assertTrue(prompt.isNotBlank(), "Prompt should not be blank")
    }

    @Test
    fun `build handles empty memory context`() {
        val identity = Identity(
            id = 1L, name = "助手", personality = "友善",
            speakingStyle = "简洁", expertise = "咨询", greeting = "你好"
        )
        val prompt = promptBuilder.build(identity, "")
        assertNotNull(prompt)
    }
}

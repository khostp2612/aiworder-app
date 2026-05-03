package com.aicustomer.identity

import com.aicustomer.data.model.Identity
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    private val builder = PromptBuilder()

    private fun createIdentity(
        name: String = "客服小助手",
        personality: String = "友好、耐心",
        speakingStyle: String = "专业简洁",
        expertise: String = "产品咨询",
        taboos: String = "政治话题",
        greeting: String = "您好，有什么可以帮您？"
    ) = Identity(
        id = 1,
        name = name,
        personality = personality,
        speakingStyle = speakingStyle,
        expertise = expertise,
        taboos = taboos,
        greeting = greeting,
        isActive = true
    )

    @Test
    fun `build includes identity name`() {
        val identity = createIdentity(name = "测试助手")
        val prompt = builder.build(identity, "")
        assertTrue(prompt.contains("你是测试助手"))
    }

    @Test
    fun `build includes personality`() {
        val identity = createIdentity(personality = "幽默风趣")
        val prompt = builder.build(identity, "")
        assertTrue(prompt.contains("幽默风趣"))
    }

    @Test
    fun `build includes speaking style`() {
        val identity = createIdentity(speakingStyle = "亲切随和")
        val prompt = builder.build(identity, "")
        assertTrue(prompt.contains("亲切随和"))
    }

    @Test
    fun `build includes behavior instructions`() {
        val identity = createIdentity()
        val prompt = builder.build(identity, "")
        assertTrue(prompt.contains("行为指令"))
    }

    @Test
    fun `build includes memory context when provided`() {
        val identity = createIdentity()
        val memoryContext = "【关于用户的长期记忆】\n用户喜欢咖啡"
        val prompt = builder.build(identity, memoryContext)
        assertTrue(prompt.contains("用户喜欢咖啡"))
    }

    @Test
    fun `build excludes memory context when empty`() {
        val identity = createIdentity()
        val prompt = builder.build(identity, "")
        assertFalse(prompt.contains("【关于用户的长期记忆】"))
    }

    @Test
    fun `build includes taboos when set`() {
        val identity = createIdentity(taboos = "敏感话题")
        val prompt = builder.build(identity, "")
        assertTrue(prompt.contains("禁忌话题"))
        assertTrue(prompt.contains("敏感话题"))
    }

    @Test
    fun `build excludes taboos section when empty`() {
        val identity = createIdentity(taboos = "")
        val prompt = builder.build(identity, "")
        assertFalse(prompt.contains("禁忌话题"))
    }

    @Test
    fun `buildChatPrompt wraps with ChatML format`() {
        val prompt = builder.buildChatPrompt(
            systemPrompt = "你是一个助手",
            userMessage = "你好"
        )
        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("<|im_start|>user"))
        assertTrue(prompt.contains("<|im_start|>assistant"))
        assertTrue(prompt.contains("<|im_end|>"))
    }

    @Test
    fun `buildChatPrompt includes chat history`() {
        val prompt = builder.buildChatPrompt(
            systemPrompt = "助手",
            userMessage = "新问题",
            chatHistory = "user: 老问题\nassistant: 老回答"
        )
        assertTrue(prompt.contains("老问题"))
        assertTrue(prompt.contains("新问题"))
    }

    @Test
    fun `buildChatPrompt excludes chat history when blank`() {
        val prompt = builder.buildChatPrompt(
            systemPrompt = "助手",
            userMessage = "你好",
            chatHistory = ""
        )
        assertFalse(prompt.contains("老问题"))
    }
}

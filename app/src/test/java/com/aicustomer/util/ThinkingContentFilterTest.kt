package com.aicustomer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ThinkingContentFilter 测试
 * 用于验证 Qwen3 模型的 <think/> 标签过滤逻辑
 */
class ThinkingContentFilterTest {
    private lateinit var filter: ThinkingContentFilter

    @BeforeEach
    fun setup() { filter = ThinkingContentFilter() }

    @Test
    fun `filters complete think tags`() {
        // 模拟流式输入: "<think思考过程</think你好"
        filter.reset()
        val result1 = filter.filter("<think")
        assertNull(result1, "Partial think tag should be buffered")
        val result2 = filter.filter(">思考中")
        assertNull(result2, "Content inside think should be filtered")
        val result3 = filter.filter("</think")
        assertNull(result3, "Partial closing tag should be buffered")
        val result4 = filter.filter(">你好")
        assertEquals("你好", result4, "Content after think should pass through")
    }

    @Test
    fun `passes through normal text`() {
        val result = filter.filter("这是一条正常消息")
        assertEquals("这是一条正常消息", result)
    }

    @Test
    fun `handles multiple think blocks`() {
        filter.reset()
        assertNull(filter.filter("<think"))
        assertNull(filter.filter(">块1"))
        assertNull(filter.filter("</think"))
        assertEquals("可见文本", filter.filter(">可见文本"))
    }

    @Test
    fun `flush returns remaining buffered content`() {
        filter.reset()
        // Non-think text passes through immediately, buffer is cleared
        assertEquals("正常内容", filter.filter("正常内容"))
        // After pass-through, buffer should be empty, flush returns null
        assertNull(filter.flush())
    }

    @Test
    fun `reset clears state`() {
        filter.reset()
        assertNull(filter.filter("<think"))
        filter.reset()
        // 重置后应该正常输出
        assertEquals("你好", filter.filter("你好"))
    }
}

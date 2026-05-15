package com.aicustomer.voice

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LocalAsrProviderTextQualityTest {

    private lateinit var provider: LocalAsrProvider

    @BeforeEach
    fun setUp() {
        val mockContext = mockk<Context>(relaxed = true)
        provider = LocalAsrProvider(mockContext)
    }

    // ========================
    // Valid Chinese text
    // ========================

    @Test
    fun `valid Chinese text passes`() {
        assertTrue(provider.isTextQualityPassing("你好世界"))
        assertTrue(provider.isTextQualityPassing("今天天气真不错"))
        assertTrue(provider.isTextQualityPassing("请问有什么可以帮助您的"))
    }

    @Test
    fun `Chinese text with punctuation passes`() {
        assertTrue(provider.isTextQualityPassing("你好，世界！"))
        assertTrue(provider.isTextQualityPassing("是的，我知道了。"))
    }

    @Test
    fun `short valid text passes`() {
        assertTrue(provider.isTextQualityPassing("你好")) // 2 chars, 100% Chinese
    }

    // ========================
    // Invalid / noise text
    // ========================

    @Test
    fun `empty text is rejected`() {
        assertFalse(provider.isTextQualityPassing(""))
        assertFalse(provider.isTextQualityPassing(" "))
    }

    @Test
    fun `single character is rejected`() {
        assertFalse(provider.isTextQualityPassing("啊"))
        assertFalse(provider.isTextQualityPassing("A"))
    }

    @Test
    fun `English text is rejected`() {
        assertFalse(provider.isTextQualityPassing("hello world"))
        assertFalse(provider.isTextQualityPassing("this is music"))
    }

    @Test
    fun `numeric text is rejected`() {
        assertFalse(provider.isTextQualityPassing("12345"))
        assertFalse(provider.isTextQualityPassing("3.14159"))
    }

    @Test
    fun `garbled STT output from music is rejected`() {
        // Music/game sound → STT produces nonsense
        assertFalse(provider.isTextQualityPassing("asdf jkl qwer"))
        assertFalse(provider.isTextQualityPassing("xxx zzz yyy"))
        assertFalse(provider.isTextQualityPassing("── ● ○ ■ □"))
    }

    @Test
    fun `repeated single character is rejected`() {
        assertFalse(provider.isTextQualityPassing("啊啊啊啊啊"))
        assertFalse(provider.isTextQualityPassing("一一一一一"))
        assertFalse(provider.isTextQualityPassing("哈哈哈哈哈哈哈"))
    }

    @Test
    fun `mixed Chinese below ratio threshold is rejected`() {
        // ~30% Chinese, below default 40% min
        assertFalse(provider.isTextQualityPassing("he你好lloworld"))
    }

    @Test
    fun `continuous same character sequence is rejected`() {
        assertFalse(provider.isTextQualityPassing("你好啊啊啊啊啊"))
        assertFalse(provider.isTextQualityPassing("我一一一一一知道"))
    }
}

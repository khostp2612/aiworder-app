package com.aicustomer.memory

import com.aicustomer.engine.LlmEngine

class ImportanceScorer(
    private val llmEngine: LlmEngine
) {
    companion object {
        private const val SCORING_PROMPT = """请对以下对话内容的重要性进行评分（0-10分）。

评分标准：
- 8-10分：包含用户姓名、联系方式、重要偏好、关键决策
- 6-8分：包含用户习惯、一般偏好、事实信息
- 3-6分：日常对话、情绪表达、一般性讨论
- 0-3分：寒暄、无实质内容的对话

只回复一个0-10的整数评分，不要解释。"""
    }

    suspend fun score(messageContent: String): Float {
        if (!llmEngine.isLoaded() || llmEngine.isGenerating()) {
            return fallbackScore(messageContent)
        }

        val prompt = "$SCORING_PROMPT\n\n对话内容：$messageContent"
        val result = llmEngine.generate(prompt, temperature = 0.1f, maxTokens = 4)

        val llmScore = try {
            val match = Regex("""\d+""").find(result)
            match?.value?.toFloatOrNull() ?: 3f
        } catch (e: Exception) { 3f }

        // 如果LLM返回0（单发模式不稳定），用关键词兜底
        return if (llmScore <= 0f) fallbackScore(messageContent) else llmScore.coerceIn(0f, 10f)
    }

    private fun fallbackScore(messageContent: String): Float {
        val keywords = listOf(
            9f to Regex("""电话|手机|联系方式|号码"""),
            8f to Regex("""我叫|我是|姓名|名字"""),
            7f to Regex("""住在|地址|城市|北京|上海|广州|深圳|地点|位置"""),
            6f to Regex("""喜欢|爱|习惯|偏好|经常|总是"""),
            5f to Regex("""工作|公司|职业|行业"""),
            4f to Regex("""问题|帮助|怎么办|如何""")
        )
        for ((score, regex) in keywords) {
            if (regex.containsMatchIn(messageContent)) return score
        }
        return 3f
    }

    suspend fun scoreBatch(messages: List<String>): Float {
        if (messages.isEmpty()) return 0f
        return score(messages.joinToString(" | "))
    }
}

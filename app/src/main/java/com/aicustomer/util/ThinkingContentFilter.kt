package com.aicustomer.util

/**
 * ThinkingContentFilter - 过滤 LLM 输出中的 thinking 标签内容
 *
 * 本地模型可能输出 <think>...</think> 标签，需要过滤内部推理过程。
 * 本工具在流式场景下逐 token 过滤这些内容。
 *
 * 使用方式：
 * ```
 * val filter = ThinkingContentFilter()
 * val output = filter.filter(token)  // 返回过滤后的文本或 null
 * // 生成结束时调用 flush() 获取残留内容
 * val remaining = filter.flush()
 * ```
 */
class ThinkingContentFilter {
    private var inThinkBlock = false
    private val buffer = StringBuilder()

    /**
     * 过滤单个 token，返回应显示的内容
     * @return 应显示的文本，null 表示当前 token 被过滤或正在缓冲
     */
    fun filter(token: String): String? {
        buffer.append(token)
        val current = buffer.toString()

        if (!inThinkBlock) {
            val thinkStart = findThinkStart(current)
            if (thinkStart.first != -1) {
                inThinkBlock = true
                val before = current.substring(0, thinkStart.first)
                val thinkEnd = current.indexOf("</think", thinkStart.second)
                if (thinkEnd != -1) {
                    val closeEnd = current.indexOf(">", thinkEnd)
                    if (closeEnd != -1) {
                        inThinkBlock = false
                        val after = current.substring(closeEnd + 1)
                        buffer.clear()
                        buffer.append(after)
                        return before.ifBlank { null }
                    }
                }
                buffer.clear()
                buffer.append(current)
                return before.ifBlank { null }
            }

            if (isPossibleThinkPrefix(current)) {
                return null
            }

            buffer.clear()
            return token
        }

        // 在 thinking 块中，查找 </think
        val thinkEnd = current.indexOf("</think")
        if (thinkEnd != -1) {
            val closeEnd = current.indexOf(">", thinkEnd)
            if (closeEnd != -1) {
                inThinkBlock = false
                val after = current.substring(closeEnd + 1)
                buffer.clear()
                buffer.append(after)
                if (after.isNotBlank()) return after
                return null
            }
        }

        return null
    }

    /**
     * 生成结束时调用，返回缓冲区中残留的非 thinking 内容
     */
    fun flush(): String? {
        if (buffer.isNotEmpty() && !inThinkBlock) {
            val remaining = buffer.toString()
            reset()
            return remaining.ifBlank { null }
        }
        reset()
        return null
    }

    fun reset() {
        inThinkBlock = false
        buffer.clear()
    }

    fun isInThinkBlock(): Boolean = inThinkBlock

    private fun findThinkStart(text: String): Pair<Int, Int> {
        val tags = listOf("<think\n", "<think ", "<think/>", "<think")
        for (tag in tags) {
            val idx = text.indexOf(tag)
            if (idx != -1) {
                return Pair(idx, idx + tag.length)
            }
        }
        return Pair(-1, -1)
    }

    private fun isPossibleThinkPrefix(current: String): Boolean {
        if (!current.startsWith("<")) return false
        if (current.contains(">") && !current.startsWith("<think")) return false
        val prefixes = listOf("<", "<t", "<th", "<thi", "<thin", "<think")
        for (prefix in prefixes) {
            if (current == prefix) return true
        }
        return false
    }
}

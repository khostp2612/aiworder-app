package com.aicustomer.util

/**
 * 语音文本清理器 — 优化 LLM 输出以适配 TTS 播放
 *
 * 云端 LLM 经常输出 Markdown 格式、代码块、URL 等，
 * 直接送入 TTS 会导致不自然的朗读。本工具在送入 TTS 前做清洗。
 */
object VoiceTextCleaner {

    fun cleanForSpeech(text: String): String {
        var t = text

        // 1. 移除 Markdown 加粗/斜体
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        t = t.replace(Regex("\\*(.+?)\\*"), "$1")
        t = t.replace(Regex("`(.+?)`"), "$1")

        // 2. 移除代码块
        t = t.replace(Regex("```[\\s\\S]*?```"), "")

        // 3. 移除 Markdown 列表标记
        t = t.replace(Regex("^[#*-]\\s", RegexOption.MULTILINE), "")

        // 4. 移除 URL
        t = t.replace(Regex("https?://\\S+"), "")

        // 5. 移除英文括号内的内容（通常是英文翻译或注释）
        t = t.replace(Regex("\\([^)]*\\)"), "")

        // 6. 移除 [思考] [...] 标签
        t = t.replace(Regex("\\[.*?\\]"), "")

        // 7. 句号后加空格增强 TTS 停顿
        t = t.replace("。", "。 ")
        t = t.replace("！", "！ ")
        t = t.replace("？", "？ ")
        t = t.replace("；", "； ")

        // 8. 连续空格合并
        t = t.replace(Regex("\\s{2,}"), " ")

        // 9. 移除开头空白和结尾标点后的空格
        t = t.trim()

        // 10. 如果清洗后为空，返回空字符串
        return t.ifBlank { "" }
    }

    /**
     * 为短回复注入语气词，增加人味
     */
    fun injectSpice(text: String): String {
        if (text.length < 8) return text
        val spiceWords = listOf("呀", "呢", "哦", "哈", "~", "啦", "嘛")
        val hasSpice = spiceWords.any { text.trimEnd().endsWith(it) }
        if (!hasSpice && !text.endsWith("。") && !text.endsWith("！") && !text.endsWith("？")) {
            return text.trimEnd() + spiceWords.random()
        }
        return text
    }
}

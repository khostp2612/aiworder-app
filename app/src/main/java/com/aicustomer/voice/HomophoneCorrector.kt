package com.aicustomer.voice

object HomophoneCorrector {

    fun correct(text: String): String {
        if (text.isBlank()) return text

        var result = text

        result = result
            .replace(Regex("在见")) { "再见" }
            .replace(Regex("在来")) { "再来" }
            .replace(Regex("在说")) { "再说" }
            .replace(Regex("在看")) { "再看" }
            .replace(Regex("在做")) { "再做" }
            .replace(Regex("在试")) { "再试" }
            .replace(Regex("在查")) { "再查" }
            .replace(Regex("在确认")) { "再确认" }
            .replace(Regex("在看看")) { "再看看" }
            .replace(Regex("在等等")) { "再等等" }
            .replace(Regex("在打电话")) { "再打电话" }
            .replace(Regex("在发")) { "再发" }

        result = result
            .replace(Regex("好的很")) { "好得很" }
            .replace(Regex("快的很")) { "快得很" }
            .replace(Regex("慢的很")) { "慢得很" }
            .replace(Regex("多的很")) { "多得很" }
            .replace(Regex("热得很")) { "热得很" }
            .replace(Regex("急得很")) { "急得很" }
            .replace(Regex("气得要(命|死)")) { "急得要$1" }
            .replace(Regex("做的(很|非常|太|好|快|慢|不错)") ) { "做得$1" }

        result = result
            .replace(Regex("作什么")) { "做什么" }
            .replace(Regex("作事情")) { "做事情" }
            .replace(Regex("作生意")) { "做生意" }
            .replace(Regex("作饭")) { "做饭" }
            .replace(Regex("作梦")) { "做梦" }
            .replace(Regex("作人")) { "做人" }
            .replace(Regex("作到")) { "做到" }
            .replace(Regex("不好作")) { "不好做" }

        result = result
            .replace(Regex("((?:他|它)们?(?:是|的))")) { m ->
                m.value.replace("它", "他")
            }
            .replace(Regex("把(它|她)送")) { m ->
                m.value.replace("她", "它").replace("他", "它")
            }
            .replace(Regex("请问[那哪]里")) { "请问哪里" }
            .replace(Regex("在[那哪]里")) { "在哪里" }
            .replace(Regex("[那哪]里有")) { "哪里有" }

        result = result
            .replace(Regex("象([不没])")) { "像$1" }
            .replace(Regex("好像([吗呢吧啊])")) { "好像$1" }
            .replace(Regex("好象")) { "好像" }
            .replace(Regex("好样")) { "好像" }

        result = result
            .replace(Regex("坏([的了])")) { "坏$1" }
            .replace(Regex("怀([的了])")) { "坏$1" }
            .replace(Regex("退怀")) { "退货" }
            .replace(Regex("换怀")) { "换货" }

        result = result.replace(Regex("\\s+"), "")

        return result
    }
}

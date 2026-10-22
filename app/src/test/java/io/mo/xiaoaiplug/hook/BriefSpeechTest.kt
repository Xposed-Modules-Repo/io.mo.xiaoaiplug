package io.mo.xiaoaiplug.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefSpeechTest {

    @Test
    fun shortAnswer_isUntouched() {
        val text = "明天晴，最高二十六度。"
        assertEquals(text, BriefSpeech.cut(text))
    }

    @Test
    fun longAnswer_cutsAtFirstSentenceEndAfterTarget() {
        // 第一句 65 字,刚过 TARGET_CHARS
        val first = "今天北京晴转多云，气温十八到二十六度，东南风二级，空气质量良，适合户外活动，" +
            "早晚温差比较大，出门记得带件薄外套，下班路上注意保暖。"
        val rest = "明天有小雨，降水概率百分之七十，出门记得带伞。后天转晴，气温回升到二十八度左右，" +
            "周末两天都是晴天，紫外线较强，注意防晒。"
        val out = BriefSpeech.cut(first + rest)
        assertEquals(first + BriefSpeech.NOTICE, out)
    }

    @Test
    fun shortTail_isSpokenInFull() {
        // 切完只剩一小截,不值得为它补一句"请看屏幕"
        // 第一句 70 字,后面只剩 25 字
        val text = "这".repeat(69) + "。" + "好".repeat(24) + "。"
        assertEquals(text, BriefSpeech.cut(text))
    }

    @Test
    fun oneHugeSentence_fallsBackToComma() {
        val text = (1..40).joinToString("，") { "第${it}项内容" } + "。"
        val out = BriefSpeech.cut(text)
        assertTrue(out.endsWith("。" + BriefSpeech.NOTICE))
        assertTrue(out.length <= BriefSpeech.MAX_CHARS + BriefSpeech.NOTICE.length + 1)
        // 切在逗号上,不会留下半个词;结尾的逗号换成了句号
        assertTrue(!out.contains("，。"))
    }

    @Test
    fun noPunctuationAtAll_hardCuts() {
        val text = "啊".repeat(300)
        assertEquals("啊".repeat(BriefSpeech.TARGET_CHARS) + "。" + BriefSpeech.NOTICE, BriefSpeech.cut(text))
    }
}

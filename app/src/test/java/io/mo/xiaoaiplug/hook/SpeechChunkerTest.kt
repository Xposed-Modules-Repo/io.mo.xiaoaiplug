package io.mo.xiaoaiplug.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    /** 模拟流式:每次多推几个字,收集交出的所有片段。 */
    private fun stream(chunker: SpeechChunker, text: String, step: Int = 3): List<String> {
        val out = ArrayList<String>()
        var i = step
        while (i < text.length) {
            chunker.onDelta(text.substring(0, i), canSpeak = true).takeIf { it.isNotEmpty() }?.let(out::add)
            i += step
        }
        chunker.onDelta(text, canSpeak = true).takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    @Test
    fun halfSentence_isNotSpokenUntilItEnds() {
        val c = SpeechChunker(brief = false, maxChars = 220)
        assertEquals("", c.onDelta("今天北京", canSpeak = true))
        assertEquals("今天北京晴。", c.onDelta("今天北京晴。最高", canSpeak = true))
        assertEquals("", c.onDelta("今天北京晴。最高二十六", canSpeak = true))
        // 定稿时最后半句也要念
        assertEquals("最高二十六度", c.onComplete("今天北京晴。最高二十六度"))
    }

    @Test
    fun streamedPieces_addUpToTheWholeAnswer() {
        val answer = "今天北京晴转多云。气温十八到二十六度！东南风二级；空气质量良。适合户外活动"
        val c = SpeechChunker(brief = false, maxChars = 220)
        val pieces = stream(c, answer)
        val tail = c.onComplete(answer)
        assertEquals(answer, pieces.joinToString("") + tail)
        // 流式阶段交出的都是整句
        assertTrue(pieces.all { it.last() in "。！；" })
    }

    @Test
    fun notOursYet_holdsThenCatchesUp() {
        val c = SpeechChunker(brief = false, maxChars = 220)
        assertEquals("", c.onDelta("第一句。第二句。", canSpeak = false))
        // 接管确认后,之前攒下的整句一次交出
        assertEquals("第一句。第二句。", c.onDelta("第一句。第二句。第三", canSpeak = true))
    }

    @Test
    fun toolMarkup_isNeverSpoken() {
        val c = SpeechChunker(brief = false, maxChars = 220)
        assertEquals("我查一下。", c.onDelta("我查一下。<tool_call>{\"name\":\"weather\"}。", canSpeak = true))
        c.onDiscard()
        // 工具轮之后的新一轮从头开始
        assertEquals("明天晴。", c.onDelta("明天晴。", canSpeak = true))
    }

    @Test
    fun mismatchedFinal_isSpokenFromScratch() {
        val c = SpeechChunker(brief = false, maxChars = 220)
        c.onDelta("我先看看。然后", canSpeak = true)
        // 硬失败:定稿是提示语,和流式正文对不上
        val failure = "(请求失败：连接模型服务超时，请稍后再试)"
        assertEquals(failure, c.onComplete(failure))
    }

    @Test
    fun brief_stopsNearTargetAndAddsNotice() {
        val first = "这".repeat(40) + "。"
        val second = "那".repeat(30) + "。"
        val rest = "后".repeat(80) + "。"
        val answer = first + second + rest
        val c = SpeechChunker(brief = true, maxChars = 220)
        val pieces = stream(c, answer)
        // 念到第二句(累计 72 字)就停
        assertEquals(first + second, pieces.joinToString(""))
        assertEquals(BriefSpeech.NOTICE, c.onComplete(answer))
    }

    @Test
    fun brief_shortTailIsSpokenInFull() {
        val head = "这".repeat(69) + "。"
        val tail = "好".repeat(10) + "。"
        val c = SpeechChunker(brief = true, maxChars = 220)
        val pieces = stream(c, head + tail)
        assertEquals(head, pieces.joinToString(""))
        assertEquals(tail, c.onComplete(head + tail))
    }

    @Test
    fun brief_firstSentenceTooLong_fallsBackToBriefCut() {
        val answer = (1..40).joinToString("，") { "第${it}项内容" } + "。"
        val c = SpeechChunker(brief = true, maxChars = 220)
        assertEquals("", stream(c, answer).joinToString(""))
        assertEquals(BriefSpeech.cut(answer), c.onComplete(answer))
    }

    @Test
    fun normalMode_capsAtMaxChars() {
        val sentence = "这".repeat(49) + "。"
        val answer = sentence.repeat(10)   // 500 字
        val c = SpeechChunker(brief = false, maxChars = 220)
        val spoken = stream(c, answer).joinToString("") + c.onComplete(answer)
        assertTrue(spoken.endsWith(BriefSpeech.NOTICE))
        assertTrue(spoken.length <= 220 + BriefSpeech.NOTICE.length)
    }

    @Test
    fun decimalPoint_isNotASentenceEnd() {
        assertEquals(-1, SpeechChunker.nextBoundary("版本 3.5", 0, 6))
        assertEquals(5, SpeechChunker.nextBoundary("Done. Next", 0, 10))
        // 连着的标点一起带走
        assertEquals(4, SpeechChunker.nextBoundary("好啊！！后面", 0, 6))
    }
}

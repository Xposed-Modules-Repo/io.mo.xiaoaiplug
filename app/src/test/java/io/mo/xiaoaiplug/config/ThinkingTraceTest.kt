package io.mo.xiaoaiplug.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingTraceTest {
    @Test fun cumulativeStreamAndFinalFrameAreNotDuplicated() {
        val trace = ThinkingTrace()
        trace.update("先")
        trace.update("先检查电量")
        trace.update("先检查电量")
        assertEquals("先检查电量", trace.text())
    }

    @Test fun toolRoundsAndPartialFailedRoundAreRetained() {
        val trace = ThinkingTrace()
        trace.nextRound()
        trace.update("查询设备状态")
        trace.nextRound()
        trace.update(" ")
        trace.nextRound()
        trace.update("整理结果时连接中断")
        assertEquals("查询设备状态\n\n整理结果时连接中断", trace.text())
        assertEquals(trace.text(), trace.text())
    }

    @Test fun nonStreamingResponsesAndSeparateConversationsWork() {
        val first = ThinkingTrace()
        first.update(AiResponseText.split("<think>第一轮</think>答案").thinking)
        first.nextRound()
        first.update(AiResponseText.split("正文", "第二轮").thinking)
        assertEquals("第一轮\n\n第二轮", first.text())
        assertEquals("", ThinkingTrace().text())
    }

    @Test fun longThinkingIsBoundedWithoutConsumingAnswerBudget() {
        val entry = LogEntry(time = 1L, type = LogEntry.TYPE_CHAT, title = "问题", detail = "最终答案",
            thinking = "思".repeat(LogEntry.MAX_THINKING + 1))
        val bounded = entry.bounded()
        assertEquals(LogEntry.MAX_THINKING, bounded.thinking.length)
        assertTrue(bounded.thinking.endsWith("[思考内容过长，后续已截断]"))
        assertEquals("最终答案", bounded.detail)
        assertFalse(bounded.detail.contains("思"))
        assertEquals(bounded, bounded.bounded())
    }

    @Test fun oldRecordsAndNormalThinkingRemainUnchanged() {
        val old = LogEntry(time = 1L, type = LogEntry.TYPE_CHAT, title = "旧问题", detail = "旧答案")
        assertEquals("", old.bounded().thinking)
        val current = old.copy(thinking = "检查结果")
        assertEquals(current, current.bounded())
        assertEquals(8000, old.copy(detail = "字".repeat(9000)).bounded().detail.length)
    }
}

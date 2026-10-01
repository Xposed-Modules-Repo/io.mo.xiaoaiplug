package io.mo.xiaoaiplug.hook

import org.junit.Assert.*
import org.junit.Test

class ReplyPresentationTest {
    @Test fun toolRoundsKeepThinkingWhileDiscardingProvisionalAnswers() {
        val reply = ReplyPresentation(showThinking = true)
        reply.onThinking("先检查")
        reply.onThinking("先检查电量") // 累计快照不能重复添加
        reply.onAnswer("正在调用工具")
        reply.onDiscard()
        reply.onThinking("电量已确认")
        reply.onComplete("电量是 80%")
        val expanded = reply.display("占位")
        assertTrue(expanded.expanded)
        assertEquals("▾ 思考过程", expanded.title)
        assertEquals("先检查电量\n\n电量已确认", expanded.thinking)
        assertEquals("电量是 80%", expanded.text)
        reply.toggleThinking()
        val collapsed = reply.display("占位")
        assertFalse(collapsed.expanded)
        assertEquals("▸ 思考过程", collapsed.title)
        assertEquals(expanded.thinking, collapsed.thinking)
        assertEquals(expanded.text, collapsed.text)
        reply.toggleThinking()
        assertTrue(reply.display("占位").expanded)
    }

    @Test fun disabledDisplayAndModelsWithoutThinkingKeepPlainAnswers() {
        val disabled = ReplyPresentation(showThinking = false)
        disabled.onThinking("不应显示")
        disabled.onDiscard()
        disabled.onComplete("答案")
        assertEquals(ReplyPresentation.Display("答案"), disabled.display("占位"))
        val normal = ReplyPresentation(showThinking = true)
        normal.onComplete("普通答案")
        assertEquals(ReplyPresentation.Display("普通答案"), normal.display("占位"))
    }

    @Test fun lateCardReadsLatestThinkingAndFailureReplacesPartialAnswer() {
        val reply = ReplyPresentation(showThinking = true)
        reply.onThinking("正在分析")
        assertEquals(ReplyPresentation.Display("等待答案", "▾ 思考中…", "正在分析", true, waiting = true),
            reply.display("等待答案"))
        reply.onAnswer("部分答案")
        reply.onComplete("请求失败")
        assertEquals(ReplyPresentation.Display("请求失败", "▾ 思考过程", "正在分析", true),
            reply.display("占位"))
    }

    @Test fun longThinkingAndAnswersRemainInSeparateRegionsAfterToggling() {
        val reply = ReplyPresentation(showThinking = true)
        val thought = (1..100).joinToString("\n") { "思考第 $it 行" }
        reply.onThinking(thought)
        reply.onComplete("最终答案")
        assertEquals("最终答案", reply.text("占位"))
        assertEquals(thought, reply.display("占位").thinking)
        reply.toggleThinking()
        assertFalse(reply.display("占位").expanded)
        assertEquals("最终答案", reply.text("占位"))
        reply.toggleThinking()
        assertTrue(reply.display("占位").expanded)
        assertEquals(thought, reply.display("占位").thinking)
    }

    @Test fun newRepliesStartExpandedAndDoNotInheritPreviousCardsState() {
        val old = ReplyPresentation(showThinking = true)
        old.onThinking("旧轮思考")
        old.onComplete("旧答案")
        old.toggleThinking()
        val current = ReplyPresentation(showThinking = true)
        current.onThinking("新轮思考")
        current.onAnswer("新答案")
        assertFalse(old.display("占位").expanded)
        assertTrue(current.display("占位").expanded)
        current.toggleThinking()
        current.onThinking("新轮思考正在更新")
        current.onDiscard()
        current.onThinking("工具返回后的思考")
        assertFalse(current.display("占位").expanded)
        assertEquals("新轮思考正在更新\n\n工具返回后的思考", current.display("占位").thinking)
        assertEquals("旧轮思考", old.display("占位").thinking)
    }

    @Test fun nativeWaitingEndsWithAnswerAndResumesDuringToolExecution() {
        val reply = ReplyPresentation(showThinking = true)
        assertEquals(ReplyPresentation.Display("", waiting = true), reply.display(""))
        reply.onThinking("查询设备信息")
        assertTrue(reply.display("").waiting)
        assertEquals("", reply.text(""))
        reply.onAnswer("准备查询")
        assertFalse(reply.display("").waiting)
        reply.onDiscard()
        assertTrue(reply.display("").waiting)
        assertEquals("查询设备信息", reply.display("").thinking)
        reply.onComplete("电量是 80%")
        assertFalse(reply.display("").waiting)
        assertEquals("电量是 80%", reply.text(""))
    }

    @Test fun failureOrEmptyCompletionDoesNotLeaveNativeLoadingRunning() {
        val reply = ReplyPresentation(showThinking = false)
        reply.onComplete("请求失败")
        assertFalse(reply.display("").waiting)
        assertEquals("请求失败", reply.text(""))
        val empty = ReplyPresentation(showThinking = true)
        empty.onComplete("")
        assertFalse(empty.display("").waiting)
    }

    @Test fun aNewQuestionStopsOldLoadingDespiteLateToolCallbacks() {
        val old = ReplyPresentation(showThinking = true)
        old.onThinking("旧轮思考")
        old.onCancel()
        old.onDiscard()
        old.onThinking("迟到的工具结果")
        assertFalse(old.display("").waiting)
        val next = ReplyPresentation(showThinking = true)
        assertTrue(next.display("").waiting)
    }
}

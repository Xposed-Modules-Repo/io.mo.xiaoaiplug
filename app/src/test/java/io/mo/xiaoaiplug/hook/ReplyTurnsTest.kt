package io.mo.xiaoaiplug.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class ReplyTurnsTest {
    private var time = 0L
    private val turns = ReplyTurns { time }

    @Test fun rewrittenQueryKeepsAsrIdentity() {
        val asr = turns.capture("a", "查看系统版本", true)
        turns.claim(asr.key)
        time = 400
        assertEquals(asr.key, turns.capture("a", "查看版本", false).key)
        assertTrue(turns.ownsCurrent())
    }

    @Test fun repeatedSpokenQuestionIsANewTurn() {
        val first = turns.capture("a", "现在几点", true)
        turns.claim(first.key)
        time = 800
        val next = turns.capture("b", "现在几点", true)
        assertNotEquals(first.key, next.key)
        assertFalse(turns.isCurrent(first.key))
        assertFalse(turns.ownsCurrent())
    }

    @Test fun shortDuplicateDispatchSharesOneRequest() {
        val first = turns.capture("a", "查看电量", false)
        time = 388
        assertEquals(first.key, turns.capture("b", "查看电量", false).key)
        time = 2_000
        assertNotEquals(first.key, turns.capture("c", "查看电量", false).key)
    }

    @Test fun slowAnswerKeepsOwnershipAndLateOldCallbacksDoNotStealNewTurn() {
        val first = turns.capture("a", "问题一", true)
        turns.claim(first.key)
        time = 90_000
        assertTrue(turns.ownsCurrent())
        assertEquals(first.key, turns.attachOutput("rn-a"))
        val next = turns.capture("b", "播放音乐", true)
        assertFalse(turns.ownsCurrent())
        assertEquals(first.key, turns.capture("a", "问题一改写", false).key)
        assertEquals(first.key, turns.attachOutput("rn-a"))
        assertTrue(turns.isCurrent(next.key))
        assertFalse(turns.isCurrentDialog("rn-a"))
    }

    @Test fun concurrentDuplicatesHaveOneIdentity() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val keys = pool.invokeAll((1..40).map { n -> Callable {
                turns.capture("id-$n", "查看电量", false).key
            } }).map { it.get() }.toSet()
            assertEquals(1, keys.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun requestStartsOnceAcrossAsrQueryAndLateCallbacks() {
        val turn = turns.capture("a", "查询", true)
        assertFalse(turns.tryStart(turn.key)) // 放行轮不发起模型请求
        turns.claim(turn.key)
        assertTrue(turns.tryStart(turn.key))
        assertFalse(turns.tryStart(turns.capture("a", "查询改写", false).key))
        time = 90_000
        assertFalse(turns.tryStart(turn.key)) // 请求已结束，也不能因迟到回调重发
        val next = turns.capture("b", "查询", true)
        turns.claim(next.key)
        assertTrue(turns.tryStart(next.key)) // 失败后用户重问，允许重试
    }

    @Test fun simultaneousRequestTriggersStartOnlyOnce() {
        val turn = turns.capture("a", "查看电量", true)
        turns.claim(turn.key)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val started = pool.invokeAll((1..40).map { Callable { turns.tryStart(turn.key) } })
                .count { it.get() }
            assertEquals(1, started)
        } finally {
            pool.shutdownNow()
        }
    }
}

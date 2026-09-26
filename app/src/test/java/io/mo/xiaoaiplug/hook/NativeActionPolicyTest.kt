package io.mo.xiaoaiplug.hook

import org.junit.Assert.*
import org.junit.Test

class NativeActionPolicyTest {
    @Test fun appLaunchIsBlockedAsSoonAsAsrClaimsTurn() {
        var now = 0L
        val turns = ReplyTurns { now }
        val turn = turns.capture("launch", "打开网易云音乐", asr = true)
        assertFalse(NativeActionPolicy.shouldBlock(0, turns.ownsCurrent(), false, false))
        turns.claim(turn.key)
        now = 765L // 真机原版启动比模型 launch_app 早约 2.6 秒。
        assertTrue(NativeActionPolicy.shouldBlock(now, turns.ownsCurrent(), false, false))
        turns.capture("launch", "网易云音乐", asr = false)
        assertTrue(NativeActionPolicy.shouldBlock(now, turns.ownsCurrent(), false, false))
    }

    @Test fun nextUnclaimedTurnDoesNotInheritLaunchBlock() {
        val turns = ReplyTurns { 0L }
        turns.claim(turns.capture("first", "打开网易云音乐", true).key)
        turns.capture("next", "打开微信", true)
        assertFalse(NativeActionPolicy.shouldBlock(0, turns.ownsCurrent(), false, false))
    }

    @Test fun whitelistWinsOverTakeoverAndIndependentViewSetting() {
        assertFalse(NativeActionPolicy.shouldBlock(100, true, true, true))
        assertFalse(NativeActionPolicy.shouldBlock(100, false, true, true))
    }

    @Test fun disabledOrUnconfiguredAiCanKeepNativeLaunch() {
        assertFalse(NativeActionPolicy.shouldBlock(100, false, false, false))
    }

    @Test fun independentViewBlockingStillWorksWithoutAiTakeover() {
        assertTrue(NativeActionPolicy.shouldBlock(100, false, false, true))
    }

    @Test fun staleOrInvalidTimeCannotBlockLaterManualActions() {
        assertTrue(NativeActionPolicy.shouldBlock(12_000, true, false, false))
        assertFalse(NativeActionPolicy.shouldBlock(12_001, true, false, true))
        assertFalse(NativeActionPolicy.shouldBlock(-1, true, false, true))
    }
}

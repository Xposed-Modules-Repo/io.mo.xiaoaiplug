package io.mo.xiaoaiplug.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickToggleTest {

    @Test
    fun parseFlag_readsSettingsIntegers() {
        assertEquals(true, QuickToggle.parseFlag("1\n"))
        assertEquals(false, QuickToggle.parseFlag("0"))
        // zen_mode 2/3、location_mode 3 都算开
        assertEquals(true, QuickToggle.parseFlag("3"))
        // 设置项不存在时 settings get 打印 null,不能当成关
        assertNull(QuickToggle.parseFlag("null"))
        assertNull(QuickToggle.parseFlag(""))
        assertNull(QuickToggle.parseFlag("(无 root，结果可能不完整) Permission denied"))
    }

    @Test
    fun parseWifi_readsCmdWifiStatus() {
        assertEquals(true, QuickToggle.parseWifi("Wifi is enabled"))
        assertEquals(false, QuickToggle.parseWifi("Wifi is disabled"))
        assertNull(QuickToggle.parseWifi("cmd: Can't find service: wifi"))
    }

    @Test
    fun parseNfc_treatsTransitionalStateAsUnknown() {
        assertEquals(true, QuickToggle.parseNfc("  mState=on"))
        assertEquals(false, QuickToggle.parseNfc("mState=off"))
        assertNull(QuickToggle.parseNfc("mState=turning_on"))
        assertNull(QuickToggle.parseNfc(""))
    }

    @Test
    fun parseNight_autoIsUnknown() {
        assertEquals(true, QuickToggle.parseNight("Night mode: yes"))
        assertEquals(false, QuickToggle.parseNight("Night mode: no"))
        assertNull(QuickToggle.parseNight("Night mode: auto"))
    }

    @Test
    fun looksFailed_onlyFlagsExplicitFailures() {
        assertTrue(QuickToggle.looksFailed("Unknown command: set_dnd"))
        assertTrue(QuickToggle.looksFailed("java.lang.SecurityException: ..."))
        assertFalse(QuickToggle.looksFailed(""))
        assertFalse(QuickToggle.looksFailed("Night mode: yes"))
    }

    @Test
    fun brightnessCurve_matchesEndpointsAndRoundTrips() {
        assertEquals(0f, QuickToggle.percentToLinear(0), 1e-6f)
        assertEquals(1f, QuickToggle.percentToLinear(100), 1e-3f)
        // 感知亮度的一半,线性值远低于 0.5 —— 这正是不能直接写 0.5 的原因
        assertTrue(QuickToggle.percentToLinear(50) < 0.1f)
        for (p in listOf(0, 10, 30, 50, 75, 100)) {
            assertEquals(p, QuickToggle.linearToPercent(QuickToggle.percentToLinear(p)))
        }
        var prev = -1f
        for (p in 0..100) {
            val v = QuickToggle.percentToLinear(p)
            assertTrue("not monotonic at $p", v >= prev)
            prev = v
        }
    }

    @Test
    fun describeBrightness_doesNotGuessPercentFromRawValue() {
        assertEquals(
            "亮度：约 50%，自动亮度关闭",
            QuickToggle.describeBrightness("0\n${QuickToggle.percentToLinear(50)}\n128")
        )
        val raw = QuickToggle.describeBrightness("1\nnull\n1024")
        assertTrue(raw, raw.contains("原始值 1024"))
        assertTrue(raw, raw.contains("自动亮度开启"))
    }

    @Test
    fun targetsCoverSpecialCases() {
        assertTrue(QuickToggle.FLASHLIGHT in QuickToggle.TARGETS)
        assertTrue(QuickToggle.BRIGHTNESS in QuickToggle.TARGETS)
        assertEquals(QuickToggle.TARGETS.size, QuickToggle.TARGETS.toSet().size)
    }
}

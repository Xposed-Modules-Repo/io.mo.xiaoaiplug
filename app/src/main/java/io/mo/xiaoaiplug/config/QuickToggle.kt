package io.mo.xiaoaiplug.config

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * quick_toggle 的纯逻辑:命令表、状态解析、亮度曲线。不碰 shell 也不碰 Android API,
 * 所以能在 src/test 里直接测 —— 解析写错的后果是"报已打开、实际没动",推演发现不了。
 *
 * 手电筒和亮度不在 [SWITCHES] 里:手电筒没有 shell 命令,走 CameraManager;
 * 亮度是百分比不是开关。两者的执行都在 Tools.quickToggleImpl。
 */
internal object QuickToggle {

    /**
     * 一个开关:读状态的命令 + 解析 + 改状态的命令。
     *
     * [async] = 命令返回时切换还没完成(Wi-Fi、移动数据、飞行模式要等射频起落),
     * 这类不回读校验 —— 立刻读多半还是旧值,会被误报成"没改成"。
     */
    class Switch(
        val label: String,
        val statusCmd: String,
        val parse: (String) -> Boolean?,
        val setCmd: (Boolean) -> String,
        val async: Boolean = false
    )

    const val FLASHLIGHT = "flashlight"
    const val BRIGHTNESS = "brightness"

    val SWITCHES: Map<String, Switch> = linkedMapOf(
        // wifi_on 有 0~3 四个值(2/3 跟飞行模式的记忆有关),直接读 cmd wifi 的结论更不容易错
        "wifi" to Switch(
            "Wi-Fi", "cmd wifi status 2>/dev/null | head -1", ::parseWifi,
            { on -> "svc wifi ${if (on) "enable" else "disable"}" }, async = true
        ),
        "mobile_data" to Switch(
            "移动数据", "settings get global mobile_data", ::parseFlag,
            { on -> "svc data ${if (on) "enable" else "disable"}" }, async = true
        ),
        // 只写 airplane_mode_on 不会通知射频,必须走 connectivity 命令
        "airplane" to Switch(
            "飞行模式", "settings get global airplane_mode_on", ::parseFlag,
            { on -> "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}" }, async = true
        ),
        "nfc" to Switch(
            "NFC", "dumpsys nfc 2>/dev/null | grep -m1 -i 'mState='", ::parseNfc,
            { on -> "svc nfc ${if (on) "enable" else "disable"}" }, async = true
        ),
        // zen_mode: 0 关,1/2/3 是不同程度的勿扰,都算开
        "dnd" to Switch(
            "勿扰模式", "settings get global zen_mode", ::parseFlag,
            { on -> "cmd notification set_dnd ${if (on) "on" else "off"}" }
        ),
        "dark_mode" to Switch(
            "深色模式", "cmd uimode night", ::parseNight,
            { on -> "cmd uimode night ${if (on) "yes" else "no"}" }
        ),
        "auto_rotate" to Switch(
            "自动旋转", "settings get system accelerometer_rotation", ::parseFlag,
            { on -> "settings put system accelerometer_rotation ${if (on) 1 else 0}" }
        ),
        "auto_brightness" to Switch(
            "自动亮度", "settings get system screen_brightness_mode", ::parseFlag,
            { on -> "settings put system screen_brightness_mode ${if (on) 1 else 0}" }
        ),
        // low_power 只是结果;直接写它不会真的进省电,得让 PowerManager 切模式
        "power_save" to Switch(
            "省电模式", "settings get global low_power", ::parseFlag,
            { on -> "cmd power set-mode ${if (on) 1 else 0}" }
        ),
        // location_mode: 0 关,3 开(老版本还有 1/2 的精度档),非 0 都算开
        "location" to Switch(
            "定位", "settings get secure location_mode", ::parseFlag,
            { on -> "cmd location set-location-enabled ${if (on) "true" else "false"}" }
        )
    )

    /** 给模型的 target 枚举。手电筒、亮度不在 [SWITCHES] 里,单独补上。 */
    val TARGETS: List<String> = SWITCHES.keys.toList() + listOf(FLASHLIGHT, BRIGHTNESS)

    /** `settings get` 的整数输出,非 0 即开。读到 null / 报错时返回 null,别瞎猜。 */
    fun parseFlag(out: String): Boolean? =
        firstLine(out)?.toIntOrNull()?.let { it != 0 }

    /** `cmd wifi status` 第一行:"Wifi is enabled" / "Wifi is disabled"。 */
    fun parseWifi(out: String): Boolean? {
        val l = out.lowercase(Locale.ROOT)
        return when {
            "is enabled" in l -> true
            "is disabled" in l -> false
            else -> null
        }
    }

    /** `dumpsys nfc` 的 "mState=on"。turning_on / turning_off 这种中间态当未知。 */
    fun parseNfc(out: String): Boolean? {
        val state = Regex("mState=(\\w+)", RegexOption.IGNORE_CASE).find(out)
            ?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        return when (state) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    /** `cmd uimode night` → "Night mode: yes"。auto / custom 是跟随时间,没法说开还是关。 */
    fun parseNight(out: String): Boolean? {
        val v = out.substringAfter(':', "").trim().lowercase(Locale.ROOT)
        return when (v) {
            "yes" -> true
            "no" -> false
            else -> null
        }
    }

    /**
     * 退出码是 0 也可能没执行成:`cmd` 遇到不认识的子命令常常只打一行提示就正常退出。
     * 只认明确的失败字样 —— 正常输出(比如 svc 什么都不打)不该被误判。
     */
    fun looksFailed(out: String): Boolean {
        val l = out.lowercase(Locale.ROOT)
        return listOf("unknown command", "exception", "error:", "not found", "permission denial", "usage:")
            .any { it in l }
    }

    // ---------------------------------------------------------------- 亮度

    // Android BrightnessUtils 的 HLG 曲线常数。系统亮度条按"感知亮度"走,底层存的是线性值 ——
    // 把"调到 30%"直接当线性 0.30 写进去,屏幕会比用户在亮度条上拖到 30% 亮得多。
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    /** 亮度条百分比 → `cmd display set-brightness` 要的线性值(0~1)。同 BrightnessUtils.convertGammaToLinearFloat。 */
    fun percentToLinear(percent: Int): Float {
        val v = percent.coerceIn(0, 100) / 100f
        val ret = if (v <= R) (v / R) * (v / R) else exp((v - C) / A) + B
        return ret.coerceIn(0f, 12f) / 12f
    }

    /** 线性值 → 亮度条百分比。同 BrightnessUtils.convertLinearToGammaFloat。 */
    fun linearToPercent(linear: Float): Int {
        val v = linear.coerceIn(0f, 1f) * 12f
        val g = if (v <= 1f) sqrt(v) * R else A * ln(v - B) + C
        return (g * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * 亮度状态。输入是三条 `settings get` 的输出,按行:screen_brightness_mode、
     * screen_brightness_float、screen_brightness。
     *
     * 优先用 float(Android 11+ 才有,0~1 线性)。只有老的整数值时**不换算成百分比** ——
     * 它的上限各机型不同(255 / 2047 / 4095…),猜错了就是一本正经地报错数。
     */
    fun describeBrightness(out: String): String {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val auto = lines.getOrNull(0)?.let(::parseFlag)
        val float = lines.getOrNull(1)?.toFloatOrNull()?.takeIf { it in 0f..1f }
        val raw = lines.getOrNull(2)?.toIntOrNull()
        val level = when {
            float != null -> "约 ${linearToPercent(float)}%"
            raw != null -> "原始值 $raw（这台设备的亮度上限未知，没法换算成百分比）"
            else -> "读不到"
        }
        val mode = when (auto) {
            true -> "，自动亮度开启（实际亮度随环境光变化）"
            false -> "，自动亮度关闭"
            null -> ""
        }
        return "亮度：$level$mode"
    }

    private fun firstLine(out: String): String? =
        out.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

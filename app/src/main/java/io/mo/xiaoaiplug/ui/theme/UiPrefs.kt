package io.mo.xiaoaiplug.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面自己的偏好（深浅色、主题色）。
 *
 * **刻意不走 ConfigProvider**：那套桥是为「跨进程给 hook 读」建的，而这些设置只有本进程的
 * 界面用得到，塞进 AiConfig 会让本来就要改 5 个地方的加字段流程变成 6 个。
 * 直接用本进程的 SharedPreferences 就够。
 */
class UiPrefs private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "xiaoai_plug_ui"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_ACCENT = "accent"

        @Volatile
        private var instance: UiPrefs? = null

        fun get(context: Context): UiPrefs =
            instance ?: synchronized(this) {
                instance ?: UiPrefs(context.applicationContext).also { instance = it }
            }
    }

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _darkMode = MutableStateFlow(
        // 枚举名存字符串而不是序号：以后往中间插一个值，序号会把用户的选择错位。
        runCatching { DarkMode.valueOf(prefs.getString(KEY_DARK_MODE, null) ?: "") }
            .getOrDefault(DarkMode.SYSTEM)
    )
    val darkMode: StateFlow<DarkMode> = _darkMode.asStateFlow()

    private val _accent = MutableStateFlow(
        runCatching { AccentColor.valueOf(prefs.getString(KEY_ACCENT, null) ?: "") }
            .getOrDefault(AccentColor.ORANGE)
    )
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    fun setDarkMode(mode: DarkMode) {
        _darkMode.value = mode
        prefs.edit().putString(KEY_DARK_MODE, mode.name).apply()
    }

    fun setAccent(accent: AccentColor) {
        _accent.value = accent
        prefs.edit().putString(KEY_ACCENT, accent.name).apply()
    }
}

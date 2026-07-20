package io.mo.xiaoaiplug.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 深浅色模式。跟模块配置无关，纯粹是界面自己的偏好，所以不进 AiConfig。 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/** 可选主题色。miuix 的 colorScheme 允许覆盖 primary，给几个预设就够了。 */
enum class AccentColor(val label: String, val color: Color) {
    ORANGE("小米橙", Color(0xFFFF6900)),
    BLUE("蓝", Color(0xFF3482FF)),
    GREEN("绿", Color(0xFF34C759)),
    PURPLE("紫", Color(0xFF8A5CF6)),
    PINK("粉", Color(0xFFFF375F))
}

/** 当前是否深色。自定义绘制（底栏高光、玻璃描边）要靠它决定明暗，miuix 没直接暴露。 */
val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { UiPrefs.get(context) }

    val darkMode by prefs.darkMode.collectAsStateWithLifecycle()
    val accent by prefs.accent.collectAsStateWithLifecycle()

    val dark = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

    val colors = if (dark) {
        darkColorScheme(primary = accent.color)
    } else {
        lightColorScheme(primary = accent.color)
    }

    CompositionLocalProvider(LocalIsDark provides dark) {
        MiuixTheme(colors = colors, content = content)
    }
}

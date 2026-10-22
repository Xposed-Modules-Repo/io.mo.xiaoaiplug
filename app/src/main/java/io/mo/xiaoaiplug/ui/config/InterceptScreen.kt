package io.mo.xiaoaiplug.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.mo.xiaoaiplug.config.DEFAULT_JUMP_ALLOW_WORDS
import io.mo.xiaoaiplug.config.DEFAULT_SKIP_TAKEOVER_PATTERN
import io.mo.xiaoaiplug.config.DEFAULT_WEB_SEARCH_ALLOW_WORDS
import io.mo.xiaoaiplug.ui.ConfigViewModel
import io.mo.xiaoaiplug.ui.nav.CardHorizontalPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.regex.PatternSyntaxException

@Composable
fun InterceptScreen(vm: ConfigViewModel, bottomInset: Dp, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()

    SubScreen(title = "拦截规则", bottomInset = bottomInset, onBack = onBack) {
        // 分区标题只做定位，别再复述一遍卡片里的 summary —— 原来「「查看类」不跳设置页」
        // 和开关自己的说明是同一句话的两种写法，读起来像重复了一遍。
        item { SmallTitle("查看类跳转") }
        item {
            AllowWordsCard(
                checked = config.blockViewJump,
                onCheckedChange = { on -> vm.update { it.copy(blockViewJump = on) } },
                title = "拦截跳转系统设置",
                summary = "拦截某些回复跳转系统设置",
                words = config.jumpAllowWords,
                onWordsChange = { v -> vm.updateText { it.copy(jumpAllowWords = v) } },
                hint = "白名单，逗号或空格分隔。留空使用默认",
                defaultWords = DEFAULT_JUMP_ALLOW_WORDS
            )
        }

        item { SmallTitle("兜底搜索") }
        item {
            AllowWordsCard(
                checked = config.blockWebSearch,
                onCheckedChange = { on -> vm.update { it.copy(blockWebSearch = on) } },
                title = "拦截兜底搜索",
                summary = "拦下简单问题跳搜索",
                words = config.webSearchAllowWords,
                onWordsChange = { v -> vm.updateText { it.copy(webSearchAllowWords = v) } },
                hint = "白名单，逗号或空格分隔。留空使用默认",
                defaultWords = DEFAULT_WEB_SEARCH_ALLOW_WORDS
            )
        }

        item { SmallTitle("白名单直通") }
        item {
            AllowWordsCard(
                checked = config.skipTakeoverEnabled,
                onCheckedChange = { on -> vm.update { it.copy(skipTakeoverEnabled = on) } },
                title = "命中正则不接管",
                summary = "问话命中就完全不接管，原生行为照旧（某些行为系统自带效果更佳，不建议删除）",
                words = config.skipTakeoverPattern,
                onWordsChange = { v -> vm.updateText { it.copy(skipTakeoverPattern = v) } },
                hint = "正则表达式，留空使用默认",
                defaultWords = DEFAULT_SKIP_TAKEOVER_PATTERN,
                fieldLabel = "正则表达式",
                validate = ::regexError
            )
        }
    }
}

/**
 * 「开关 + 放行词 + 恢复默认」这一组。两条拦截规则的结构完全一样，
 * 早先是两段逐行重复的代码，改一处忘另一处的间距就会错开。
 */
@Composable
private fun AllowWordsCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String,
    words: String,
    onWordsChange: (String) -> Unit,
    hint: String,
    defaultWords: String,
    fieldLabel: String = "放行词",
    // 返回 null 表示没问题;否则是要用红字替换掉 hint 的错误说明
    validate: ((String) -> String?)? = null
) {
    val error = remember(words, validate) { validate?.invoke(words) }
    val focusManager = LocalFocusManager.current
    Card(Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            summary = summary
        )
        ConfigTextField(
            value = words,
            onValueChange = onWordsChange,
            label = fieldLabel,
            useLabelAsPlaceholder = true,
            enabled = checked,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = CardHorizontalPadding, vertical = 6.dp)
        )
        // 说明和「恢复默认」并到一行：按钮原来是贴着卡片下沿的全宽色块，
        // 既压着上面的说明文字，视觉重量也远超它的实际份量（改坏了才用一次）。
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = CardHorizontalPadding, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = error ?: hint,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                color = if (error != null) Color(0xFFFF3B30) else MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            TextButton(
                text = "恢复默认",
                onClick = {
                    // 先放掉焦点,输入框才会接收这次外部改动(见 ConfigTextField)
                    focusManager.clearFocus()
                    onWordsChange(defaultWords)
                },
                // 开关关掉时输入框已经灰了，按钮也得跟着停用 —— 否则能去改一个当前根本不生效的值
                enabled = checked && words != defaultWords,
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 白名单正则写错时的说明。hook 那边编译失败会**静默当作没写**(见 HookEntry.isAiTakeoverSkip),
 * 用户只会觉得"白名单怎么不灵了";在这里当场标红,改对之前就知道它不生效。
 * 用的是同一个 Regex 引擎,这里能编译过,小爱那边就能。
 */
private fun regexError(pattern: String): String? {
    if (pattern.isBlank()) return null
    val e = runCatching { Regex(pattern) }.exceptionOrNull() ?: return null
    val where = (e as? PatternSyntaxException)?.index?.takeIf { it >= 0 }
        ?.let { "（第 ${it + 1} 个字符附近）" }.orEmpty()
    val what = (e as? PatternSyntaxException)?.description ?: e.message.orEmpty()
    return "正则写法有误$where，当前不会生效：$what"
}

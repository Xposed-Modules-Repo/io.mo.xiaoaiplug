package com.xiaoai.plug.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoai.plug.config.DEFAULT_JUMP_ALLOW_WORDS
import com.xiaoai.plug.config.DEFAULT_WEB_SEARCH_ALLOW_WORDS
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.nav.CardHorizontalPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
                onWordsChange = { v -> vm.update { it.copy(jumpAllowWords = v) } },
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
                onWordsChange = { v -> vm.update { it.copy(webSearchAllowWords = v) } },
                hint = "白名单，逗号或空格分隔。留空使用默认",
                defaultWords = DEFAULT_WEB_SEARCH_ALLOW_WORDS
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
    defaultWords: String
) {
    Card(Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            summary = summary
        )
        TextField(
            value = words,
            onValueChange = onWordsChange,
            label = "放行词",
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
                text = hint,
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            TextButton(
                text = "恢复默认",
                onClick = { onWordsChange(defaultWords) },
                // 开关关掉时输入框已经灰了，按钮也得跟着停用 —— 否则能去改一个当前根本不生效的值
                enabled = checked && words != defaultWords,
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

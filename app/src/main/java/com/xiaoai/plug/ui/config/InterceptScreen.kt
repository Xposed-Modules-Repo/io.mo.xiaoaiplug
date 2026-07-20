package com.xiaoai.plug.ui.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        item { SmallTitle("「查看类」不跳设置页") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.blockViewJump,
                    onCheckedChange = { on -> vm.update { it.copy(blockViewJump = on) } },
                    title = "拦截跳转系统设置",
                    summary = "说「查看/查询…」时不跳走，改由 AI 回答"
                )
                TextField(
                    value = config.jumpAllowWords,
                    onValueChange = { v -> vm.update { it.copy(jumpAllowWords = v) } },
                    label = "放行词",
                    useLabelAsPlaceholder = true,
                    enabled = config.blockViewJump,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = CardHorizontalPadding, vertical = 6.dp)
                )
                Text(
                    text = "命中这些词才允许跳转，逗号或空格分隔。留空会恢复默认。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding)
                )
                TextButton(
                    text = "恢复默认",
                    onClick = { vm.update { it.copy(jumpAllowWords = DEFAULT_JUMP_ALLOW_WORDS) } },
                    enabled = config.jumpAllowWords != DEFAULT_JUMP_ALLOW_WORDS,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { SmallTitle("答不上来时不跳全局搜索") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.blockWebSearch,
                    onCheckedChange = { on -> vm.update { it.copy(blockWebSearch = on) } },
                    title = "拦截兜底搜索",
                    summary = "拦下「小爱只能帮你到这儿啦」+跳搜索"
                )
                TextField(
                    value = config.webSearchAllowWords,
                    onValueChange = { v -> vm.update { it.copy(webSearchAllowWords = v) } },
                    label = "放行词",
                    useLabelAsPlaceholder = true,
                    enabled = config.blockWebSearch,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = CardHorizontalPadding, vertical = 6.dp)
                )
                Text(
                    text = "用户明确说「搜一下」时照常跳搜索，不然「搜一下明天天气」也会被截走。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding)
                )
                TextButton(
                    text = "恢复默认",
                    onClick = {
                        vm.update { it.copy(webSearchAllowWords = DEFAULT_WEB_SEARCH_ALLOW_WORDS) }
                    },
                    enabled = config.webSearchAllowWords != DEFAULT_WEB_SEARCH_ALLOW_WORDS,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

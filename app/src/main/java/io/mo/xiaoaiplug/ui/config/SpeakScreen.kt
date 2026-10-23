package io.mo.xiaoaiplug.ui.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.mo.xiaoaiplug.ui.ConfigViewModel
import io.mo.xiaoaiplug.ui.nav.CardHorizontalPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SpeakScreen(vm: ConfigViewModel, bottomInset: Dp, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()

    SubScreen(title = "语音播报", bottomInset = bottomInset, onBack = onBack) {
        item { SmallTitle("播报") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.speakAnswer,
                    onCheckedChange = { on -> vm.update { it.copy(speakAnswer = on) } },
                    title = "用小爱自己的声音念出答案",
                    summary = "复用小爱的 TTS，音色跟原来一致"
                )
                Text(
                    text = "关闭时小爱回复为静音",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 8.dp)
                )
            }
        }

        item { SmallTitle("开口速度") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.streamSpeech,
                    onCheckedChange = { on -> vm.update { it.copy(streamSpeech = on) } },
                    title = "边生成边播报（实验性）",
                    summary = "答案还在生成时，整句一出来就开始念",
                    enabled = config.speakAnswer
                )
                Text(
                    text = "用的是小爱自己的流式语音，需要小爱界面在前台；条件不满足时自动退回整段播报。" +
                        "模型调用工具前说的话（如「我查一下」）也会被念出来。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 8.dp)
                )
            }
        }

        item { SmallTitle("长回答") }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = config.speakBrief,
                    onCheckedChange = { on -> vm.update { it.copy(speakBrief = on) } },
                    title = "长回答只念开头",
                    summary = "只念前一两句，再提示「详细内容请看屏幕」",
                    // 不出声的时候这一项没有意义
                    enabled = config.speakAnswer
                )
                Text(
                    text = "卡片上始终是完整答案；想听全文，点卡片上的喇叭重播即可。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 8.dp)
                )
            }
        }
    }
}

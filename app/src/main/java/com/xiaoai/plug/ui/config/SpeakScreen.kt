package com.xiaoai.plug.ui.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.nav.CardHorizontalPadding
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
                    text = "关掉的话只出卡片、全程不出声 —— 悬浮窗和息屏场景下基本等于没有回应，" +
                        "除非你只在 App 里用，否则建议保持开启。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 8.dp)
                )
            }
        }
    }
}

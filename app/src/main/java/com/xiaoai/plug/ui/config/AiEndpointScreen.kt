package com.xiaoai.plug.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoai.plug.ui.ConfigViewModel
import com.xiaoai.plug.ui.TestState
import com.xiaoai.plug.ui.nav.CardContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AiEndpointScreen(vm: ConfigViewModel, bottomInset: Dp, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }

    SubScreen(title = "AI 接入", bottomInset = bottomInset, onBack = onBack) {
        item { SmallTitle("端点") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                TextField(
                    value = config.endpoint,
                    onValueChange = { v -> vm.update { it.copy(endpoint = v) } },
                    label = "API 地址",
                    useLabelAsPlaceholder = false,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                Text(
                    text = "OpenAI 兼容的 /chat/completions，可省略末尾路径。例：https://api.openai.com/v1",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                TextField(
                    value = config.apiKey,
                    onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
                    label = "API Key",
                    useLabelAsPlaceholder = false,
                    // 密钥默认打码：这一页随时可能被人瞄到，也可能被截图发出去问问题
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = if (keyVisible) "隐藏" else "显示",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )

                TextField(
                    value = config.model,
                    onValueChange = { v -> vm.update { it.copy(model = v) } },
                    label = "模型名",
                    useLabelAsPlaceholder = false,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
        }

        item { SmallTitle("系统提示词") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                TextField(
                    value = config.systemPrompt,
                    onValueChange = { v -> vm.update { it.copy(systemPrompt = v) } },
                    label = "系统提示词（可留空）",
                    useLabelAsPlaceholder = false,
                    minLines = 3,
                    // 不封顶的话长提示词会把输入框撑到几屏高，下面的测试连接按钮就够不着了
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
        }

        item { SmallTitle("连通性") }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = CardContentPadding) {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = if (testState is TestState.Running) "测试中…" else "测试连接",
                        onClick = { vm.testConnection() },
                        enabled = testState !is TestState.Running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val (msg, color) = when (val s = testState) {
                        is TestState.Ok -> "通了，模型回复：${s.reply}" to Color(0xFF34C759)
                        is TestState.Failed -> "失败：${s.message}" to Color(0xFFFF3B30)
                        else -> null to Color.Unspecified
                    }
                    if (msg != null) {
                        Text(
                            text = msg,
                            color = color,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    Text(
                        text = "测试时会临时关掉全部工具，只验证地址、密钥和模型名，不会真去动设备。",
                        fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

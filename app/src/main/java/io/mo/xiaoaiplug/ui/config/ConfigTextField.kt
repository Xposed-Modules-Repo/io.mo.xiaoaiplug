package io.mo.xiaoaiplug.ui.config

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 绑配置项的输入框。
 *
 * **输入框显示的是自己手里的文字,不是配置 StateFlow 里那份。** 配置流经过 collect 才回到
 * 界面,比按键晚一拍;直接绑它的话,快速打字和中文输入法的组字会被旧值盖回去 —— 吞字、
 * 光标跳到末尾。这里敲一个字先改本地,再交给 [onValueChange] 去更新配置。
 *
 * 外部的改动(配置首次读上来、「恢复默认」)只在**没获得焦点**时接进来:获得焦点期间
 * 配置流里出现的只可能是自己刚发出去的值的回声,接了反而会把正在打的字冲掉。
 * 所以会改这个字段的按钮,点击时要先清焦点(见各处的 clearFocus)。
 */
@Composable
fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    var text by remember { mutableStateOf(value) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(value, focused) {
        if (!focused && text != value) text = value
    }

    TextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        modifier = modifier,
        label = label,
        useLabelAsPlaceholder = useLabelAsPlaceholder,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        interactionSource = interactionSource
    )
}

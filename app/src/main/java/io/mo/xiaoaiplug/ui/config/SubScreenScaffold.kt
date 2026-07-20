package com.xiaoai.plug.ui.config

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.xiaoai.plug.ui.nav.PageScaffold
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页外壳：就是 [PageScaffold] 加一个返回按钮，标题同样是可折叠大标题。
 */
@Composable
fun SubScreen(
    title: String,
    bottomInset: Dp,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    PageScaffold(
        title = title,
        bottomInset = bottomInset,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
        },
        content = content
    )
}

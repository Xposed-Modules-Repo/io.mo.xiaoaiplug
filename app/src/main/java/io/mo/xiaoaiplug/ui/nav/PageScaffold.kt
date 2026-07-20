package io.mo.xiaoaiplug.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

/**
 * 每一页的统一外壳：miuix Scaffold + 可折叠大标题 TopAppBar + 滚动内容。
 *
 * 之前这里是手搓的 LazyColumn + 一个 Text 当标题，结果标题直接压在状态栏上 ——
 * 因为 `enableEdgeToEdge()` 把内容铺到了系统栏底下，而手搓的布局没人管 inset。
 * [TopAppBar] 的 `defaultWindowInsetsPadding` 默认就是 true，交给它处理才对。
 *
 * @param bottomInset 外层 Scaffold 给出的底部留白（悬浮底栏占的高度）。内容是从底栏
 *   **下面**穿过去的（玻璃能看到东西的前提），不留这段最后一项会被永久压住。
 */
@Composable
fun PageScaffold(
    title: String,
    bottomInset: Dp,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = title,
                // largeTitle 默认等于 title，滚动时会从大标题收成小标题
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = bottomInset + 12.dp
            ),
            content = content
        )
    }
}

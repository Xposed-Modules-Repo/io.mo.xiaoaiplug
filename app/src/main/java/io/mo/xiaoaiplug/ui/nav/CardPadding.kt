package io.mo.xiaoaiplug.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 放进 [top.yukonga.miuix.kmp.basic.Card] 的**自定义**内容要用的内边距。
 *
 * 坑在这儿：miuix 的 `CardDefaults.InsideMargin` 是 **PaddingValues(0.dp)** ——
 * Card 本身不给任何内边距。`SwitchPreference` / `ArrowPreference` 这些组件自带
 * `BasicComponentDefaults.InsideMargin`(16dp) 所以看着正常，但凡是自己往 Card 里塞的
 * Row/Text/TextField，文字会直接顶到卡片边缘、被圆角裁掉（记录页和 AI 接入页就是这么废的）。
 *
 * 横向取 16dp 是为了跟 Preference 行左对齐，不然同一张卡里两种行会错位。
 */
val CardContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** 混排卡片（既有 Preference 行又有自定义内容）里，只给自定义那部分用的横向内边距。 */
val CardHorizontalPadding = 16.dp

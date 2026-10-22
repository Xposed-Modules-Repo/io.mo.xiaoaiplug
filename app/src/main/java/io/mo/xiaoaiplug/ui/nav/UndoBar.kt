package io.mo.xiaoaiplug.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mo.xiaoaiplug.ui.ConfigViewModel.UndoOffer
import io.mo.xiaoaiplug.ui.theme.LocalIsDark
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val BarShape = RoundedCornerShape(16.dp)

/**
 * 左滑删除后的「已删除 · 撤销」条。
 *
 * 跟 [BottomToast] 不同,它是要用户**伸手去点**的,所以有底板、有明确的按钮;
 * 而 BottomToast 只是一行看完就走的小字。计时和撤销/落库逻辑在 ConfigViewModel.offerUndo,
 * 这里只负责显示。
 */
@Composable
fun BoxScope.UndoBar(
    offer: UndoOffer?,
    bottomInset: Dp,
    onUndo: () -> Unit
) {
    // 同 BottomToast:offer 置空后淡出还要拿最后一条文案渲染
    var last by remember { mutableStateOf("") }
    LaunchedEffect(offer) { if (offer != null) last = offer.message }

    val rimColor =
        if (LocalIsDark.current) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)

    AnimatedVisibility(
        visible = offer != null,
        enter = fadeIn(tween(180)) + slideInVertically(spring(stiffness = 600f)) { it / 2 },
        exit = fadeOut(tween(300)),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + 12.dp, start = 16.dp, end = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(BarShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHighest, BarShape)
                .border(1.dp, rimColor, BarShape)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = last,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp)
            )
            Text(
                text = "撤销",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(BarShape)
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }
    }
}

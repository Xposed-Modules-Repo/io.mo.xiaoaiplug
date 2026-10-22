package io.mo.xiaoaiplug.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.xiaoaiplug.ui.ConfigViewModel.ConfirmRequest
import io.mo.xiaoaiplug.ui.liquid.lens
import io.mo.xiaoaiplug.ui.liquid.vibrancy
import io.mo.xiaoaiplug.ui.theme.LocalIsDark
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardShape = RoundedCornerShape(28.dp)

/** 删除类操作的红色。跟记录页「失败」、主页红点是同一个色。 */
private val DestructiveColor = Color(0xFFFF3B30)

/**
 * 删不可恢复的东西前的二次确认(清空记录、删除 MCP 服务)。
 *
 * 和 [io.mo.xiaoaiplug.ui.home.UpdateDialog] 一样是贴在 AppRoot 内容层之上的 overlay,
 * 卡片采 [backdrop] 做毛玻璃 —— 所以只能画在 layerBackdrop 那层**外面**,
 * 各页面通过 ConfigViewModel.requestConfirm 发请求,不能在页面里自己画。
 */
@Composable
fun ConfirmDialog(
    request: ConfirmRequest?,
    backdrop: Backdrop,
    onResult: (Boolean) -> Unit
) {
    // 请求置空后淡出动画还要拿它渲染,记住最后一条非空的
    var last by remember { mutableStateOf<ConfirmRequest?>(null) }
    LaunchedEffect(request) { if (request != null) last = request }
    val shown = request ?: last

    // 只在显示时才组合:BackHandler 后注册的优先,这样返回键先关弹窗,不会越过它退掉二级页
    if (request != null) BackHandler { onResult(false) }

    AnimatedVisibility(
        visible = request != null,
        enter = fadeIn(spring(stiffness = 500f)),
        exit = fadeOut(spring(stiffness = 700f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 点遮罩 = 取消。确认必须是明确点到那颗红色按钮
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onResult(false) }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = request != null,
                enter = fadeIn(spring(stiffness = 500f)) +
                    scaleIn(spring(stiffness = 380f), initialScale = 0.85f),
                exit = fadeOut(spring(stiffness = 700f)) +
                    scaleOut(spring(stiffness = 700f), targetScale = 0.9f)
            ) {
                shown?.let { ConfirmCard(it, backdrop, onResult) }
            }
        }
    }
}

@Composable
private fun ConfirmCard(
    request: ConfirmRequest,
    backdrop: Backdrop,
    onResult: (Boolean) -> Unit
) {
    val isDark = LocalIsDark.current
    val isGlass = isRuntimeShaderSupported()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val cardTint =
        if (isGlass) surfaceColor.copy(alpha = if (isDark) 0.55f else 0.6f) else surfaceColor
    val rimColor =
        if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp)
            .width(320.dp)
            .clip(CardShape)
            .then(
                if (isGlass) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { CardShape },
                        effects = {
                            padding = maxOf(padding, 24.dp.toPx())
                            vibrancy()
                            blur(12.dp.toPx(), 12.dp.toPx())
                            lens(
                                refractionHeight = 12.dp.toPx(),
                                refractionAmount = 12.dp.toPx()
                            )
                        },
                        onDrawSurface = { drawRect(cardTint) }
                    )
                } else {
                    Modifier.background(cardTint, CardShape)
                }
            )
            .border(1.dp, rimColor, CardShape)
            // 吃掉落在卡片上的点击,免得穿透到遮罩把自己关了
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(24.dp)
    ) {
        Column {
            Text(
                text = request.title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = request.message,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = { onResult(false) },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = request.confirmLabel,
                    onClick = { onResult(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        color = DestructiveColor,
                        textColor = Color.White
                    )
                )
            }
        }
    }
}

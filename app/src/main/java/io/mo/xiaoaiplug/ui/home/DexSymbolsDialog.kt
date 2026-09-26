package io.mo.xiaoaiplug.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.xiaoaiplug.config.DexStatusInfo
import io.mo.xiaoaiplug.ui.liquid.lens
import io.mo.xiaoaiplug.ui.liquid.vibrancy
import io.mo.xiaoaiplug.ui.theme.LocalIsDark
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DialogShape = RoundedCornerShape(28.dp)

@Composable
fun DexSymbolsDialog(
    visible: Boolean,
    dexStatus: DexStatusInfo,
    backdrop: Backdrop,
    isScanning: Boolean = false,
    onRescan: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalIsDark.current
    val isGlass = isRuntimeShaderSupported()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val cardTint = if (isGlass) surfaceColor.copy(alpha = if (isDark) 0.65f else 0.7f) else surfaceColor
    val rimColor = if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.08f)
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    var searchKeyword by remember { mutableStateOf("") }

    val detailedList = remember(dexStatus.symbols) {
        dexStatus.symbols.getDetailedList()
    }

    val filteredList = remember(detailedList, searchKeyword) {
        if (searchKeyword.isBlank()) {
            detailedList
        } else {
            detailedList.filter {
                it.name.contains(searchKeyword, ignoreCase = true) ||
                    it.resolvedClass.contains(searchKeyword, ignoreCase = true) ||
                    it.key.contains(searchKeyword, ignoreCase = true) ||
                    it.description.contains(searchKeyword, ignoreCase = true)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring(stiffness = 500f)),
        exit = fadeOut(spring(stiffness = 700f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(spring(stiffness = 500f)) + scaleIn(spring(stiffness = 380f), initialScale = 0.85f),
                exit = fadeOut(spring(stiffness = 700f)) + scaleOut(spring(stiffness = 700f), targetScale = 0.9f)
            ) {
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxCardHeight)
                        .clip(DialogShape)
                        .then(
                            if (isGlass) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { DialogShape },
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
                                Modifier.background(cardTint, DialogShape)
                            }
                        )
                        .border(1.dp, rimColor, DialogShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        // 顶栏标题与操作按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "DEX搜索结果",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "DexKit 动态分析与版本适配",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onRescan,
                                    enabled = !isScanning
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Refresh,
                                        contentDescription = "重新扫描",
                                        tint = if (isScanning) MiuixTheme.colorScheme.onBackgroundVariant else MiuixTheme.colorScheme.primary
                                    )
                                }

                                IconButton(onClick = {
                                    val json = dexStatus.symbols.toJson().toString(2)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("symbols_json", json))
                                    Toast.makeText(context, "已复制全部符号映射 JSON", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(
                                        imageVector = MiuixIcons.Copy,
                                        contentDescription = "复制全部",
                                        tint = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 运行状态概览卡片
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (isScanning || !dexStatus.homeOk) Color(0xFFFF9500) else Color(0xFF34C759))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (isScanning) "正在分析 DexKit 指纹…" else "指纹命中 ${dexStatus.matchedCount}/${io.mo.xiaoaiplug.hook.dex.DexScanResult.REQUIRED_KEYS.size}",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        text = if (isScanning) "分析中" else if (dexStatus.time > 0) "${dexStatus.durationMs}ms" else "尚未扫描",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(Modifier.height(4.dp))

                                val sourceText = if (isScanning) "后台扫描中" else dexStatus.source.ifBlank { "尚未扫描" }
                                val timeText = if (dexStatus.time > 0) {
                                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(dexStatus.time))
                                } else "待小爱首次运行同步"

                                Text(
                                    text = "来源: $sourceText · $timeText",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant
                                )
                                Text(
                                    text = when {
                                        dexStatus.pending -> "扫描结果待应用，请重启小爱"
                                        dexStatus.hostLoaded -> "宿主已读取；未命中项不启用"
                                        else -> "尚未收到宿主加载确认"
                                    },
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant
                                )
                                if (dexStatus.summary.isNotBlank()) Text(
                                    text = dexStatus.summary,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 搜索过滤框
                        TextField(
                            value = searchKeyword,
                            onValueChange = { searchKeyword = it },
                            label = "搜索符号名 / 类名",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        // 符号列表 (使用带有滚动限制的 Column，避免 LazyColumn 与 heightIn 约束冲突)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (filteredList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "无匹配符号",
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant
                                    )
                                }
                            } else {
                                filteredList.forEach { item ->
                                    SymbolItemRow(item, dexStatus.states[item.key])
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 底部操作按钮栏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onRescan,
                                enabled = !isScanning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isScanning) "扫描中…" else "重新扫描")
                            }

                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("关闭")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolItemRow(
    item: io.mo.xiaoaiplug.hook.dex.SymbolDetail,
    scan: io.mo.xiaoaiplug.hook.dex.SymbolScan?
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = item.resolvedClass.isNotBlank()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(item.name, item.resolvedClass))
                Toast.makeText(context, "已复制: ${item.resolvedClass}", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.key,
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            }
            Text(
                text = item.description,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            if (!scan?.detail.isNullOrBlank()) Text(
                text = scan!!.detail,
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = (scan?.state ?: io.mo.xiaoaiplug.hook.dex.SymbolState.DEFAULT).label +
                    (scan?.let { " · ${it.durationMs}ms" } ?: ""),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Text(
                text = item.resolvedClass.ifBlank { "未解析（不启用）" },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

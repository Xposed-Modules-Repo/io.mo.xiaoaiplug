package io.mo.xiaoaiplug.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.mo.xiaoaiplug.config.McpClient
import io.mo.xiaoaiplug.config.McpServerConfig
import io.mo.xiaoaiplug.config.McpTool
import io.mo.xiaoaiplug.ui.ConfigViewModel
import io.mo.xiaoaiplug.ui.nav.CardContentPadding
import io.mo.xiaoaiplug.ui.nav.CardHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun McpScreen(vm: ConfigViewModel, bottomInset: Dp, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()

    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var isCreating by remember { mutableStateOf(false) }

    // 工具列表查看 Modal/Dialog 状态
    var viewingToolsServer by remember { mutableStateOf<McpServerConfig?>(null) }

    val servers = remember(config.mcpServersRaw) { config.mcpServers }

    fun saveServer(server: McpServerConfig) {
        val list = servers.toMutableList()
        val index = list.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            list[index] = server
        } else {
            list.add(server)
        }
        McpClient.clearCache(server.id)
        vm.update { it.copy(mcpServersRaw = McpServerConfig.toJsonArray(list)) }
    }

    fun deleteServer(id: String) {
        McpClient.clearCache(id)
        // 从最新配置里删,不用这一帧捕获的 servers:确认弹窗点下去时可能已经隔了几次改动
        vm.update { c ->
            c.copy(mcpServersRaw = McpServerConfig.toJsonArray(c.mcpServers.filterNot { it.id == id }))
        }
    }

    fun toggleServer(server: McpServerConfig, enabled: Boolean) {
        saveServer(server.copy(enabled = enabled))
    }

    SubScreen(title = "MCP 服务", bottomInset = bottomInset, onBack = onBack) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text = "Model Context Protocol (MCP) 允许小爱同学接入外部 HTTP/SSE 工具服务。添加服务后，AI 将能动态感知并调用其提供的工具能力。",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallTitle("已配置服务 (${servers.size})")
                TextButton(
                    text = "+ 添加服务",
                    onClick = {
                        isCreating = true
                        editingServer = McpServerConfig(name = "", url = "")
                    }
                )
            }
        }

        if (servers.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "暂未配置 MCP 服务",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                isCreating = true
                                editingServer = McpServerConfig(name = "", url = "")
                            },
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text("添加第一个 MCP 服务")
                        }
                    }
                }
            }
        } else {
            items(servers, key = { it.id }) { server ->
                McpServerItemCard(
                    server = server,
                    onToggle = { enabled -> toggleServer(server, enabled) },
                    onEdit = {
                        isCreating = false
                        editingServer = server
                    },
                    onDelete = {
                        // 地址和请求头里的 token 一起删掉,没有撤销,先问一句
                        vm.requestConfirm(
                            title = "删除 MCP 服务？",
                            message = "「${server.name.ifBlank { "未命名 MCP 服务" }}」的地址和请求头会一起删除，且无法恢复。",
                            confirmLabel = "删除"
                        ) { deleteServer(server.id) }
                    },
                    onViewTools = { viewingToolsServer = server }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 编辑 / 创建对话框
    editingServer?.let { server ->
        McpEditDialog(
            server = server,
            isCreating = isCreating,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                saveServer(updated)
                editingServer = null
            }
        )
    }

    // 查看工具列表对话框
    viewingToolsServer?.let { server ->
        McpToolsDialog(
            server = server,
            onDismiss = { viewingToolsServer = null }
        )
    }
}

@Composable
private fun McpServerItemCard(
    server: McpServerConfig,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onViewTools: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = server.enabled,
                onCheckedChange = onToggle,
                title = server.name.ifBlank { "未命名 MCP 服务" },
                summary = "${McpServerConfig.transportLabel(server.transportType)} · ${server.url}"
            )

            if (server.headers.isNotBlank()) {
                Text(
                    text = "🔑 已配置自定义请求头",
                    fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding, vertical = 2.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = "查看工具",
                    onClick = onViewTools
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        text = "编辑",
                        onClick = onEdit
                    )
                    TextButton(
                        text = "删除",
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun McpEditDialog(
    server: McpServerConfig,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var transportType by remember { mutableStateOf(server.transportType) }
    var url by remember { mutableStateOf(server.url) }
    var headers by remember { mutableStateOf(server.headers) }

    val transportIndex = McpServerConfig.TRANSPORT_KEYS.indexOf(transportType).coerceAtLeast(0)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            insideMargin = CardContentPadding
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (isCreating) "添加 MCP 服务" else "编辑 MCP 服务",
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(20.dp))

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "显示名称 (如: 天气 MCP)",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                WindowDropdownPreference(
                    title = "传输类型",
                    items = McpServerConfig.TRANSPORT_LABELS,
                    selectedIndex = transportIndex,
                    onSelectedIndexChange = { idx ->
                        transportType = McpServerConfig.TRANSPORT_KEYS[idx]
                    }
                )
                Spacer(Modifier.height(16.dp))

                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "URL (如: http://127.0.0.1:8787/mcp)",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                TextField(
                    value = headers,
                    onValueChange = { headers = it },
                    label = "自定义请求头 (每行 Key: Value，如 Authorization: Bearer xxx)",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        enabled = name.isNotBlank() && url.isNotBlank(),
                        onClick = {
                            onSave(
                                server.copy(
                                    name = name.trim(),
                                    transportType = transportType,
                                    url = url.trim(),
                                    headers = headers.trim()
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolsDialog(
    server: McpServerConfig,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var toolsList by remember { mutableStateOf<List<McpTool>>(emptyList()) }

    fun loadTools() {
        isLoading = true
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val tools = McpClient.listTools(server)
                withContext(Dispatchers.Main) {
                    toolsList = tools
                    isLoading = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: "连接 MCP 服务失败"
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(server.id) {
        loadTools()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            insideMargin = CardContentPadding
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name.ifBlank { "MCP 服务" },
                            fontSize = MiuixTheme.textStyles.title3.fontSize,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "工具列表 (${McpServerConfig.transportLabel(server.transportType)})",
                            fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                            color = MiuixTheme.colorScheme.onBackgroundVariant
                        )
                    }

                    IconButton(onClick = { loadTools() }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新",
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "正在连接 MCP 服务获取工具列表...",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                    }
                    errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "❌ 获取工具列表失败",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = errorMessage.orEmpty(),
                                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                    }
                    toolsList.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "该 MCP 服务未提供任何工具",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "共找到 ${toolsList.size} 个可用工具 (点击可展开查看详情)：",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(toolsList, key = { it.name }) { tool ->
                                McpToolItemCard(tool)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolItemCard(tool: McpTool) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.name,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = if (expanded) "▲" else "▼",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (tool.description.isNotBlank()) {
                        Text(
                            text = tool.description,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant
                        )
                    }

                    // 参数细节
                    val props = tool.inputSchema.optJSONObject("properties")
                    val requiredArr = tool.inputSchema.optJSONArray("required")
                    val requiredSet = mutableSetOf<String>()
                    if (requiredArr != null) {
                        for (i in 0 until requiredArr.length()) {
                            requiredSet.add(requiredArr.getString(i))
                        }
                    }

                    if (props != null && props.length() > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "参数 Specs:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))

                        val paramKeys = mutableListOf<String>()
                        val keysIter = props.keys()
                        while (keysIter.hasNext()) {
                            paramKeys.add(keysIter.next())
                        }

                        paramKeys.forEach { pName ->
                            val pObj = props.optJSONObject(pName)
                            val pType = pObj?.optString("type", "string") ?: "string"
                            val pDesc = pObj?.optString("description", "") ?: ""
                            val isReq = pName in requiredSet

                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "• $pName",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = " ($pType${if (isReq) ", 必填" else ""})${if (pDesc.isNotBlank()) " - $pDesc" else ""}",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

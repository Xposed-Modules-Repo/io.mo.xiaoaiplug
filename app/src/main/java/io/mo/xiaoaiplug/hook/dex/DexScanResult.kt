package io.mo.xiaoaiplug.hook.dex

/** Unresolved symbols are disabled, never filled from defaults or older scans. */
enum class SymbolState(val label: String) {
    MATCHED("验证命中"), DEFAULT("尚未验证，已停用"),
    NOT_FOUND("未命中，已停用"), AMBIGUOUS("候选不唯一，已停用"),
    ERROR("扫描失败，已停用")
}

data class SymbolScan(val state: SymbolState, val durationMs: Long = 0L, val detail: String = "")

data class DexScanResult(
    val symbols: TargetSymbols = TargetSymbols(),
    val states: Map<String, SymbolScan> = emptyMap(),
    val error: String? = null
) {
    val matchedCount: Int get() = symbols.getDetailedList().count {
        it.key in REQUIRED_KEYS && states[it.key]?.state == SymbolState.MATCHED && it.resolvedClass.isNotBlank()
    }
    val cacheable: Boolean get() = error == null && matchedCount == REQUIRED_KEYS.size
    val summary: String get() = when {
        error != null -> "扫描失败：$error"
        cacheable -> "全部符号验证通过"
        matchedCount == 0 -> "未匹配到符号，相关功能已停用"
        else -> "命中 $matchedCount/${REQUIRED_KEYS.size}，未命中项停用，不使用旧缓存"
    }

    companion object {
        val REQUIRED_KEYS = setOf(
            "rnCardClass", "asrProcessorClass", "bridgeClass", "audioTrackManagerClass",
            "toastStreamPlayerClass", "agentActionClass", "toastOperationClass",
            "uiNavOperationClass", "speakContentClass", "intentUtilsClass",
            "chatDbManagerClass", "flowToastCardClass", "flowControllerClass", "floatManagerClass",
            "ttsBridgeClass", "uiNavMethodName", "operationManagerClass", "intentUtilsWrapperClass",
            "settingsSwitchClass"
        )

        fun failed(message: String) = DexScanResult(states = REQUIRED_KEYS.associateWith {
            SymbolScan(SymbolState.ERROR)
        }, error = message)
    }
}

/** Human-readable evidence for the app log and the diagnostic runner. */
fun DexScanResult.diagnosticText(): String = buildString {
    appendLine(summary)
    symbols.getDetailedList().forEach { item ->
        val state = states[item.key] ?: SymbolScan(SymbolState.DEFAULT)
        appendLine("${item.name} (${item.key}): ${state.state.label} · ${state.durationMs}ms")
        if (state.state == SymbolState.MATCHED) appendLine("  ${item.resolvedClass}")
        if (state.detail.isNotBlank()) appendLine("  ${state.detail}")
    }
}

/** Multiple overloads in the same class still identify one class. */
internal fun uniqueCandidate(names: List<String>): Pair<String?, SymbolState> {
    val candidates = names.filter { it.isNotBlank() }.distinct()
    return when (candidates.size) {
        0 -> null to SymbolState.NOT_FOUND
        1 -> candidates.single() to SymbolState.MATCHED
        else -> null to SymbolState.AMBIGUOUS
    }
}

data class ApkIdentity(val path: String, val version: Long, val modified: Long, val length: Long) {
    val valid: Boolean get() = path.isNotBlank() && modified > 0L && length > 0L
}

data class DexResolution(
    val scan: DexScanResult,
    val source: String,
    val durationMs: Long,
    val artifact: DexCacheArtifact? = null
)

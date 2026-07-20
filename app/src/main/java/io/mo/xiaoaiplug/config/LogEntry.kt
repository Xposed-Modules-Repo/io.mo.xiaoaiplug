package io.mo.xiaoaiplug.config

/**
 * 一条运行记录。
 *
 * 这套东西的存在理由:整条链路(拦截 → 取问话 → 调模型 → 跑工具 → 回填卡片 → TTS)
 * 横跨两个进程,出问题时唯一的线索是 `adb logcat -s XiaoAiProbe` —— 手机不在电脑边上
 * 就等于没有。落盘之后「记录」页就是事后排查的现场。
 */
data class LogEntry(
    val id: Long = 0L,
    val time: Long,
    val type: String,
    /** 摘要行:对话记用户问话,工具记工具名,错误记错误类型。 */
    val title: String,
    /** 完整内容。工具的返回可能很长,写入前由 [LogStore] 截断。 */
    val detail: String,
    /** 耗时,毫秒;不适用时为 -1。 */
    val durationMs: Long = -1L,
    val ok: Boolean = true
) {
    companion object {
        const val TYPE_CHAT = "CHAT"
        const val TYPE_TOOL = "TOOL"
        const val TYPE_ERROR = "ERROR"

        val ALL_TYPES = listOf(TYPE_CHAT, TYPE_TOOL, TYPE_ERROR)
    }
}

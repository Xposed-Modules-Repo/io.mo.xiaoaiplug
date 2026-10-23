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
    val ok: Boolean = true,
    /** 接口返回的思考，独立保存；旧记录和未提供思考的模型为空。 */
    val thinking: String = ""
) {
    /** 在跨进程传输前限制大小，避免长思考导致整条记录丢失。 */
    fun bounded(): LogEntry = copy(
        title = title.take(MAX_DETAIL),
        detail = detail.take(MAX_DETAIL),
        thinking = if (thinking.length > MAX_THINKING) {
            thinking.take(MAX_THINKING - TRUNCATED.length) + TRUNCATED
        } else thinking
    )

    companion object {
        private const val MAX_DETAIL = 8000
        internal const val MAX_THINKING = 64_000
        private const val TRUNCATED = "\n\n[思考内容过长，后续已截断]"

        const val TYPE_CHAT = "CHAT"
        const val TYPE_TOOL = "TOOL"
        const val TYPE_ERROR = "ERROR"

        val ALL_TYPES = listOf(TYPE_CHAT, TYPE_TOOL, TYPE_ERROR)
    }
}

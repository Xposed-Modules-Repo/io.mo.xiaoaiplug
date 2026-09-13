package io.mo.xiaoaiplug.hook

/** 用对话身份关联 ASR 和改写后的 query；只对没有 ASR 的重复派发使用短时间去重。 */
internal class ReplyTurns(private val now: () -> Long) {
    data class Turn(val key: String, val text: String, val createdAt: Long,
        var owned: Boolean = false, var started: Boolean = false)

    private val dialogs = LinkedHashMap<String, Turn>()
    private var sequence = 0L
    private var current: Turn? = null

    @Synchronized
    fun capture(dialogId: String, text: String, asr: Boolean): Turn {
        dialogs[dialogId]?.let { return it }
        val time = now()
        val active = current
        val turn = if (!asr && active != null && active.text == text && time - active.createdAt < 1_500L) {
            active
        } else {
            Turn("turn-${++sequence}", text, time).also { current = it }
        }
        dialogs[dialogId] = turn
        // 保留旧轮身份以识别迟到回调，同时限制长驻进程的索引大小。
        if (dialogs.size > 256) dialogs.remove(dialogs.keys.first())
        return turn
    }

    @Synchronized fun claim(key: String) { current?.takeIf { it.key == key }?.owned = true }
    @Synchronized fun tryStart(key: String): Boolean {
        val turn = current?.takeIf { it.key == key && it.owned && !it.started } ?: return false
        turn.started = true
        return true
    }
    @Synchronized fun isCurrent(key: String): Boolean = current?.key == key
    @Synchronized fun keyFor(dialogId: String): String? = dialogs[dialogId]?.key
    @Synchronized fun ownsCurrent(): Boolean = current?.owned == true
    @Synchronized fun isCurrentDialog(dialogId: String): Boolean =
        current != null && dialogs[dialogId] === current

    /** RN 可能使用新 dialogId；已知旧轮的 id 绝不能归到当前轮。 */
    @Synchronized
    fun attachOutput(dialogId: String): String? {
        val turn = dialogs[dialogId] ?: current?.takeIf { it.owned }?.also { dialogs[dialogId] = it }
        return turn?.key
    }
}

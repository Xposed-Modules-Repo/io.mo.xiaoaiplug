package io.mo.xiaoaiplug.config

/** 每次 chat 独占；流式回调给的是本轮累计值，工具轮结束后再追加下一轮。 */
internal class ThinkingTrace {
    private val completed = ArrayList<String>()
    private var current = ""

    fun update(text: String) {
        current = text
    }

    fun nextRound() {
        if (current.isNotBlank()) completed.add(current)
        current = ""
    }

    fun text(): String = (completed + current).filter { it.isNotBlank() }.joinToString("\n\n")
}

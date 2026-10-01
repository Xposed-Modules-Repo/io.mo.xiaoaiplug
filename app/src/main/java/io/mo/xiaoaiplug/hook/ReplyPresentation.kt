package io.mo.xiaoaiplug.hook

/** 每次问话的显示状态；工具轮撤回正文时保留已收到的思考。 */
internal class ReplyPresentation(private val showThinking: Boolean) {
    private val completedThinking = ArrayList<String>()
    private var thinking = ""
    private var answer = ""
    private var complete = false
    private var expanded = true

    /** 思考区域和原生答案文本分别渲染；text 始终只含答案。 */
    data class Display(
        val text: String,
        val title: String = "",
        val thinking: String = "",
        val expanded: Boolean = true,
        val waiting: Boolean = false
    )

    @Synchronized fun onThinking(text: String) { thinking = if (showThinking) text else "" }
    @Synchronized fun onAnswer(text: String) { answer = text }
    @Synchronized fun onComplete(text: String) { answer = text; complete = true }
    @Synchronized fun onCancel() { complete = true }
    @Synchronized fun onDiscard() {
        if (thinking.isNotBlank()) completedThinking.add(thinking)
        thinking = ""
        answer = ""
    }

    @Synchronized fun toggleThinking() { expanded = !expanded }

    @Synchronized fun display(fallback: String): Display {
        val thoughts = (completedThinking + thinking).filter { it.isNotBlank() }.joinToString("\n\n")
        val body = answer.ifBlank { fallback }
        val waiting = !complete && answer.isBlank()
        if (thoughts.isBlank()) return Display(body, waiting = waiting)
        val title = "${if (expanded) "▾" else "▸"} ${if (complete) "思考过程" else "思考中…"}"
        return Display(body, title, thoughts, expanded, waiting)
    }

    fun text(fallback: String): String = display(fallback).text
}

package io.mo.xiaoaiplug.hook

/**
 * 「边生成边念」的切句逻辑:把流式推上来的正文切成能交给 TTS 的整句。
 * 纯逻辑,不碰小爱,见 SpeechChunkerTest。
 *
 * 输入是**本轮累计正文**(跟 AiClient.StreamSink.onDelta 一样单调增长),输出是这次新增、
 * 可以念的那一段原文(含 markdown,清理交给调用方)。一句话没说完不交出去 —— TTS 拿到半句
 * 会在奇怪的地方停顿;工具标记(`<tool_call>` 之类)之后的内容也不交。
 *
 * 长度控制和整段播报保持一致:
 *  - 普通模式念到 [maxChars] 左右为止;
 *  - [brief](长回答只念开头)念到 [BriefSpeech.TARGET_CHARS] 左右为止;
 * 到了上限后面的先压住,等定稿([onComplete])再决定:剩一小截就念完,否则补一句「详细内容请看屏幕」。
 */
internal class SpeechChunker(private val brief: Boolean, private val maxChars: Int) {

    /** 当前这一轮的累计正文。 */
    private var roundText = ""

    /** [roundText] 里已经交出去的长度。 */
    private var consumed = 0

    /** 跨轮累计已交出去的字数(工具轮前模型说的「我查一下」也算)。 */
    private var spoken = 0

    /** 到了长度上限,后面的先不念,等定稿再说。 */
    private var held = false

    /** 一句话念到这里就不再往下接;到了它,后面的句子先压住。 */
    private val softLimit = if (brief) BriefSpeech.TARGET_CHARS else maxChars

    /** 加上下一句会超过它就压住(哪怕是第一句),定稿时按整段规则处理。 */
    private val hardLimit = if (brief) BriefSpeech.MAX_CHARS else maxChars

    /** 流式期间:返回这次新增的、可以念的整句;还不能念([canSpeak] 为 false)时只记下进度。 */
    @Synchronized
    fun onDelta(text: String, canSpeak: Boolean): String {
        resync(text)
        if (!canSpeak || held) return ""
        val tag = text.indexOf('<', consumed)
        return emitSentences(if (tag >= 0) tag else text.length, final = false)
    }

    /** 这一轮其实是工具调用:下一轮的正文从头开始。已经念出去的收不回,也不必收。 */
    @Synchronized
    fun onDiscard() {
        roundText = ""
        consumed = 0
    }

    /**
     * 定稿:返回剩下所有要念的。
     *
     * 定稿和流式推上来的对不上时(硬失败的提示语、清理掉了标记),已念的部分没法收回,
     * 定稿当作新的一段从头念。
     */
    @Synchronized
    fun onComplete(finalText: String): String {
        if (consumed > finalText.length || !finalText.startsWith(roundText.substring(0, consumed))) {
            consumed = 0
        }
        roundText = finalText
        val out = StringBuilder()
        if (!held) out.append(emitSentences(finalText.length, final = true))
        val rest = finalText.substring(consumed)
        if (rest.isBlank()) return out.toString()
        when {
            // 剩一小截:为它补一句「请看屏幕」反而更长
            rest.trim().length < BriefSpeech.TAIL_TOLERANCE -> out.append(take(finalText.length))
            // 一句都还没念(第一句就长得离谱):退回整段播报的切法
            spoken == 0 -> out.append(
                if (brief) BriefSpeech.cut(rest)
                else rest.take(maxChars).trimEnd() + "……" + BriefSpeech.NOTICE
            ).also { consumed = finalText.length }
            else -> out.append(BriefSpeech.NOTICE).also { consumed = finalText.length }
        }
        return out.toString()
    }

    /** 文本跟之前的对不上(极少见):已念的收不回,只把进度收到新文本长度以内。 */
    private fun resync(text: String) {
        if (consumed > text.length || !text.startsWith(roundText.substring(0, consumed))) {
            consumed = minOf(consumed, text.length)
        }
        roundText = text
    }

    /** 从 [consumed] 起逐句交出,直到 [limit] 或碰到长度上限。[final] 时最后半句也算一句。 */
    private fun emitSentences(limit: Int, final: Boolean): String {
        val out = StringBuilder()
        while (consumed < limit) {
            var end = nextBoundary(roundText, consumed, limit)
            if (end < 0) {
                if (!final) break
                end = limit
            }
            val length = roundText.substring(consumed, end).trim().length
            // 第一句就超长也压住:定稿时走整段播报的切法,而不是一口气念几百字
            if (spoken + length > hardLimit) {
                held = true
                break
            }
            out.append(take(end))
            if (spoken >= softLimit) {
                held = true
                break
            }
        }
        return out.toString()
    }

    private fun take(end: Int): String {
        val piece = roundText.substring(consumed, end)
        consumed = end
        spoken += piece.trim().length
        return piece
    }

    companion object {
        private const val SENTENCE_ENDS = "。！？!?；;…\n"

        /** 返回 [from] 之后第一个句末的下一个位置;到 [limit] 都没有就返回 -1。 */
        fun nextBoundary(text: String, from: Int, limit: Int): Int {
            var i = from
            while (i < limit) {
                val c = text[i]
                // 英文句号后面得跟空白才算句末,不然 3.5 这种小数会被切开
                if (c in SENTENCE_ENDS || (c == '.' && i + 1 < limit && text[i + 1].isWhitespace())) {
                    var end = i + 1
                    // 连着的标点(「！！」「……」「？」」)一起带走
                    while (end < limit && (text[end] in SENTENCE_ENDS || text[end] in "」』”\"')）")) end++
                    return end
                }
                i++
            }
            return -1
        }
    }
}

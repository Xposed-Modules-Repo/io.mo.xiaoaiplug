package io.mo.xiaoaiplug.hook

/**
 * 「长回答只念开头」:播报只念前一两句,其余留在卡片上。
 *
 * 模型偶尔会给出几百字的答案,整段念完要一分钟,用户早就看完屏幕了。开关打开后,
 * 自动播报只念到 [TARGET_CHARS] 左右的句末,再提示一句去看屏幕;卡片和喇叭重播
 * 仍是全文(重播读的 SpeakContent 不经过这里)。
 *
 * 入参是 toSpeakable 处理过的纯文本 —— 换行已经变成「，」,markdown 已经剥掉。
 */
internal object BriefSpeech {

    /** 念到这个长度附近的句末就收。中文 TTS 大约每秒 4~5 字,60 字十几秒。 */
    const val TARGET_CHARS = 60

    /** 一句话长到这里还没结束,就退而在逗号处切,再不行硬切。 */
    const val MAX_CHARS = 120

    /** 切完剩下的不到这么多,就干脆念完 —— 为一小截尾巴再补一句"请看屏幕"反而更长。 */
    const val TAIL_TOLERANCE = 30

    const val NOTICE = "详细内容请看屏幕"

    private const val SENTENCE_ENDS = "。！？!?；;"
    private const val SOFT_BREAKS = "，,、：:"

    fun cut(text: String): String {
        val t = text.trim()
        if (t.length <= TARGET_CHARS + TAIL_TOLERANCE) return t

        val end = cutPoint(t)
        if (t.length - end < TAIL_TOLERANCE) return t

        val head = t.substring(0, end).trimEnd()
        return if (head.last() in SENTENCE_ENDS) head + NOTICE
        else head.trimEnd { it in SOFT_BREAKS } + "。" + NOTICE
    }

    /** 返回切点(不含)。优先句末,其次逗号,最后硬切。 */
    private fun cutPoint(t: String): Int {
        // 首个落在 TARGET_CHARS 之后、MAX_CHARS 之内的句末
        var lastBefore = -1
        for (i in 0 until minOf(t.length, MAX_CHARS)) {
            if (t[i] !in SENTENCE_ENDS) continue
            if (i + 1 >= TARGET_CHARS) return i + 1
            lastBefore = i + 1
        }
        // TARGET_CHARS 之前有句末,而且前半句不是短得只有几个字
        if (lastBefore >= TARGET_CHARS / 3) return lastBefore
        // 一句话太长:在 MAX_CHARS 以内最后一个逗号处切
        for (i in minOf(t.length, MAX_CHARS) - 1 downTo TARGET_CHARS / 2) {
            if (t[i] in SOFT_BREAKS) return i + 1
        }
        return TARGET_CHARS
    }
}

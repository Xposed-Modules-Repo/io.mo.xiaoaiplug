package io.mo.xiaoaiplug.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * 一轮问话的「边生成边念」:答案还在生成,整句一出来就交给小爱的流式 TTS。
 *
 * 走的是小爱自己的 StreamingTextTtsManager(反编译里 tag 同名):
 * `onStreamStart(dialogId)` → 多次 `onTextReceived(text)` → `onStreamFinish()`。它在
 * `stream_synthesize` 独立音轨上边收边合成,音色、音量和小爱原生一致。
 * ToastStreamPlayer 做不了这件事:它的 speakTts 每调一次就换一个 session,上一句会被截掉。
 *
 * 两道门槛(都在小爱那边判):
 *  - `onStreamStart` 只认被标成 MiClaw 结果的 dialogId(t1.isEnabled → isMiClawResult)。
 *    我们传一个**自己编的、只此一轮用的 id**,先 `markMiClawResult(id)` 再开流 —— 只往它的
 *    表里加一个谁都不会查的新键,不碰任何真实对话的状态。小爱自己的「静音模式/文字输入不出声」
 *    判断仍然生效。
 *  - 小爱不在前台时它直接拒绝。拒绝了就是 [State.FAILED],调用方退回整段播报(ToastStreamPlayer
 *    在会话拆掉之后也能播)。
 *
 * 所有对小爱的调用都在主线程,按投递顺序执行;调用期间置 [ourCall],HookEntry 靠它分辨
 * 哪些 StreamingTextTtsManager 调用是我们发的、哪些是小爱自己的(要挡)。
 */
internal class StreamingSpeech(
    private val cl: ClassLoader,
    private val streamClass: String,
    private val registryClass: String,
    val chunker: SpeechChunker,
    /** 原文 → 念出来的文字(去 markdown、链接等)。 */
    private val clean: (String) -> String,
    /** 开流前调一次:掐掉小爱可能还在念的话。 */
    private val beforeStart: () -> Unit,
    /** 取 Kotlin object 单例。 */
    private val instanceOf: (Class<*>) -> Any?,
    private val ourCall: ThreadLocal<Boolean>
) {
    enum class State { IDLE, STARTED, FINISHED, FAILED, STOPPED }

    /** 定稿收尾的结果。 */
    enum class Outcome {
        /** 已经流式念了(或被用户停掉了),调用方不用再念。 */
        HANDLED,
        /** 流从没开起来,一个字都没念,调用方退回整段播报。 */
        FALLBACK
    }

    @Volatile
    var state = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())

    /** 交一段原文去念(可以从任何线程调)。流还没开就先开。 */
    fun speak(raw: String) {
        if (raw.isBlank()) return
        main.post { if (ensureStarted()) feed(raw) }
    }

    /** 定稿:把剩下的 [tail] 念完并结束这条流。**主线程调**。 */
    fun finishOnMain(tail: String): Outcome {
        if (state == State.IDLE && tail.isNotBlank()) ensureStarted()
        return when (state) {
            State.STARTED -> {
                if (tail.isNotBlank()) feed(tail)
                safeCall("onStreamFinish")
                state = State.FINISHED
                Log.i(TAG, "stream speech finished")
                Outcome.HANDLED
            }
            State.FINISHED, State.STOPPED -> Outcome.HANDLED
            State.IDLE, State.FAILED -> Outcome.FALLBACK
        }
    }

    /** 用户打断/新一轮开始:停流。音轨由调用方另外掐。**主线程调**。 */
    fun stopOnMain() {
        if (state == State.STARTED) safeCall("stop")
        state = State.STOPPED
    }

    private fun ensureStarted(): Boolean {
        if (state != State.IDLE) return state == State.STARTED
        state = try {
            beforeStart()
            val id = "xiaoaiplug-" + UUID.randomUUID()
            val registry = cl.loadClass(registryClass)
            val registryInstance = instanceOf(registry) ?: error("no MiClaw registry instance")
            registry.getMethod("markMiClawResult", String::class.java).invoke(registryInstance, id)
            val session = call("onStreamStart", id) as? String
            Log.i(TAG, "stream speech start id=$id session=$session")
            if (session != null) State.STARTED else State.FAILED
        } catch (t: Throwable) {
            Log.w(TAG, "stream speech unavailable: $t")
            State.FAILED
        }
        return state == State.STARTED
    }

    private fun feed(raw: String) {
        var text = clean(raw)
        if (text.isBlank()) return
        // clean 会把换行变成「，」再修剪掉首尾标点:以换行收尾的一段交出去时末尾没了停顿,
        // 会跟下一段连着念。补一个逗号。
        if (text.last() !in SPOKEN_PAUSES) text += "，"
        safeCall("onTextReceived", text)
    }

    /** 开流之后的调用:跑在主线程的 Runnable 里,抛出去会把小爱整个带崩,只记日志。 */
    private fun safeCall(name: String, vararg args: String) {
        try {
            call(name, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "stream speech $name failed: $t")
        }
    }

    private fun call(name: String, vararg args: String): Any? {
        val clazz = cl.loadClass(streamClass)
        val instance = instanceOf(clazz) ?: error("no streaming tts instance")
        val method = clazz.getMethod(name, *Array(args.size) { String::class.java })
        ourCall.set(true)
        try {
            return method.invoke(instance, *args)
        } finally {
            ourCall.set(false)
        }
    }

    private companion object {
        const val TAG = "XiaoAiProbe"
        const val SPOKEN_PAUSES = "。！？!?；;，,、：:…"
    }
}

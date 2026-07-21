package io.mo.xiaoaiplug.config

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors

/**
 * Hook 侧(跑在 com.miui.voiceassist 进程里)的记录写入端。
 *
 * **绝对不能阻塞回答路径。** 整条链路本来就慢 —— AiClient 的注释里记着真机上跑满
 * 6 轮工具要 30 秒 —— 用户已经在干等了,不能因为记个日志再多等一毫秒。
 * 所以这里一律 fire-and-forget:丢给单线程后台队列,失败静默吞掉,调用方拿不到也不关心结果。
 *
 * 单线程而非线程池:写入本身是串行的 SQLite 事务,并发只会互相抢锁;
 * 单线程还顺带保证了记录的落库顺序跟发生顺序一致。
 */
object LogClient {

    private val uri: Uri = Uri.parse("content://${ConfigProvider.AUTHORITY}")

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xiaoai-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun chat(
        context: Context?,
        question: String,
        answer: String,
        model: String,
        durationMs: Long,
        /** 各阶段耗时,形如 "模型 2.9s → 工具 0.8s → 模型 3.4s"。 */
        breakdown: String = ""
    ) {
        append(
            context,
            LogEntry(
                time = System.currentTimeMillis(),
                type = LogEntry.TYPE_CHAT,
                title = question,
                detail = buildString {
                    append("模型: ").append(model.ifBlank { "(未填)" }).append("\n")
                    if (breakdown.isNotBlank()) append("耗时: ").append(breakdown).append("\n")
                    append("\n")
                    append("问: ").append(question).append("\n\n")
                    append("答: ").append(answer)
                },
                durationMs = durationMs,
                ok = true
            )
        )
    }

    fun tool(
        context: Context?,
        name: String,
        args: String,
        result: String,
        durationMs: Long
    ) {
        // 工具是否成功没有独立信号,约定 handler 出错时返回以 "error:" 开头的字符串
        // (见 Tools.execute 和 ConfigProvider 里的几处 "error: ..." 返回)。
        val failed = result.trimStart().startsWith("error:", ignoreCase = true)
        append(
            context,
            LogEntry(
                time = System.currentTimeMillis(),
                type = LogEntry.TYPE_TOOL,
                title = name,
                detail = buildString {
                    append("参数: ").append(args).append("\n\n")
                    append("返回: ").append(result)
                },
                durationMs = durationMs,
                ok = !failed
            )
        )
    }

    fun error(context: Context?, title: String, detail: String, durationMs: Long = -1L) {
        append(
            context,
            LogEntry(
                time = System.currentTimeMillis(),
                type = LogEntry.TYPE_ERROR,
                title = title,
                detail = detail,
                durationMs = durationMs,
                ok = false
            )
        )
    }

    fun append(context: Context?, entry: LogEntry) {
        if (context == null) return
        val app = context.applicationContext ?: context
        writer.execute {
            try {
                app.contentResolver.call(
                    uri,
                    ConfigProvider.METHOD_LOG_APPEND,
                    null,
                    Bundle().apply {
                        putLong(ConfigProvider.LOG_TIME, entry.time)
                        putString(ConfigProvider.LOG_TYPE, entry.type)
                        putString(ConfigProvider.LOG_TITLE, entry.title)
                        putString(ConfigProvider.LOG_DETAIL, entry.detail)
                        putLong(ConfigProvider.LOG_DURATION, entry.durationMs)
                        putBoolean(ConfigProvider.LOG_OK, entry.ok)
                    }
                )
            } catch (t: Throwable) {
                // 记日志失败不该影响任何事,更不该再记一条日志。
            }
        }
    }
}

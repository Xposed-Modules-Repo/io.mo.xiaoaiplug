package io.mo.xiaoaiplug.hook

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 把接管轮次的答案写进 App「历史对话」页真正读的那份记录。
 *
 * 历史页不读 ChatDbManager 的 CHAT_MESSAGE_BEAN,而是读 va_database.instruction_info,
 * 按 dialogId 把当轮指令回放成卡片:大模型轮次靠 Template/FrontendPage 的 extra.sections 出字,
 * 动作类轮次靠 Template/Toast 的 payload.text。接管轮次小爱自己的流被我们拦了,
 * 落库的 sections 是空数组(Toast 则是它那句"打开失败"),于是历史里只剩用户的问话。
 *
 * 直接走 SQL、不 hook 小爱的 Room DAO:表结构跨版本稳定,DAO/实体类名每版都混淆重排。
 * FrontendPage 要等这轮对话收尾才落库(实测在答案到达后约 9 秒,我们放行被拦的结束信令那一刻),
 * 时机不固定,所以在一段时间内每秒查一次:出现就补,小爱后来又覆写了也能改回来。
 * 稀疏几次重试会让用户回 App 时看到好几秒空白。小爱还没写卡时先垫一条我们自己的,
 * 它的卡落库后再删掉 —— 进程在收尾前被划掉的话,小爱那条永远不会生成。
 */
internal object InstructionHistory {
    private const val TAG = "XiaoAiProbe"
    private const val DB_NAME = "va_database"
    private const val POLL_INTERVAL_MS = 1_000L
    // 实测有轮次答案到了 59 秒小爱还没写卡;窗口太短的话它晚到的空卡会和我们垫的那条并存
    private const val POLL_WINDOW_MS = 300_000L
    // 我们自己垫的 FrontendPage 记录用这个 ins_id 前缀,小爱的卡落库后据此删掉
    private const val OWN_INS_PREFIX = "xiaoaiplug-"
    private const val FALLBACK_PAGE_PAYLOAD = """{"payload":{"bottom_button_display":true,"error_prompt":{"description":""},""" +
        """"load_type":"UNKNOWN","load_url":"https://i.ai.mi.com/h5/ai-rn-bussiness-stream/stream.bundle?share=true",""" +
        """"param":{},"param_type":"","skill_icon":{"description":"","sources":[]},"timeout_in_milli":0}}"""

    private val worker: Handler by lazy {
        Handler(HandlerThread("xiaoai-plug-history").apply { start() }.looper)
    }

    fun patch(context: Context, dialogId: String, answer: String) {
        if (dialogId.isBlank() || answer.isBlank()) return
        val db = context.getDatabasePath(DB_NAME)
        val deadline = System.currentTimeMillis() + POLL_WINDOW_MS
        worker.post(object : Runnable {
            override fun run() {
                patchOnce(db, dialogId, answer)
                if (System.currentTimeMillis() < deadline) worker.postDelayed(this, POLL_INTERVAL_MS)
            }
        })
    }

    private fun patchOnce(file: File, dialogId: String, answer: String) {
        if (!file.exists()) return
        try {
            // 必须带 ENABLE_WRITE_AHEAD_LOGGING:Room 用的是 WAL,不带的话这个连接打开时会
            // 尝试把 journal_mode 改回默认值,和小爱自己的连接打架。
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
                // 历史页 join query_info,没有问话记录的 dialogId 根本不显示,别碰。实测 MiClaw 工具被拦后
                // 小爱会往固定占位 id "fakeErrorDialogId" 写错误 Toast,它跨轮共用,改了会串到别的轮次。
                if (!hasQuery(db, dialogId)) return
                val (ours, theirs) = rows(db, dialogId, "FrontendPage").partition { it.insId.startsWith(OWN_INS_PREFIX) }
                val toasts = rows(db, dialogId, "Toast").filter { toastText(it.payload).isNotBlank() }
                var changed = 0
                when {
                    theirs.isNotEmpty() -> {
                        // 小爱自己的大模型卡落库了:改它那条,我们先垫的那条删掉,否则历史里出两遍
                        changed += theirs.count { rewriteFrontendPage(db, it.id, dialogId, it.extra, answer) }
                        changed += ours.count { delete(db, it.id) }
                    }
                    toasts.isNotEmpty() -> {
                        // 动作类轮次:答案放在 Toast 里
                        changed += toasts.count { rewriteToast(db, it.id, it.payload, answer) }
                        changed += ours.count { delete(db, it.id) }
                    }
                    ours.isNotEmpty() -> changed += ours.count { rewriteFrontendPage(db, it.id, dialogId, it.extra, answer) }
                    // 小爱还没写卡(甚至可能永远不写:实测进程在收尾前被划掉,那条 FrontendPage 就不会生成),
                    // 先垫一条自己的。只垫在有 Query 卡的 dialogId 上 —— 同一句话常有两个 dialogId,
                    // 另一个只有 MiClaw/Experiment 记录、历史页不显示,往那垫会多出一条答案。
                    rows(db, dialogId, "Query").isNotEmpty() -> if (insertFrontendPage(db, dialogId, answer)) changed++
                }
                if (changed > 0) Log.i(TAG, "instruction history patched dialogId=$dialogId rows=$changed")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "instruction history patch failed dialogId=$dialogId: $t")
        }
    }

    private fun hasQuery(db: SQLiteDatabase, dialogId: String): Boolean =
        db.rawQuery("SELECT 1 FROM query_info WHERE dialog_id = ? LIMIT 1", arrayOf(dialogId)).use { it.moveToFirst() }

    private class Row(val id: Long, val insId: String, val payload: String?, val extra: String?)

    private fun rows(db: SQLiteDatabase, dialogId: String, name: String): List<Row> =
        db.rawQuery(
            "SELECT id, ins_id, payload, extra FROM instruction_info WHERE dialog_id = ? AND namespace = 'Template' AND name = ?",
            arrayOf(dialogId, name)
        ).use { c ->
            buildList { while (c.moveToNext()) add(Row(c.getLong(0), c.getString(1).orEmpty(), c.getString(2), c.getString(3))) }
        }

    // 垫的那条照抄小爱自己 FrontendPage 的 payload(决定历史页用哪个 RN 页面渲染),只换 header 里的身份。
    // 库里一条都没有时退回实测抓到的那份。
    private fun insertFrontendPage(db: SQLiteDatabase, dialogId: String, answer: String): Boolean {
        val insId = OWN_INS_PREFIX + UUID.randomUUID().toString().replace("-", "")
        val template = db.rawQuery(
            "SELECT payload FROM instruction_info WHERE namespace = 'Template' AND name = 'FrontendPage' " +
                "AND payload LIKE '%stream.bundle%' ORDER BY id DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val payload = (template?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject(FALLBACK_PAGE_PAYLOAD))
        payload.put("header", JSONObject()
            .put("name", "FrontendPage")
            .put("namespace", "Template")
            .put("dialog_id", dialogId)
            .put("id", insId)
            .put("transaction_id", ""))
        val values = ContentValues().apply {
            put("ins_id", insId)
            put("dialog_id", dialogId)
            put("namespace", "Template")
            put("name", "FrontendPage")
            put("payload", payload.toString())
            put("extra", JSONObject().put("dialogId", "").put("isLargeModelContent", true).toString())
            put("timestamp", System.currentTimeMillis())
        }
        val id = db.insert("instruction_info", null, values)
        return id > 0 && rewriteFrontendPage(db, id, dialogId, values.getAsString("extra"), answer)
    }

    // 字段与小爱自己的 FrontPageHistoryData.create(content, dialogId) 生成的保持一致
    private fun rewriteFrontendPage(db: SQLiteDatabase, id: Long, dialogId: String, extra: String?, answer: String): Boolean {
        val json = extra?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val sections = json.optJSONArray("sections")
        if (sections != null && sections.length() == 1 &&
            sections.optJSONObject(0)?.optString("content") == answer) return false
        val section = JSONObject()
            .put("id", dialogId)
            .put("type", "markdown")
            .put("isLast", true)
            .put("show", true)
            .put("isEnd", true)
            .put("showCancel", false)
            .put("totalText", "")
            .put("content", answer)
            .put("isParsed", false)
            .put("isTextReplaceed", false)
        json.put("sections", JSONArray().put(section))
        json.put("isLlmContentDelivered", true)
        json.put("isLlmContentDisplayComplete", true)
        return update(db, id, "extra", json.toString())
    }

    // 没有 text 的 Toast(只带 display 任务)不是文字卡,别往里塞
    private fun toastText(payload: String?): String =
        payload?.let { runCatching { JSONObject(it).optJSONObject("payload")?.optString("text") }.getOrNull() }.orEmpty()

    private fun rewriteToast(db: SQLiteDatabase, id: Long, payload: String?, answer: String): Boolean {
        if (toastText(payload) == answer) return false
        val json = JSONObject(payload!!)
        json.getJSONObject("payload").put("text", answer)
        return update(db, id, "payload", json.toString())
    }

    private fun update(db: SQLiteDatabase, id: Long, column: String, value: String): Boolean =
        db.update("instruction_info", ContentValues().apply { put(column, value) }, "id = ?", arrayOf(id.toString())) > 0

    private fun delete(db: SQLiteDatabase, id: Long): Boolean =
        db.delete("instruction_info", "id = ?", arrayOf(id.toString())) > 0
}

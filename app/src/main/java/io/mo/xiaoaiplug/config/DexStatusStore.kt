package io.mo.xiaoaiplug.config

import android.content.Context
import android.os.Bundle
import io.mo.xiaoaiplug.hook.dex.DexCacheArtifact
import org.json.JSONObject

/** Provider-owned storage: a manual scan is staged separately from the host acknowledgement. */
internal class DexStatusStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("xiaoai_plug_dex", Context.MODE_PRIVATE)

    @Synchronized
    fun sharedArtifact(): Bundle = Bundle().apply {
        putString("artifact", prefs.getString("staged", null))
    }

    @Synchronized
    fun read(): Bundle = Bundle().apply {
        putString("report", prefs.getString("report", null))
        val staged = DexCacheArtifact.decode(prefs.getString("staged", null), requireComplete = false)
        putBoolean("pending", staged != null && staged.id != prefs.getString("loaded_id", null))
        putBoolean("host_loaded", prefs.getBoolean("host_loaded", false))
    }

    @Synchronized
    fun report(extras: Bundle, fromModule: Boolean): Bundle {
        val manual = extras.getBoolean("manual")
        if (manual && !fromModule) return Bundle().apply { putBoolean("ok", false) }
        val artifactText = extras.getString("artifact")
        val artifact = DexCacheArtifact.decode(artifactText, requireComplete = false)
        // Partial reports are authoritative invalidation requests, not usable cached symbols.
        if (manual && !artifactText.isNullOrBlank() && artifact == null) {
            return Bundle().apply { putBoolean("ok", false) }
        }
        val staged = DexCacheArtifact.decode(prefs.getString("staged", null), requireComplete = false)
        val loadedId = extras.getString("loaded_id").orEmpty()
        val decision = dexHandoffDecision(manual, staged?.id, prefs.getString("loaded_id", null),
            loadedId, extras.getString("observed_id"))
        val edit = prefs.edit()
        if (manual && artifact != null) edit.putString("staged", artifactText)
        if (manual && artifact == null) edit.remove("staged")
        if (!manual && loadedId == staged?.id && artifact?.id == staged.id) {
            edit.putString("staged", artifactText)
        }
        if (decision.discardStaged) edit.remove("staged")

        // A report from an older running host must not erase a newer pending manual scan.
        if (decision.replaceReport) {
            val report = JSONObject().apply {
                put("symbols", JSONObject(extras.getString("symbols_json").orEmpty()))
                put("states", JSONObject(extras.getString("states_json") ?: "{}"))
                put("duration", extras.getLong("duration"))
                put("source", extras.getString("source"))
                put("app_version", extras.getString("app_version"))
                put("summary", extras.getString("summary"))
                put("cacheable", extras.getBoolean("cacheable"))
                put("time", System.currentTimeMillis())
            }
            edit.putString("report", report.toString())
            edit.putBoolean("host_loaded", !manual)
        }
        if (!manual && loadedId == staged?.id) edit.putString("loaded_id", loadedId)
        // Acknowledge only after the handoff and its UI status are durable together.
        val saved = edit.commit()
        if (saved && decision.replaceReport && extras.getString("source").orEmpty().contains("DexKit")) {
            // Reporting remains successful even if the optional activity log cannot be written.
            runCatching {
                LogStore.get(context).append(LogEntry(
                    time = System.currentTimeMillis(), type = LogEntry.TYPE_TOOL,
                    title = "自适应符号扫描 (DexKit)",
                    detail = "${extras.getString("app_version")} · ${extras.getString("source")}\n" +
                        extras.getString("diagnostic").orEmpty(),
                    durationMs = extras.getLong("duration"), ok = extras.getBoolean("cacheable")
                ))
            }
        }
        return Bundle().apply { putBoolean("ok", saved) }
    }
}

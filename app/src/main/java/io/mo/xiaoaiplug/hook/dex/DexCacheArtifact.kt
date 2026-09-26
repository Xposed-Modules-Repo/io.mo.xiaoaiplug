package io.mo.xiaoaiplug.hook.dex

import org.json.JSONObject
import java.util.UUID

/** A newer partial scan is an explicit invalidation barrier for both memory and disk. */
internal fun selectCachedArtifact(
    identity: ApkIdentity, published: DexCacheArtifact?, memory: DexCacheArtifact?, disk: DexCacheArtifact?
): DexCacheArtifact? {
    if (published?.identity == identity) return published.takeIf { it.matches(identity) }
    return memory?.takeIf { it.matches(identity) } ?: disk?.takeIf { it.matches(identity) }
}

/** Shared by disk caching and the module -> host handoff. Bump when rules change. */
data class DexCacheArtifact(
    val identity: ApkIdentity,
    val scan: DexScanResult,
    val id: String = UUID.randomUUID().toString()
) {
    fun matches(expected: ApkIdentity): Boolean =
        expected.valid && identity == expected && scan.cacheable

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", id)
        put("apkPath", identity.path)
        put("appVersionCode", identity.version)
        put("apkLastModified", identity.modified)
        put("apkLength", identity.length)
        put("symbols", scan.symbols.toJson())
        put("states", statesToJson(scan.states))
        put("error", scan.error)
    }

    companion object {
        const val SCHEMA_VERSION = 6

        fun decode(text: String?, requireComplete: Boolean = true): DexCacheArtifact? = runCatching {
            if (text.isNullOrBlank()) return null
            val json = JSONObject(text)
            if (json.getInt("schemaVersion") != SCHEMA_VERSION) return null
            val identity = ApkIdentity(json.getString("apkPath"), json.getLong("appVersionCode"),
                json.getLong("apkLastModified"), json.getLong("apkLength"))
            if (!identity.valid) return null
            val symbols = json.getJSONObject("symbols")
            val states = statesFromJson(json.getJSONObject("states").toString())
            // Partial handoffs carry only freshly verified names plus explicit disabled entries.
            if (TargetSymbols().getDetailedList().any {
                    symbols.opt(it.key) !is String || states[it.key] == null ||
                        (states[it.key]?.state == SymbolState.MATCHED) != symbols.getString(it.key).isNotBlank()
                }) return null
            val scan = DexScanResult(TargetSymbols.fromJson(symbols), states,
                json.optString("error").takeIf { it.isNotBlank() })
            val id = json.getString("id")
            if ((requireComplete && !scan.cacheable) || id.isBlank()) return null
            DexCacheArtifact(identity, scan, id)
        }.getOrNull()

        fun statesToJson(states: Map<String, SymbolScan>): JSONObject = JSONObject().apply {
            states.forEach { (key, value) ->
                put(key, JSONObject().put("state", value.state.name).put("durationMs", value.durationMs)
                    .put("detail", value.detail))
            }
        }

        fun statesFromJson(text: String?): Map<String, SymbolScan> = runCatching {
            val json = JSONObject(text.orEmpty())
            json.keys().asSequence().associateWith { key ->
                val entry = json.getJSONObject(key)
                SymbolScan(SymbolState.valueOf(entry.getString("state")), entry.optLong("durationMs"), entry.optString("detail"))
            }
        }.getOrDefault(emptyMap())
    }
}

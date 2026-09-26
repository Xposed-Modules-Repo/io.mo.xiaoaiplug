package io.mo.xiaoaiplug.config

/** Protects a newly published scan from late reports by a previously started host. */
internal data class DexHandoffDecision(val replaceReport: Boolean, val discardStaged: Boolean)

internal fun dexHandoffDecision(
    manual: Boolean,
    stagedId: String?,
    acknowledgedId: String?,
    loadedId: String?,
    observedId: String?
): DexHandoffDecision {
    val pending = stagedId != null && stagedId != acknowledgedId
    // The host saw this exact publication but rejected it (e.g. APK version changed).
    // Never clear a newer publication that arrived after the host read the provider.
    val rejected = !manual && stagedId != null && observedId == stagedId && loadedId != stagedId
    return DexHandoffDecision(manual || !pending || loadedId == stagedId || rejected, rejected)
}

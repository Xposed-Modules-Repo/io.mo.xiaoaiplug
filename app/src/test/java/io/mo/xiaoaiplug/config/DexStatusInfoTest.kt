package io.mo.xiaoaiplug.config

import io.mo.xiaoaiplug.hook.dex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DexStatusInfoTest {
    private fun complete() = DexStatusInfo(
        symbols = TargetSymbols.fromJson(JSONObject().apply {
            DexScanResult.REQUIRED_KEYS.forEach { put(it, "test.$it") }
        }),
        states = DexScanResult.REQUIRED_KEYS.associateWith { SymbolScan(SymbolState.MATCHED) },
        time = 1, cacheable = true, hostLoaded = true
    )

    @Test fun initialScreenDoesNotClaimAdaptation() {
        assertFalse(DexStatusInfo().homeOk)
        assertTrue(DexStatusInfo().homeSummary.contains("尚未扫描"))
    }

    @Test fun partialScanIsNotGreenEvenWhenOldCacheableFlagIsTrue() {
        val partial = complete().copy(states = complete().states +
            ("bridgeClass" to SymbolScan(SymbolState.NOT_FOUND)))
        assertFalse(partial.homeOk)
        assertTrue(partial.homeSummary.contains("17/18"))
        assertTrue(partial.homeSummary.contains("未命中项停用"))
    }

    @Test fun completeButPendingScanDoesNotClaimHostLoadedIt() {
        val pending = complete().copy(pending = true)
        assertFalse(pending.homeOk)
        assertTrue(pending.homeSummary.contains("待重启"))
        assertTrue(complete().homeOk)
    }
}

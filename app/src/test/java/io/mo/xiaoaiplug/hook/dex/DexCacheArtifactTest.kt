package io.mo.xiaoaiplug.hook.dex

import org.junit.Assert.*
import org.junit.Test

class DexCacheArtifactTest {
    private val identity = ApkIdentity("/data/app/voice/base.apk", 123L, 1000L, 2000L)
    private fun complete() = DexScanResult(symbols = TargetSymbols.fromJson(org.json.JSONObject().apply {
        DexScanResult.REQUIRED_KEYS.forEach { put(it, "test.$it") }
    }), states = DexScanResult.REQUIRED_KEYS.associateWith {
        SymbolScan(SymbolState.MATCHED, 4L)
    })
    private fun artifact() = DexCacheArtifact(identity, complete(), "manual-scan-1")

    @Test fun staleOrMissingHandoffNeedsRescanButPartialDoesNot() {
        val text = artifact().toJson().toString()
        assertFalse(DexCacheArtifact.needsRescan(text, identity))
        // 从没扫过 / 升了 schema / 小爱更新了(身份变了)都要重扫
        assertTrue(DexCacheArtifact.needsRescan(null, identity))
        assertTrue(DexCacheArtifact.needsRescan(
            artifact().toJson().put("schemaVersion", DexCacheArtifact.SCHEMA_VERSION - 1).toString(), identity))
        assertTrue(DexCacheArtifact.needsRescan(text, identity.copy(version = 124L)))
        // 同一个 APK 上的部分命中是真实结论,不反复重扫
        val partial = complete().copy(symbols = complete().symbols.copy(bridgeClass = ""),
            states = complete().states + ("bridgeClass" to SymbolScan(SymbolState.NOT_FOUND)))
        assertFalse(DexCacheArtifact.needsRescan(DexCacheArtifact(identity, partial).toJson().toString(), identity))
        // 读不到小爱 APK 时不瞎扫
        assertFalse(DexCacheArtifact.needsRescan(null, ApkIdentity("", 0L, 0L, 0L)))
    }

    @Test fun completeScanRoundTripsIncludingProvenance() {
        val original = artifact().copy(scan = complete().copy(symbols = complete().symbols.copy(
            ttsBridgeClass = "com.xiaomi.voiceassistant.NewTts",
            uiNavMethodName = "renamedNav"
        )))
        assertEquals(original, DexCacheArtifact.decode(original.toJson().toString()))
        assertTrue(original.matches(identity))
    }

    @Test fun failedAndDefaultOnlyScansCannotBecomeCacheHits() {
        for (scan in listOf(DexScanResult(), DexScanResult.failed("native library unavailable"))) {
            assertFalse(scan.cacheable)
            val candidate = DexCacheArtifact(identity, scan)
            assertFalse(candidate.matches(identity))
            assertNull(DexCacheArtifact.decode(candidate.toJson().toString()))
        }
    }

    @Test fun oneUnresolvedFingerprintPreventsPersistingFallbackAsSuccess() {
        for (state in SymbolState.entries.filter { it != SymbolState.MATCHED }) {
            val partial = complete().copy(symbols = complete().symbols.copy(bridgeClass = ""), states = complete().states +
                ("bridgeClass" to SymbolScan(state)))
            assertFalse("$state must not become a valid cache", partial.cacheable)
            assertNull(DexCacheArtifact.decode(DexCacheArtifact(identity, partial).toJson().toString()))
        }
    }

    @Test fun evenCompleteStatesCannotHideAScanLevelFailure() {
        assertFalse(complete().copy(error = "APK changed during scan").cacheable)
    }

    @Test fun allApkIdentityFieldsInvalidateTheCache() {
        val entry = artifact()
        listOf(identity.copy(path = "/data/app/new/base.apk"), identity.copy(version = 124),
            identity.copy(modified = 1001), identity.copy(length = 2001), identity.copy(length = 0))
            .forEach { assertFalse("Must invalidate $it", entry.matches(it)) }
    }

    @Test fun oldSchemasAndMalformedJsonAreRejected() {
        assertNull(DexCacheArtifact.decode("{broken"))
        assertNull(DexCacheArtifact.decode(null))
        assertNull(DexCacheArtifact.decode(artifact().toJson().put("schemaVersion", 3).toString()))
        assertNull(DexCacheArtifact.decode(artifact().toJson().put("schemaVersion", 4).toString()))
    }

    @Test fun truncatedOrBlankSymbolsAreNotFilledInAsValidCache() {
        val missing = artifact().toJson()
        missing.getJSONObject("symbols").remove("bridgeClass")
        assertNull(DexCacheArtifact.decode(missing.toString()))
        val blank = artifact().toJson()
        blank.getJSONObject("symbols").put("bridgeClass", "")
        assertNull(DexCacheArtifact.decode(blank.toString()))
    }

    @Test fun missingOrUnknownStatesAreRejected() {
        val missing = artifact().toJson()
        missing.getJSONObject("states").remove("asrProcessorClass")
        assertNull(DexCacheArtifact.decode(missing.toString()))
        val invalid = artifact().toJson()
        invalid.getJSONObject("states").getJSONObject("asrProcessorClass").put("state", "SUCCESS")
        assertNull(DexCacheArtifact.decode(invalid.toString()))
    }

    @Test fun ambiguityIsNotResolvedByResultOrdering() {
        assertEquals(null to SymbolState.AMBIGUOUS, uniqueCandidate(listOf("a.A", "b.B")))
        assertEquals(null to SymbolState.AMBIGUOUS, uniqueCandidate(listOf("b.B", "a.A")))
        assertEquals(null to SymbolState.NOT_FOUND, uniqueCandidate(emptyList()))
    }

    @Test fun overloadsFromTheSameClassAreOneCandidate() {
        assertEquals("a.A" to SymbolState.MATCHED, uniqueCandidate(listOf("a.A", "a.A")))
    }

    @Test fun partialPublicationBlocksOlderCompleteMemoryAndDiskEntries() {
        val partial = DexCacheArtifact(identity, complete().copy(
            symbols = complete().symbols.copy(bridgeClass = ""),
            states = complete().states + ("bridgeClass" to SymbolScan(SymbolState.NOT_FOUND))
        ), "new-partial")
        assertNull(selectCachedArtifact(identity, partial, artifact(), artifact()))
        assertNull(DexCacheArtifact.decode(partial.toJson().toString()))
        assertEquals(partial, DexCacheArtifact.decode(partial.toJson().toString(), requireComplete = false))
    }

    @Test fun failedPublicationAlsoInvalidatesCompleteCaches() {
        val failed = DexCacheArtifact(identity, DexScanResult.failed("native unavailable"), "new-failed")
        assertNull(selectCachedArtifact(identity, failed, artifact(), artifact()))
        assertEquals(failed, DexCacheArtifact.decode(failed.toJson().toString(), requireComplete = false))
    }

    @Test fun partialHandoffCannotSmuggleADefaultNameForAnUnresolvedSymbol() {
        val polluted = artifact().copy(scan = complete().copy(
            states = complete().states + ("bridgeClass" to SymbolScan(SymbolState.NOT_FOUND))
        ))
        assertNull(DexCacheArtifact.decode(polluted.toJson().toString(), requireComplete = false))
    }

    @Test fun emptySymbolIsNotAMatchEvenIfItsStatusClaimsSuccess() {
        assertEquals(0, DexScanResult(states = complete().states).matchedCount)
        assertFalse(DexScanResult(states = complete().states).cacheable)
        assertTrue(TargetSymbols().getDetailedList().all { it.resolvedClass.isEmpty() })
    }

    @Test fun diagnosticsIdentifyFailedItemsInsteadOfListingOldNames() {
        val scan = complete().copy(symbols = complete().symbols.copy(bridgeClass = ""),
            states = complete().states + ("bridgeClass" to SymbolScan(SymbolState.AMBIGUOUS,
                detail = "候选: one.Bridge, two.Bridge")))
        val text = scan.diagnosticText()
        assertTrue(text.contains("bridgeClass"))
        assertTrue(text.contains("候选不唯一，已停用"))
        assertTrue(text.contains("one.Bridge, two.Bridge"))
        assertFalse(text.contains("test.bridgeClass"))
    }
}

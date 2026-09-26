package io.mo.xiaoaiplug.config

import org.junit.Assert.*
import org.junit.Test

class DexHandoffPolicyTest {
    @Test fun oldHostCannotOverwriteNewPendingManualScan() {
        assertEquals(DexHandoffDecision(false, false),
            dexHandoffDecision(false, "new", "old", "old", "old"))
    }

    @Test fun hostAcknowledgesExactlyTheStagedGeneration() {
        assertEquals(DexHandoffDecision(true, false),
            dexHandoffDecision(false, "new", "old", "new", "new"))
    }

    @Test fun rejectedArtifactForAnOldApkStopsShowingPendingForever() {
        assertEquals(DexHandoffDecision(true, true),
            dexHandoffDecision(false, "old-apk", null, "new-apk", "old-apk"))
    }

    @Test fun missingProviderReadDoesNotDiscardPublishedResult() {
        assertEquals(DexHandoffDecision(false, false),
            dexHandoffDecision(false, "new", null, "other", null))
    }

    @Test fun manualFailureCanBeDisplayedWithoutDiscardingPreviousPublication() {
        assertEquals(DexHandoffDecision(true, false),
            dexHandoffDecision(true, "good", null, null, null))
    }

    @Test fun automaticScanWithoutPublicationUpdatesTheUi() {
        assertTrue(dexHandoffDecision(false, null, null, "auto", null).replaceReport)
    }
}

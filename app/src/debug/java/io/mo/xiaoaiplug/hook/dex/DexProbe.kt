package io.mo.xiaoaiplug.hook.dex

import org.luckypray.dexkit.DexKitBridge

/** Read-only on-device verification via app_process, without installing or restarting the host. */
object DexProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: DexProbe <apkPath> <absolute libdexkit.so path>" }
        System.load(args[1])
        val result = DexKitBridge.create(args[0]).use { DexFingerprints.scan(it, TargetSymbols()) }
        println(result.diagnosticText())
        println("DEX_PROBE_RESULT ${result.matchedCount}/${DexScanResult.REQUIRED_KEYS.size} complete=${result.cacheable}")
    }
}

package io.mo.xiaoaiplug.hook.dex

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File

private const val TAG = "XiaoAiProbe.Dex"
private const val CACHE_FILE_NAME = "xiaoai_plug_symbols_cache.json"
private const val SYMBOL_SCHEMA_VERSION = 2

/**
 * 负责 DexKit 动态扫描生命周期、版本更新感知与本地缓存管理。
 */
object DexAdapter {

    @Volatile
    private var cachedSymbols: TargetSymbols? = null

    @Volatile
    private var nativeLibraryLoaded = false

    @Volatile
    var lastSource: String = "未初始化"
        private set

    @Volatile
    var lastDurationMs: Long = 0L
        private set

    private fun ensureNativeLibrary(): Boolean {
        if (nativeLibraryLoaded) return true
        return try {
            System.loadLibrary("dexkit")
            nativeLibraryLoaded = true
            Log.i(TAG, "DexKit native library loaded successfully")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load dexkit native library: $t")
            false
        }
    }

    /**
     * 解析目标符号映射表。
     *
     * @param apkPath 目标应用（小爱同学）的 base.apk 路径
     * @param cacheDir 缓存目录（优先使用目标进程的 cacheDir）
     * @param appVersionCode 目标应用的版本号
     * @param apkLastModified APK 文件的最后修改时间
     * @param apkLength APK 文件的字节大小
     */
    fun resolveSymbols(
        apkPath: String,
        cacheDir: File? = null,
        appVersionCode: Long = 0L,
        apkLastModified: Long = 0L,
        apkLength: Long = 0L
    ): TargetSymbols {
        cachedSymbols?.let { return it }

        val startTime = System.currentTimeMillis()
        val fallback = TargetSymbols()
        val cacheFile = getCacheFile(cacheDir)

        // 1. 尝试从缓存中加载
        if (cacheFile != null && cacheFile.exists()) {
            val cached = loadFromCache(cacheFile, appVersionCode, apkLastModified, apkLength, apkPath)
            if (cached != null) {
                lastSource = "本地缓存"
                lastDurationMs = System.currentTimeMillis() - startTime
                Log.i(TAG, "Loaded target symbols from valid cache in ${lastDurationMs}ms (ver=$appVersionCode, mod=$apkLastModified)")
                cachedSymbols = cached
                return cached
            }
        }

        // 2. 缓存未命中或应用已更新，触发 DexKit 扫描
        Log.i(TAG, "Target app updated or cache missing (ver=$appVersionCode, mod=$apkLastModified, len=$apkLength), starting dynamic DexKit scan for: $apkPath")
        val scanned = scanApk(apkPath, fallback)
        lastSource = "动态搜索 (DexKit)"
        lastDurationMs = System.currentTimeMillis() - startTime

        // 3. 写入新缓存
        if (cacheFile != null) {
            saveToCache(cacheFile, scanned, appVersionCode, apkLastModified, apkLength, apkPath)
        }

        cachedSymbols = scanned
        return scanned
    }

    /**
     * 清理本地已保存的符号缓存文件及内存缓存。
     */
    fun clearCache(cacheDir: File? = null): Boolean {
        cachedSymbols = null
        val file = getCacheFile(cacheDir)
        return try {
            if (file != null && file.exists()) {
                val deleted = file.delete()
                Log.i(TAG, "Dex cache file deleted: $deleted (${file.absolutePath})")
                deleted
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete dex cache: $t")
            false
        }
    }

    /**
     * 手动触发 DexKit 全量重新扫描，清理本地缓存后重新执行指纹搜索。
     */
    fun forceRescan(
        apkPath: String,
        cacheDir: File? = null,
        appVersionCode: Long = 0L,
        apkLastModified: Long = 0L,
        apkLength: Long = 0L
    ): TargetSymbols {
        clearCache(cacheDir)

        val startTime = System.currentTimeMillis()
        val fallback = TargetSymbols()
        val cacheFile = getCacheFile(cacheDir)

        Log.i(TAG, "Manual rescan initiated for: $apkPath")
        val scanned = scanApk(apkPath, fallback)
        lastSource = "手动重搜 (DexKit)"
        lastDurationMs = System.currentTimeMillis() - startTime

        if (cacheFile != null) {
            saveToCache(cacheFile, scanned, appVersionCode, apkLastModified, apkLength, apkPath)
        }

        cachedSymbols = scanned
        return scanned
    }

    private fun scanApk(apkPath: String, defaultSymbols: TargetSymbols): TargetSymbols {
        if (!ensureNativeLibrary()) {
            Log.w(TAG, "DexKit native lib unavailable, fallback to default symbols")
            return defaultSymbols
        }

        return try {
            val start = System.currentTimeMillis()
            val result = DexKitBridge.create(apkPath).use { bridge ->
                DexFingerprints.scan(bridge, defaultSymbols)
            }
            val cost = System.currentTimeMillis() - start
            Log.i(TAG, "DexKit dynamic scan completed in ${cost}ms")
            result
        } catch (t: Throwable) {
            Log.e(TAG, "DexKit scan failed with exception, fallback to defaults: $t", t)
            defaultSymbols
        }
    }

    private fun getCacheFile(cacheDir: File?): File? {
        val dir = cacheDir ?: File("/data/data/com.miui.voiceassist/cache")
        return try {
            if (!dir.exists()) dir.mkdirs()
            File(dir, CACHE_FILE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve cache file path: $t")
            null
        }
    }

    private fun loadFromCache(
        file: File,
        expectedVersion: Long,
        expectedLastModified: Long,
        expectedLength: Long,
        expectedPath: String
    ): TargetSymbols? {
        return try {
            val text = file.readText()
            val json = JSONObject(text)
            if (json.optInt("schemaVersion", 0) != SYMBOL_SCHEMA_VERSION) return null

            val cachedVersion = json.optLong("appVersionCode", -1L)
            val cachedLastModified = json.optLong("apkLastModified", -1L)
            val cachedLength = json.optLong("apkLength", -1L)
            val cachedPath = json.optString("apkPath", "")

            // 只要任何一个指标不匹配，立即认定小爱已升级，丢弃缓存触发动态扫描
            if (expectedVersion > 0 && cachedVersion != expectedVersion) {
                Log.i(TAG, "Cache expired: appVersionCode changed ($cachedVersion -> $expectedVersion)")
                return null
            }
            if (expectedLastModified > 0 && cachedLastModified != expectedLastModified) {
                Log.i(TAG, "Cache expired: apkLastModified changed ($cachedLastModified -> $expectedLastModified)")
                return null
            }
            if (expectedLength > 0 && cachedLength != expectedLength) {
                Log.i(TAG, "Cache expired: apkLength changed ($cachedLength -> $expectedLength)")
                return null
            }
            if (expectedPath.isNotBlank() && cachedPath.isNotBlank() && cachedPath != expectedPath) {
                Log.i(TAG, "Cache expired: apkPath changed ($cachedPath -> $expectedPath)")
                return null
            }

            val symbolsJson = json.optJSONObject("symbols") ?: return null
            TargetSymbols.fromJson(symbolsJson)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read cache file: $t")
            null
        }
    }

    private fun saveToCache(
        file: File,
        symbols: TargetSymbols,
        appVersionCode: Long,
        apkLastModified: Long,
        apkLength: Long,
        apkPath: String
    ) {
        try {
            val root = JSONObject().apply {
                put("schemaVersion", SYMBOL_SCHEMA_VERSION)
                put("appVersionCode", appVersionCode)
                put("apkLastModified", apkLastModified)
                put("apkLength", apkLength)
                put("apkPath", apkPath)
                put("updatedAt", System.currentTimeMillis())
                put("symbols", symbols.toJson())
            }
            file.writeText(root.toString(2))
            Log.i(TAG, "Saved target symbols to cache: ${file.absolutePath} (ver=$appVersionCode)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write target symbols cache: $t")
        }
    }
}

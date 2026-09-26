package io.mo.xiaoaiplug.hook.dex

import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "XiaoAiProbe.Dex"
private const val CACHE_FILE_NAME = "xiaoai_plug_symbols_cache.json"

/** Serializes scans in this process and coordinates the shared host cache across processes. */
object DexAdapter {
    private var cached: DexCacheArtifact? = null
    private var nativeLibraryLoaded = false

    @Synchronized
    fun resolveSymbols(
        apkPath: String,
        cacheDir: File,
        appVersionCode: Long = 0L,
        sharedArtifact: String? = null
    ): DexResolution = resolve(apkPath, cacheDir, appVersionCode, sharedArtifact, force = false)

    /** A fresh scan supersedes older evidence, including when some symbols fail. */
    @Synchronized
    fun forceRescan(apkPath: String, cacheDir: File, appVersionCode: Long = 0L): DexResolution =
        resolve(apkPath, cacheDir, appVersionCode, null, force = true)

    private fun resolve(
        apkPath: String, cacheDir: File, version: Long, shared: String?, force: Boolean
    ): DexResolution {
        val start = SystemClock.elapsedRealtime()
        if (force) cached = null
        return withCacheLock(cacheDir) { file ->
            // Read metadata after acquiring the lock; another process may have scanned while we waited.
            val identity = identity(apkPath, version)
            if (!identity.valid) {
                cached = null
                if (file != null) AtomicFile(file).delete()
                return@withCacheLock DexResolution(DexScanResult.failed("无法读取小爱 APK"),
                    "解析失败，相关功能停用", SystemClock.elapsedRealtime() - start)
            }
            // Prefer an explicitly published manual scan over both memory and disk caches.
            val published = if (force) null else DexCacheArtifact.decode(shared, requireComplete = false)
                ?.takeIf { it.identity == identity }
            val memory = if (force) null else cached?.takeIf { it.matches(identity) }
            val disk = if (force || file == null) null else read(file)?.takeIf { it.matches(identity) }
            val hit = selectCachedArtifact(identity, published, memory, disk)
            if (hit != null) {
                if (file != null && published != null && disk?.id != published.id) write(file, hit)
                cached = hit
                DexResolution(hit.scan.copy(symbols = hit.scan.symbols.copy()),
                    when { published != null -> "手动扫描结果"; memory != null -> "内存缓存"; else -> "本地缓存" },
                    SystemClock.elapsedRealtime() - start, hit)
            } else {
                var scan = scanApk(apkPath)
                if (identity(apkPath, version) != identity) {
                    scan = DexScanResult.failed("扫描期间小爱 APK 已变化，请重试")
                }
                val artifact = DexCacheArtifact(identity, scan,
                    published?.id ?: java.util.UUID.randomUUID().toString())
                // Incomplete entries are invalidation records; read() will never return them as a cache hit.
                if (file != null) write(file, artifact)
                cached = artifact.takeIf { scan.cacheable }
                val source = (if (force) "手动重搜 (DexKit)" else "动态搜索 (DexKit)") + " · " + scan.summary
                DexResolution(scan.copy(symbols = scan.symbols.copy()), source,
                    SystemClock.elapsedRealtime() - start, artifact)
            }
        }
    }

    private fun identity(path: String, version: Long): ApkIdentity = File(path).let {
        ApkIdentity(it.absolutePath, version, it.lastModified(), it.length())
    }

    private fun scanApk(path: String): DexScanResult = try {
        if (!nativeLibraryLoaded) {
            System.loadLibrary("dexkit")
            nativeLibraryLoaded = true
        }
        DexKitBridge.create(path).use { DexFingerprints.scan(it, TargetSymbols()) }
    } catch (t: Throwable) {
        Log.e(TAG, "DexKit scan failed; unresolved symbols disabled", t)
        DexScanResult.failed(t.javaClass.simpleName)
    }

    private fun <T> withCacheLock(dir: File, action: (File?) -> T): T {
        val lockFile = try {
            check(dir.isDirectory || dir.mkdirs())
            RandomAccessFile(File(dir, "$CACHE_FILE_NAME.lock"), "rw")
        } catch (t: Exception) {
            Log.w(TAG, "Cache unavailable; scanning without disk cache", t)
            return action(null)
        }
        return lockFile.use {
            val lock = try { it.channel.lock() } catch (t: Exception) {
                Log.w(TAG, "Cache lock unavailable; skipping disk access", t)
                return@use action(null)
            }
            lock.use { action(File(dir, CACHE_FILE_NAME)) }
        }
    }

    private fun read(file: File): DexCacheArtifact? = try {
        DexCacheArtifact.decode(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { null }

    private fun write(file: File, artifact: DexCacheArtifact) {
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(artifact.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Exception) {
            atomic.failWrite(stream)
            Log.w(TAG, "Failed to save symbols; previous cache retained", t)
        }
    }
}

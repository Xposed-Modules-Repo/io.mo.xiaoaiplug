package io.mo.xiaoaiplug.config

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 模块界面对宿主(超级小爱)进程的控制。
 *
 * 配置是小爱每次问话时现读的,改了马上生效;只有 DEX 符号要等小爱重新启动才会加载。
 * 早先只能提示用户"去应用详情里结束运行",这里用 root 替他点那一下。
 */
object HostControl {

    private const val TAG = "XiaoAiProbe"
    private const val HOST_PACKAGE = "com.miui.voiceassist"

    /** 首次调用可能停在 root 管理器的授权弹窗上等用户点,给足时间。 */
    private const val SU_TIMEOUT_SEC = 15L

    /**
     * `am force-stop` 小爱,和应用详情页的「结束运行」是同一个操作,下次唤醒时重新拉起。
     * 会 fork su,**必须在后台线程调**。没 root、被拒绝或超时都返回 false。
     */
    fun forceStop(): Boolean = try {
        val proc = ProcessBuilder("su", "-c", "am force-stop $HOST_PACKAGE")
            .redirectErrorStream(true)
            .directory(File("/"))
            .start()
        if (!proc.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            Log.w(TAG, "su timed out while stopping host")
            false
        } else {
            val ok = proc.exitValue() == 0
            if (!ok) Log.w(TAG, "force-stop host exited ${proc.exitValue()}: " +
                    proc.inputStream.bufferedReader().readText())
            ok
        }
    } catch (t: Throwable) {
        Log.w(TAG, "su unavailable for force-stop: ${t.javaClass.simpleName}")
        false
    }
}

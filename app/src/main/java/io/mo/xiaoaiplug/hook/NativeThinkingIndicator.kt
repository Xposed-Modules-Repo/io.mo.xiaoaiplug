package io.mo.xiaoaiplug.hook

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Method

/**
 * 复用 CommonLoadingCard 的原生组件，不复制宿主资源或依赖混淆字段。
 * 已从小爱 8.2.65.4714 核对 Context 构造函数、show() 和 releaseResources()。
 */
internal class NativeThinkingIndicator private constructor(
    val view: View,
    private val show: Method,
    private val release: Method
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private var waiting = false
    private var running = false

    init {
        view.visibility = View.GONE
        view.addOnAttachStateChangeListener(this)
    }

    fun setWaiting(value: Boolean) {
        waiting = value
        view.visibility = if (value) View.VISIBLE else View.GONE
        if (value && canAnimate()) start() else stop()
    }

    private fun canAnimate(): Boolean =
        view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE

    private fun start() {
        if (running) return
        running = true
        try {
            show.invoke(view)
            // 原生 show() 自己 post 动画启动；若其执行前答案已到，随后再次清理。
            view.post {
                if (!waiting || !canAnimate()) {
                    releaseResources()
                    running = false
                }
            }
        } catch (t: Exception) {
            running = false
            Log.w(TAG, "Native thinking indicator failed to start", t)
            releaseResources()
        }
    }

    private fun stop() {
        if (!running) return
        running = false
        releaseResources()
    }

    private fun releaseResources() {
        try { release.invoke(view) }
        catch (t: Exception) { Log.w(TAG, "Native thinking indicator cleanup failed", t) }
    }

    override fun onViewAttachedToWindow(view: View) {
        view.viewTreeObserver.addOnGlobalLayoutListener(this)
        if (waiting && canAnimate()) start()
    }

    override fun onViewDetachedFromWindow(view: View) {
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
        stop()
    }

    override fun onGlobalLayout() {
        if (waiting && canAnimate()) start() else stop()
    }

    companion object {
        private const val TAG = "XiaoAiProbe"
        private const val CLASS_NAME = "com.xiaomi.voiceassistant.widget.LoadingTextView"

        fun create(context: Context): NativeThinkingIndicator? = try {
            val type = context.classLoader.loadClass(CLASS_NAME).asSubclass(View::class.java)
            val show = type.getMethod("show")
            val release = type.getMethod("releaseResources")
            val view = type.getConstructor(Context::class.java).newInstance(context)
            NativeThinkingIndicator(view, show, release)
        } catch (t: Exception) {
            // 无此组件的宿主版本跳过等待组件，答案显示继续工作。
            Log.w(TAG, "Native LoadingTextView unavailable", t)
            null
        }
    }
}

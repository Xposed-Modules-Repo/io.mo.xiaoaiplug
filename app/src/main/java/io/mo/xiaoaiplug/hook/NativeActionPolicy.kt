package io.mo.xiaoaiplug.hook

/** 只用于宿主的动作入口；模块工具直接调用 Android API，不经过这些入口。 */
internal object NativeActionPolicy {
    fun shouldBlock(
        queryAgeMs: Long,
        takenOver: Boolean,
        skipTakeover: Boolean,
        blockViewJump: Boolean
    ): Boolean {
        if (queryAgeMs !in 0L..12_000L || skipTakeover) return false
        // 回答已接管，就不能让原版再执行同一轮的动作（包括“打开应用”）。
        // 未接管时保留原有的独立“查看类不跳转”设置。
        return takenOver || blockViewJump
    }
}

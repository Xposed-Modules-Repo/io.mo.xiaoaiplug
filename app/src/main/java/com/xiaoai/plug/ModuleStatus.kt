package com.xiaoai.plug

/**
 * LSPosed 是否真的把本模块激活了。
 *
 * 套路是标准的自欺:这里永远返回 false,而 [HookEntry] 在**加载进本模块自己的进程**时
 * 会把它替换成返回 true。所以界面读到 true 就说明 hook 框架确实生效了 ——
 * 光看配置文件或者进程存活都证明不了这一点。
 *
 * 前提是模块作用域里勾了自己(res/values/strings.xml 的 xposed_scope 已加上 com.xiaoai.plug),
 * 用户在 LSPosed 里没勾自己的话这里会一直是 false,属于误报,界面的提示要写清楚。
 */
object ModuleStatus {

    @JvmStatic
    fun isActive(): Boolean = false
}

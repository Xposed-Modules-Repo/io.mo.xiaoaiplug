# ——— Xposed 模块入口 ———
# assets/xposed_init 里是按**字符串**写的类名,R8 看不到这条引用。
# 不 keep 的话类会被删掉或改名,装上去 LSPosed 静默加载失败。
-keep class io.mo.xiaoaiplug.HookEntry { *; }

# Xposed API 是 compileOnly,不进 APK,运行时由框架注入。
# R8 的 classpath 里没有它,不 dontwarn 会报一堆 missing class。
-dontwarn de.robv.android.xposed.**
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# ——— 模块激活自检 ———
# ModuleStatus.isActive() 永远返回 false,由 HookEntry 在本模块进程里 hook 成 true
# (见 ModuleStatus 的注释)。风险是 R8 把这个常量返回值直接传播到调用点
# (ConfigViewModel),那样 hook 了也没用,界面永远显示"未激活"。
#
# 实测(R8 full mode,AGP 8.x):光靠下面这条 -keep 就够了 —— 产物 dex 里
# ConfigViewModel 仍然是 invoke-static ModuleStatus.isActive()Z,没有被折叠成常量。
# 试过加 -optimizations !method/propagation/*,full mode 会直接忽略该选项,加不加无差别。
# 改动这条规则后请重新验证:
#   dexdump -d classes.dex | grep "ModuleStatus;.isActive"
# 应当既能看到方法定义,也能看到调用点的 invoke-static。
-keep class io.mo.xiaoaiplug.ModuleStatus { *; }

# ——— 无需额外规则的部分 ———
# MainActivity / ConfigProvider / UiAutoService 由 manifest 声明,AGP 自动生成 keep 规则。
# 反射目标(小爱、系统设置的类)不在本 APK 内,R8 管不着,也无需 keep。
# 配置读写走 org.json 手写解析,没有基于字段名的反射序列化,不用保字段名。

-dontwarn org.jetbrains.annotations.**

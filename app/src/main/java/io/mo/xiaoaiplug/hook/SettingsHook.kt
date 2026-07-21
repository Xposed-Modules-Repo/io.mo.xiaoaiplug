package io.mo.xiaoaiplug.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "XiaoAiProbe"

/**
 * 认页面用的锚点:「小米澎湃 AI」页里「超级小爱」那一条的 key。
 *
 * 用 key 不用标题 —— 这条在 setPreferenceScreen 那一刻标题还是「小爱同学」,
 * 显示出来才变成「超级小爱」,按标题匹配是匹配不上的(第一版就栽在这)。
 * key 还顺带免疫了多语言。
 */
private const val ANCHOR_KEY = "super_xiao_ai"

/** 自己那条 preference 的 key,用来防重复注入。 */
private const val OUR_KEY = "xiaoaiplug_entry"

private const val OUR_PKG = "io.mo.xiaoaiplug"
private const val OUR_ACTIVITY = "io.mo.xiaoaiplug.ui.MainActivity"

/**
 * 在系统设置「小米澎湃 AI」页里插一个进本模块的入口。
 *
 * **按内容认页面,不按类名认**:MIUI 这一页的 fragment 类名每个版本都在变(而且被混淆),
 * 写死类名等于每次 OTA 都要重新逆一遍。这里改成 hook `PreferenceFragmentCompat`
 * 这个所有子类都会调到的基类方法,拿到 PreferenceScreen 后翻一遍标题,
 * 找到「超级小爱」才动手 —— 找不到就当这不是目标页,原样放行。
 *
 * 副作用是每个设置子页都会走一次这个回调,所以判定必须便宜:就是一次线性扫标题。
 */
object SettingsHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fragmentCls = XposedHelpers.findClassIfExists(
            "androidx.preference.PreferenceFragmentCompat", lpparam.classLoader
        )
        val screenCls = XposedHelpers.findClassIfExists(
            "androidx.preference.PreferenceScreen", lpparam.classLoader
        )
        if (fragmentCls == null || screenCls == null) {
            Log.w(TAG, "settings: androidx.preference 不在,跳过入口注入")
            return
        }

        // setPreferenceScreen 是基类里的具体方法,子类(含 miuix.preference.PreferenceFragment)
        // 走 addPreferencesFromResource 最后都会调到它,且此时 xml 里的项已经全部挂好了。
        XposedHelpers.findAndHookMethod(
            fragmentCls, "setPreferenceScreen", screenCls,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val screen = param.args.getOrNull(0) ?: return
                    try {
                        inject(screen)
                    } catch (t: Throwable) {
                        // 设置进程里抛异常会把整个页面带崩,这里一律吞掉只记日志
                        Log.w(TAG, "settings: 注入入口失败", t)
                    }
                }
            }
        )
        Log.i(TAG, "settings: 已挂上 setPreferenceScreen")
    }

    private fun inject(screen: Any) {
        // 插进锚点**自己那一组**,而不是下一组 —— 一个 PreferenceCategory 就是界面上的一张卡片,
        // 同组才会跟「超级小爱」连在一张卡里;放进下一组的话会跟「全局AI帮写」那批粘成一片。
        val target = childrenOf(screen).firstOrNull { group ->
            childrenOf(group).any { keyOf(it) == ANCHOR_KEY }
        } ?: return

        val siblings = childrenOf(target)
        // 已经插过了就别再插 —— setPreferenceScreen 可能被调多次(旋转、重建)
        if (siblings.any { keyOf(it) == OUR_KEY }) return

        val anchor = siblings.first { keyOf(it) == ANCHOR_KEY }
        val anchorOrder = XposedHelpers.callMethod(anchor, "getOrder") as Int

        val context = XposedHelpers.callMethod(screen, "getContext") as Context
        val entry = newPreference(anchor, context) ?: return

        XposedHelpers.callMethod(entry, "setKey", OUR_KEY)
        XposedHelpers.callMethod(entry, "setTitle", "XiaoAi Plug")
        XposedHelpers.callMethod(entry, "setSummary", "本地 AI 接管小爱同学")
        XposedHelpers.callMethod(
            entry, "setIntent",
            Intent().apply {
                component = ComponentName(OUR_PKG, OUR_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // 同组里还有个 gemini,order 跟锚点是连号的。不把它往后挪就会跟我们撞上,
        // 撞了之后 Preference.compareTo 退化成比标题,位置就飘了。
        for (p in siblings) {
            val order = XposedHelpers.callMethod(p, "getOrder") as Int
            if (order > anchorOrder) XposedHelpers.callMethod(p, "setOrder", order + 1)
        }
        XposedHelpers.callMethod(entry, "setOrder", anchorOrder + 1)

        XposedHelpers.callMethod(target, "addPreference", entry)
        Log.i(TAG, "settings: 入口已插入,紧跟 $ANCHOR_KEY,order=${anchorOrder + 1}")
    }

    private fun childrenOf(group: Any): List<Any> {
        val count = runCatching {
            XposedHelpers.callMethod(group, "getPreferenceCount") as Int
        }.getOrNull() ?: return emptyList()
        return (0 until count).map { XposedHelpers.callMethod(group, "getPreference", it) }
    }

    /**
     * 用锚点自己的类来造我们这条,长相才跟同页其它项一致 ——
     * MIUI 这页用的是 miuix 的 Preference 变体,塞一个原生 androidx.preference.Preference
     * 进去会缺卡片圆角、字号也不一样。锚点的类构造不出来(有的要求带 AttributeSet)时
     * 再退回 androidx 基类,至少功能是通的。
     */
    private fun newPreference(anchor: Any, context: Context): Any? {
        runCatching {
            return XposedHelpers.newInstance(anchor.javaClass, context)
        }
        return runCatching {
            XposedHelpers.newInstance(
                XposedHelpers.findClass("androidx.preference.Preference", context.classLoader),
                context
            )
        }.onFailure { Log.w(TAG, "settings: 造不出 Preference", it) }.getOrNull()
    }

    private fun keyOf(pref: Any): String? =
        runCatching { XposedHelpers.callMethod(pref, "getKey") as? String }.getOrNull()
}

package io.mo.xiaoaiplug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.mo.xiaoaiplug.config.AiClient
import io.mo.xiaoaiplug.config.AiConfig
import io.mo.xiaoaiplug.config.ChatHistory
import io.mo.xiaoaiplug.config.ConfigClient
import io.mo.xiaoaiplug.hook.SettingsHook
import io.mo.xiaoaiplug.hook.ReplyTurns
import io.mo.xiaoaiplug.hook.ReplyCards
import io.mo.xiaoaiplug.hook.dex.DexAdapter
import io.mo.xiaoaiplug.hook.dex.TargetSymbols
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "XiaoAiProbe"
private const val TARGET_PKG = "com.miui.voiceassist"

/** 本模块自己的包名。作用域里勾上自己,就能靠 hook 自身来检测「模块是否已激活」。 */
private const val SELF_PKG = "io.mo.xiaoaiplug"

/** 系统设置。只为了在「小米澎湃 AI」页里挂一个入口,跟语音那条链路无关。 */
private const val SETTINGS_PKG = "com.android.settings"

// 我们自己的答案播在这条音轨上(n1.speakTts 用的),静音时必须跳过它。
// 其余的(main / tts / getAuthorization / getNoAccountAuthorization)都是小爱在说话。
private const val OUR_AUDIO_TRACK = "toastStreamTts"

// ASR 指令处理器。小爱把语音识别结果(SpeechRecognizer.RecognizeResult)交给它,
// 终态结果(isFinal=true)带着 dialogId 和问话原文。
private const val ASR_RECOGNIZE_RESULT = "SpeechRecognizer.RecognizeResult"

// 念出来的答案上限。模型答案可能很长(还可能夹着工具输出),整段念完既吵又没法打断。
// 超出部分只截断不摘要 —— 卡片上是全文,想看细节看屏幕。
private const val MAX_SPEAK_CHARS = 220

// 兜底跳转的目标:小米全局搜索(act=android.intent.action.SEARCH, dat=qsb://query/...)
private const val QUICK_SEARCH_PKG = "com.android.quicksearchbox"

// 兜底话术的特征词。实测这句话分三批落库:拦截**之前**就写了两行、我们写完答案之后
// 又补写了十来行,所以只按"拦截时刻"开时间窗是挡不住的,得按内容认。
private val FALLBACK_MARKERS = listOf("只能帮你到这", "请手动操作")

// 「查看类」触发词:命中其一 + 未命中放行词 + 跳转目标是系统设置页 → 拦掉跳转
private val VIEW_WORDS = listOf(
    "查看", "查询", "看看", "看下", "看一下", "查一下", "是多少", "多少", "怎么样", "什么情况", "是什么"
)

// 「发消息类」指令。这类**必须由我们接管**,理由和查看类不同:
// 小爱原生是支持语音发微信的,但这台机器装了微信双开,它只会回
// "暂不支持微信双开功能";打字输入时还会回"仅支持语音对话方式"。
// 两条都是死路,而我们有无障碍服务能真的发出去。
//
// 注意这类话必然命中放行词("打开微信…"里的"打开"),所以走不了
// isViewBlockCandidate,得单独判。
private val SEND_MESSAGE_APPS = listOf("微信", "wechat")
private val SEND_MESSAGE_VERBS = listOf("发消息", "发信息", "发条", "发个", "告诉", "说一声", "带句话")

// 「<锚点> + 对象 + 发 + 内容」的句式。
//
// 词表补不完 —— 「打开微信给文件传输助手**发你好**」一个动词都匹配不上
// (表里最接近的是"发个"),整句被当成"打开微信"处理,发消息彻底失效。
//
// 锚点必须有**两个**。第一版只写了「给」,结果实测语音把它吞了:
//   说的:  打开微信给万峰吻春眠发今晚出去吃饭
//   ASR:   打开微信万峰吻春眠发今晚出去吃饭     ← 「给」没了
// 于是认不出来、不接管、动作类工具被闸拦下,现象就是"被系统小爱接管了"。
// 「给」是口语里最容易被吞的虚词之一,不能单靠它。「微信」是实词,ASR 稳得多。
//
// 后面的否定预查是为了挡掉**问句**:「微信怎么发朋友圈」结构上也是
// 微信…发…,但那是在问怎么做,不是让我们发。
private val SEND_MESSAGE_PATTERN = Regex("(?:给|微信).{1,15}?发(?!朋友圈|现|视频号|布)")

// 「杀后台/清后台/强制停止应用」类指令。和发消息类一样**必须由我们接管**:
// 小爱原生要么做不到、要么只把你送到"最近任务/应用管理"页让你自己动手,真正的
// am force-stop / kill 只有我们的 root shell 能干。真机日志(2026-07-23)复现:模型
// 已经 ps 出 PID、也拼好了 `kill -9 10063` / `am force-stop bin.mt.plus`,但那轮动作闸
// 没为它打开,命令被 Tools.execute 拦下(dur=0ms),模型只好回"没被本模块接管,没法帮你杀"。
//
// 判据:杀类动词 + 「后台/进程」这个对象锚点。**必须要锚点** —— 只看动词会误伤
// "关闭wifi""清理垃圾""停止播放"这些和杀进程无关的话;而"后台/进程"一出现,
// 意图基本就锁定在结束应用上了。另外单列一条"强制/强行停止<某物>":它自带杀意、
// 不依赖锚点(用户常说"强制停止MT管理器"而不带"后台")。
// 自带杀意的动词:不需要"后台/进程"锚点也算(用户认可的放宽,覆盖"杀掉抖音""干掉微信")。
private val KILL_BG_HARD_VERBS = listOf("杀掉", "杀死", "杀", "干掉")
// 语气较软的动词:必须配"后台/进程"锚点,否则会误伤"关闭wifi""清理垃圾""停掉音乐"。
private val KILL_BG_VERBS = listOf("清理", "清掉", "清", "关掉", "关闭", "结束", "停掉")
private val KILL_BG_ANCHORS = listOf("后台", "进程")
// 强制/强行 + 停止/关闭… 一律算(不需锚点),覆盖"强行关掉抖音""强制退出微信"。
private val KILL_BG_STRONG = Regex("(强制|强行)(停止|关闭|关掉|退出|杀)|force.?stop", RegexOption.IGNORE_CASE)

// 「冻结/解冻/清理缓存」类指令。和杀后台同为 app_state_control 的动作,同样要接管才放行动作闸,
// 但不触发小爱的"清后台动画"(那只跟杀/清后台绑,见 [ownsKillTurnNow])。
private val APP_CTRL_PATTERN = Regex("冻结|解冻|清(理|除|一下|一下子)?(缓存|垃圾)")

// 「连接/断开某个蓝牙设备(耳机等)」类指令。和杀后台/发消息类一样**必须由我们接管**才能放行
// bluetooth_control 的 connect/disconnect 动作闸 —— 小爱原生连不了指定设备(连某个 MAC 系统没
// 稳定接口,正是这个工具存在的理由)。判据仿杀后台:连/断动词 + 蓝牙设备锚点,双命中才算,
// 避免"连接WiFi""连上电脑"这类误伤;问句(怎么/如何…)不接管。
private val BT_CONN_VERBS = listOf("连接", "连上", "连一下", "连下", "接上", "断开", "断掉", "断连")
private val BT_CONN_ANCHORS = listOf("蓝牙", "耳机", "耳麦", "耳塞", "buds", "headset", "音箱", "音响")
private val BT_CONN_QUESTION = Regex("怎么|如何|怎样|为什么")

// 判定"跳转目标确实是系统设置/系统应用",避免误伤导航/打开第三方应用之类的正常跳转
class HookEntry : IXposedHookLoadPackage {

    @Volatile
    private var symbols = TargetSymbols()

    /** 把本模块进程里的 ModuleStatus.isActive() 替换成返回 true(见 ModuleStatus 的注释)。 */
    private fun hookSelfProbe(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("io.mo.xiaoaiplug.ModuleStatus")
            XposedBridge.hookAllMethods(
                clazz, "isActive",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
            Log.i(TAG, "self probe installed, module is active")
        } catch (t: Throwable) {
            Log.w(TAG, "self probe failed: $t")
        }
    }

    /**
     * 一个 dialogId 名下的全部易变状态。
     *
     * 原先这些是 11 个并行的 ConcurrentHashMap / newKeySet,全靠 dialogId 当 key 手动关联 ——
     * 要看"这轮走到哪一步"得同时翻十几张表,加/清一项状态得记住在若干处一起动。收进一个对象后,
     * 一个 dialogId 的状态一眼可见,字段级回滚也集中(不再是"三张表各 remove 一次")。
     *
     * 注意 [utteranceDialogs] / 卡片 identityHash 那两族状态**不在这里** —— 它们的 key 域不是
     * dialogId(前者是"一次问话"、一对多挂着 dialogId;后者是 UI 对象),生命周期不同,硬塞进来
     * 反而错。这个类只收 key 恰好是 dialogId 的那 11 项。
     *
     * 字段标注为什么这么定:
     *  - **存在性即状态**的用可空([queryText]):null = 没设过。别拿空串代替 —— setQueryInfo 的
     *    去重(见 hookOperationManager)判的就是"这个 dialogId 设没设过 queryText"。
     *  - 只需跨线程"最终可见"的用 `@Volatile`:hook 在 binder 线程写,AI 线程 / 主线程读。
     *  - 需要"只做一次"原子闸的用 [AtomicBoolean]([injected] / [historyWritten]):
     *    原来靠 `Set.add()` 的布尔返回值做 CAS,合并后必须用 compareAndSet 保住这个语义,
     *    否则并发下会注入两次 / 写两次历史。
     */
    private class DialogSession {
        /** 用户提问原文。非 null 即"这个 dialogId 已被 setQueryInfo 处理过",是去重判据。 */
        @Volatile var queryText: String? = null
        /** 我们自己 AI 的回复(拿到之后才能注入)。 */
        @Volatile var aiAnswer: String? = null
        /** RN 桥接对象(r70.a 子类实例),拿它发我们自己的内容。 */
        @Volatile var bridgeRef: WeakReference<Any>? = null
        /** TemplateReactNativeCard 实例,用来强制显示卡片。 */
        @Volatile var cardRef: WeakReference<Any>? = null
        /** 已接管、要拦截真实内容。 */
        @Volatile var takenOver: Boolean = false
        /** 第二层:被判定为"查看/发消息/杀后台…类,要拦跳转并由我们回答"。 */
        @Volatile var pendingViewAnswer: Boolean = false
        /** 我们造并加进去的答案卡(先占位,答案到再更新它)。 */
        @Volatile var answerCard: WeakReference<Any>? = null
        /** 当初把答案卡加进了哪个 sink,用来判断它是不是后来真正在渲染的那个。 */
        @Volatile var answerCardSink: WeakReference<Any>? = null
        /** 从现在起压制小爱写进历史库的兜底文案,直到我们的答案写进去。 */
        @Volatile var historyPending: Boolean = false
        /** RN 前端是否已回调 rnStartReceiveInstruction 准备好接收流式数据。 */
        @Volatile var rnJsReady: Boolean = false
        /** 已完成注入,防重复。原子:并发只放第一个过。 */
        val injected = AtomicBoolean(false)
        /** 答案已写进历史,防重复(recordToSpeak 一次交互会被连调十来次)。原子。 */
        val historyWritten = AtomicBoolean(false)
    }

    /** dialogId -> 会话状态。取代原先按 dialogId 关联的那 11 个表。 */
    private val sessions = ConcurrentHashMap<String, DialogSession>()
    private val replyTurns = ReplyTurns { android.os.SystemClock.elapsedRealtime() }
    private val replyCards = ReplyCards<Any>()
    private val cardSinks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, WeakReference<Any>>())
    private val replyAddDepth = ThreadLocal.withInitial { 0 }

    /** 取(不存在则建)。**写**路径用它 —— 一个 dialogId 第一次出现在哪条 hook 上是不确定的。 */
    private fun session(dialogId: String): DialogSession =
        sessions.computeIfAbsent(dialogId) { DialogSession() }

    /** 只取不建。**读**路径用它,免得一次查询把空 session 撒得到处都是。 */
    private fun sessionOrNull(dialogId: String): DialogSession? = sessions[dialogId]

    /** 这个 dialogId 是不是被我们接管了 —— 查看类占位([DialogSession.pendingViewAnswer])
     *  或 RN 文本接管([DialogSession.takenOver])。是 allowMutating / 播报等多处的共同判据。 */
    private fun isOurs(dialogId: String): Boolean =
        sessionOrNull(dialogId)?.let { it.pendingViewAnswer || it.takenOver } == true

    // 标记"这次 sendStreamData 调用是我们自己发起的",避免被自己的 hook 再拦一次
    private val injectingNow = ThreadLocal.withInitial { false }
    // 同一个 RN bridge 可能关联多个 dialogId，只发送一次最终答案。
    private val injectedBridges = java.util.WeakHashMap<Any, String>() // 仅主线程访问

    private var sendStreamDataMethod: java.lang.reflect.Method? = null

    // 「查看类」不跳转用:记录最近一次用户问话 + 当时读到的配置,供跳转拦截判定
    @Volatile private var lastQueryText: String = ""
    @Volatile private var lastQueryTime: Long = 0L
    @Volatile private var lastDialogId: String = ""
    @Volatile private var lastConfig: AiConfig? = null

    /**
     * **终态 ASR 原文**,和 [lastQueryText] 分开存。
     *
     * 小爱会改写问话再交给 setQueryInfo,实测:
     *   "打开系统更新" → "系统更新"      (放行词"打开"没了)
     *   "查看系统版本" → "查看版本"      ("系统"没了)
     * 而 [lastQueryText] 会被后到的 setQueryInfo 覆盖,于是判定看到的是**残缺版**。
     * 后果是双向的:放行词被吃掉 → 该放行的跳转被拦;查看词被吃掉 → 该拦的没拦。
     * 前者更糟 —— 用户明确说了"打开",我们却把它挡了。
     *
     * 所以两个文本都留着,判定时取并集(见 [viewBlockCandidateNow])。
     */
    @Volatile private var lastAsrText: String = ""
    @Volatile private var lastAsrTime: Long = 0L

    @Volatile private var targetClassLoader: ClassLoader? = null

    // 当前活跃的结果渲染 sink(flowableresult.d 或 widget.d,取决于是 App 还是悬浮窗),我们用它 addCard
    @Volatile private var activeCardSink: WeakReference<Any>? = null

    /**
     * 哪些卡是"认领"来的(小爱自己造的话术卡),而不是我们自建的。identity hash。
     *
     * 用来区分"还没轮到 bind"和"永远不会 bind":自建卡是我们刚 addCard 进去的,
     * 一时没绑定很正常,不能丢;认领的卡若到答案就绪时仍未绑定,那就是**永远不会绑**
     * (2026-07-20 探针:全程 30 秒 vh=null),必须丢掉改走自建,否则答案无处显示。
     */
    private val commandeeredCards = ConcurrentHashMap.newKeySet<Int>()

    // 被小爱摘掉的卡片(identity hash)。小爱开新一轮对话会重置卡片列表,把我们加的卡也摘掉;
    // 答案回来时若发现自己的卡在这里面,就得重新造一张加回去,否则答案无处显示。
    private val detachedCards = ConcurrentHashMap.newKeySet<Int>()

    // 我们自己造的卡(identity hash -> 该显示的文本)。
    // FlowTemplateToastCard.bindView 绑定视图时,我们要在那一刻把 toastTv 强制填上并设为可见 ——
    // 光靠构造函数传文本不够,实测占位卡 bindView 跑了但屏幕上全程空白。
    private val ourCardTexts = ConcurrentHashMap<Int, String>()

    private val THINKING_PLACEHOLDER = "🤖 正在思考…"

    // 流式显示:两次卡片刷新之间的最小间隔,免得每个 token 都 post 一次主线程把 UI 打爆。
    private val STREAM_RENDER_INTERVAL_MS = 60L
    @Volatile private var lastStreamRenderAt = 0L

    // 历史库改写:拦下小爱自己那句兜底文案,等我们的答案到了再写进去。
    // 实测一次交互里 recordToSpeak 会被连调十来次(同一句插了 11 行),所以压制按对话维度
    // (DialogSession.historyPending)、写入用 DialogSession.historyWritten 保证只写一次。

    // 标记"这次 recordToSpeak 是我们自己写的",别被自己的 hook 压掉
    private val writingHistory = ThreadLocal.withInitial { false }

    // ReplyTurns 用 ASR 对话身份归拢一轮；没有 ASR 时兼容短时间内的同文重复派发。
    // 用户再次说同一句话会得到新 key，ASR 与后续改写 query 共用一次请求。
    // key -> 属于这次问话的所有 dialogId
    private val utteranceDialogs = ConcurrentHashMap<String, MutableSet<String>>()

    // key -> 这次问话的答案(只调一次模型)
    private val utteranceAnswers = ConcurrentHashMap<String, String>()

    // 已经念过的 key,保证一次问话只出一次声
    private val spokenUtterances = ConcurrentHashMap.newKeySet<String>()

    // 静音泵:持续钳制主音轨到这个时刻为止(0 = 不钳制)。
    // 小爱的答案是云端流式回来的,什么时候开始播不确定 —— 打几发定时补刀必然漏。
    // 关键前提:小爱的 TTS 在**主音轨**(v20.e.getMainAudioTrack()),我们的答案在
    // **另一条**音轨(getAudioTrackByName(TOAST_STREAM_TTS),n1 用的那条),
    // 两个是不同的 v20.e 实例,所以死命掐主音轨永远不会掐到我们自己。
    @Volatile private var mutePumpUntil = 0L

    // 静音泵作用于哪个对话。新提问换了 dialogId 就停泵,免得把下一轮的正常播报也吃掉。
    @Volatile private var mutePumpDialogId = ""

    // 泵是否已在跑,避免重复投递形成多条自我递归的 Runnable 链
    @Volatile private var mutePumpRunning = false

    override fun handleLoadPackage(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        // 加载进模块自己的进程时,只做一件事:把 ModuleStatus.isActive() 改成返回 true,
        // 好让设置界面能判断 LSPosed 到底有没有真的激活本模块。
        if (lpparam.packageName == SELF_PKG) {
            hookSelfProbe(lpparam.classLoader)
            return
        }
        // 系统设置:只往「小米澎湃 AI」页插一个进本模块的入口,不碰小爱那套逻辑。
        if (lpparam.packageName == SETTINGS_PKG) {
            SettingsHook.install(lpparam)
            return
        }
        if (lpparam.packageName != TARGET_PKG) return
        Log.i(TAG, "loaded into $TARGET_PKG process=${lpparam.processName} revision=reply-ui-2")
        targetClassLoader = lpparam.classLoader

        // 动态 Dex 搜索与自适应符号解析 (当检测到小爱更新或初次运行时通过 DexKit 扫描，其余走缓存)
        val apkPath = lpparam.appInfo?.sourceDir
        val apkLastModified = if (apkPath != null) File(apkPath).lastModified() else 0L
        val apkLength = if (apkPath != null) File(apkPath).length() else 0L
        val cacheDir = lpparam.appInfo?.dataDir?.let { File(it, "cache") }

        try {
            if (apkPath != null) {
                symbols = DexAdapter.resolveSymbols(
                    apkPath = apkPath,
                    cacheDir = cacheDir,
                    appVersionCode = 0L,
                    apkLastModified = apkLastModified,
                    apkLength = apkLength
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DexAdapter symbol resolution failed, fallback to defaults: $t")
        }

        // 挂钩宿主 Application.onCreate，确保在拥有非空 Context 时将自适应状态同步至模块界面
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Context ?: return
                        val appVer = try {
                            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                "v${pi.longVersionCode}"
                            } else {
                                @Suppress("DEPRECATION")
                                "v${pi.versionCode}"
                            }
                        } catch (t: Throwable) {
                            ""
                        }
                        Log.i(TAG, "Host Application initialized ($appVer), reporting dex symbols (source=${DexAdapter.lastSource})")
                        ConfigClient.reportDexSymbols(
                            context = app,
                            symbolsJson = symbols.toJson().toString(),
                            durationMs = DexAdapter.lastDurationMs,
                            source = DexAdapter.lastSource,
                            appVersion = appVer
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook Application.onCreate for dex symbols reporting: $t")
        }

        hookOperationManager(lpparam.classLoader)
        hookNativeAudio(lpparam.classLoader)
        hookAuthoritativeText(lpparam.classLoader)
        hookBridge(lpparam.classLoader)
        hookCardBaseDiagnostic(lpparam.classLoader)
        hookSettingsJump(lpparam.classLoader)
        hookDismissLifecycle(lpparam.classLoader)
        hookWebSearchFallback(lpparam.classLoader)
        hookChatHistory(lpparam.classLoader)
        hookCardSinks(lpparam.classLoader)
        hookAsrResult(lpparam.classLoader)
        hookAgentAction(lpparam.classLoader)
        hookIntentLaunch(lpparam.classLoader)
        hookToastCardBind(lpparam.classLoader)
        hookBackgroundAppsNav(lpparam.classLoader)
    }

    // 「查看类」不跳转:拦截 IntentUtilsWrapper 的所有跳转与隐藏卡片入口。
    // 当最近一次问话是"查看/查询/多少…"类、且没有放行词时,在小爱收回对话框前直接拦截,
    // 保持浮窗卡片展开并转由 AI 接管回答。
    private fun hookSettingsJump(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.intentUtilsWrapperClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.intentUtilsWrapperClass} not found: $e")
            return
        }
        val targetNames = setOf(
            "startActivitySafely",
            "startActivityHideCard",
            "startActivityHideCardByWindow",
            "startActivityHideCardByActivity",
            "startActivityNotHandleBoard",
            "sendIntentHideCard",
            "startActivityWithIntent",
            "b",
            "c",
            "d"
        )
        for (m in clazz.declaredMethods) {
            if (m.name !in targetNames) continue
            val types = m.parameterTypes
            if (types.isEmpty()) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!ownsCurrentTurn()) return
                            Log.i(TAG, "block view-jump via IntentUtilsWrapper.${m.name}: query=\"$lastQueryText\"")
                            param.result = when (m.returnType) {
                                java.lang.Boolean.TYPE -> true
                                Integer.TYPE -> 0
                                else -> null
                            }
                            onViewJumpBlocked(lastDialogId)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookSettingsJump error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked IntentUtilsWrapper.${m.name}(${types.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                Log.i(TAG, "hook IntentUtilsWrapper.${m.name} fail: $t")
            }
        }
    }

    // 监听浮窗/主界面关闭与退出事件:当用户返回桌面、关闭浮窗或切走时,立即停止 TTS 播报(对齐小爱原生)
    private fun hookDismissLifecycle(cl: ClassLoader) {
        val floatManagerClass = try {
            cl.loadClass(symbols.floatManagerClass)
        } catch (t: Throwable) { null }
        if (floatManagerClass != null) {
            for (m in floatManagerClass.declaredMethods) {
                if (m.name in listOf("hideFloatCard", "resetUiStateAsCapsuleShow")) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                Log.i(TAG, "[float] dismissed (${m.name}), stopping TTS")
                                stopOurTts()
                            }
                        })
                        Log.i(TAG, "hooked floatManager.${m.name} for dismiss TTS stop")
                    } catch (t: Throwable) { }
                }
            }
        }

        val uiManagerClass = try {
            cl.loadClass("com.xiaomi.voiceassistant.UiManager")
        } catch (t: Throwable) { null }
        if (uiManagerClass != null) {
            for (m in uiManagerClass.declaredMethods) {
                if (m.name in listOf("moveTabHostMainActivityToBackground", "resetUiStateAsCapsuleShow")) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                Log.i(TAG, "[ui] exit (${m.name}), stopping TTS")
                                stopOurTts()
                            }
                        })
                        Log.i(TAG, "hooked UiManager.${m.name} for dismiss TTS stop")
                    } catch (t: Throwable) { }
                }
            }
        }
    }

    // 在**终态 ASR** 就把问话记下来。比 setQueryInfo 早 600ms,比"跳设置"的 Agent Action 早 266ms ——
    // 这 266ms 正是能不能拦住那条跳转的全部余量。
    private fun hookAsrResult(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.asrProcessorClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.asrProcessorClass} not found: $e")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "processed" && it.parameterTypes.size == 1
        }
        if (method == null) {
            Log.i(TAG, "${symbols.asrProcessorClass}.processed(Instruction) not found")
            return
        }
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val instruction = param.args[0] ?: return
                        val fullName = instruction.javaClass.getMethod("getFullName")
                            .invoke(instruction) as? String ?: return
                        if (fullName != ASR_RECOGNIZE_RESULT) return
                        val payload = instruction.javaClass.getMethod("getPayload")
                            .invoke(instruction) ?: return
                        // 只认终态,中间的 partial 结果会随着用户说话不断变
                        val isFinal = payload.javaClass.getMethod("isFinal")
                            .invoke(payload) as? Boolean ?: false
                        if (!isFinal) return
                        val results = payload.javaClass.getMethod("getResults")
                            .invoke(payload) as? List<*> ?: return
                        val text = results.filterNotNull().joinToString("") {
                            (it.javaClass.getMethod("getText").invoke(it) as? String).orEmpty()
                        }
                        if (text.isBlank()) return
                        val dialogId = optionalString(
                            instruction.javaClass.getMethod("getDialogId").invoke(instruction)
                        ).orEmpty()

                        val ctx = currentApplicationContext()
                        val config = readHookConfig(ctx)
                        if (config != null) lastConfig = config

                        // 以 dialogId 区分新一轮，不能把“几点”“停止”等短指令当成尾音吞掉。
                        if (dialogId.isBlank()) return
                        val wasOurs = replyTurns.ownsCurrent()
                        val previousKey = replyTurns.keyFor(lastDialogId)
                        val turn = replyTurns.capture(dialogId, text, asr = true)
                        if (!replyTurns.isCurrent(turn.key)) return
                        if (turn.key != previousKey) {
                            stopMutePump()
                            if (wasOurs) stopOurTts()
                        }
                        lastQueryText = text
                        lastQueryTime = System.currentTimeMillis()
                        // 原文单独留一份 —— 稍后 setQueryInfo 会用改写过的版本覆盖 lastQueryText
                        lastAsrText = text
                        lastAsrTime = lastQueryTime
                        if (dialogId.isNotBlank()) lastDialogId = dialogId
                        Log.i(TAG, "asr final: dialogId=$dialogId text=$text")

                        // 这一刻就能判定"这句我们自己答" → 立刻起泵,比小爱起播早得多。
                        //
                        // 发消息类**必须**和查看类一样在这里起泵,不能只靠 setQueryInfo 那处。
                        // 实测「打开微信给文件传输助手发你好」:
                        //   41.625 asr final → 41.924 SpeechSynthesizer.Speak(小爱 299ms 就开口)
                        //   → 42.008 mute pump started(setQueryInfo 路径,383ms)
                        // 晚了 84ms,那句"暂不支持微信双开功能"就漏出去了 —— 现象是先播报
                        // 失败话术、然后消息才被我们发出去。查看类当初就是因为这个把泵前移到
                        // 这里的(见 setQueryInfo 那处注释),发消息这条路当时漏打了。
                        if (config != null && config.enabled && config.isUsable &&
                            !isAiTakeoverSkip(text, config)) {
                            replyTurns.claim(turn.key)
                            session(dialogId).takenOver = true
                            startMutePump(dialogId)
                            startTrackedCall(turn.key, dialogId, turn.text, config)
                        }
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookAsrResult error: $t")
                    }
                }
            })
            Log.i(TAG, "hooked ${symbols.asrProcessorClass}.processed (asr)")
        } catch (t: Throwable) {
            Log.i(TAG, "hook asr fail: $t")
        }
    }

    // 接管(而不是拦掉)小爱那张固定话术卡。
    //
    // 为什么改成接管:自建卡片走了十几轮都渲染不出来 —— 真机日志显示我们的卡
    // createViewHolder/bindView/onCardAttached 全跑过,TextView 也确实 vis=VISIBLE、h=98、
    // 父容器可见,但屏幕上就是没有,说明它被插进了一个不上屏的容器。
    // 而小爱**自己**那张话术卡("应用已经不支持这个功能啦")是能正常显示的 —— 同一时刻、
    // 同一场景,它的卡看得见。既然如此就别再往里塞自己的卡:让小爱照常创建、布局、显示,
    // 我们只把内容换成自己的。容器、时机、显示模式全部沿用它的机制。
    private fun hookToastCard(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.toastOperationClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.toastOperationClass} not found: $e")
            return
        }
        val baseCard = cl.loadClass("com.xiaomi.voiceassistant.card.a")
        val targets = clazz.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) &&
                baseCard.isAssignableFrom(it.returnType) &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "${symbols.toastOperationClass} card factory (int)->Card not found")
            return
        }
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val card = param.result ?: return
                            val op = param.thisObject
                            val opDialogId = try {
                                op.javaClass.getMethod("getDialogId").invoke(op) as? String
                            } catch (t: Throwable) { null }.orEmpty()
                            // 注意:这类固定话术 Operation 的 dialogId 是合成字面量
                            // ("fakeDialogId"/"fakeErrorDialogId"),**不是**真实对话 id,
                            // 匹配不上很正常。用"静音泵是否在为当前交互运行"来判定这轮是否归我们。
                            claimToastCard(card, opDialogId, via = "toastOp.${m.name}")
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookToastCard error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked ${symbols.toastOperationClass}.${m.name}(int)")
            } catch (t: Throwable) {
                Log.i(TAG, "hook ${symbols.toastOperationClass}.${m.name} fail: $t")
            }
        }
    }

    // 拦掉小爱对"杀/清后台"的原生反应:UIControllerNavigate.OPEN_BACKGROUND_APPS 会由 z0()
    // 模拟按下最近任务键(KEYCODE_APP_SWITCH),把系统"清后台"界面划出来。我们已经接管这轮、
    // 由 root shell 真正 force-stop,所以这下按键跳过即可 —— 否则先划出最近任务界面再由我们杀,
    // 观感是两边各干各的。只在 ownsKillTurnNow()(确是我们接管的杀后台轮)时跳过,不误伤正常导航。
    private fun hookBackgroundAppsNav(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.uiNavOperationClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.uiNavOperationClass} not found: $e")
            return
        }
        // z0() 无参、只做 setSimulateKeyEvent(187);按名字精确取,取不到就算了(混淆改名时不至于拖垮别的)
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "z0" && it.parameterTypes.isEmpty()
        }
        if (method == null) {
            Log.i(TAG, "${symbols.uiNavOperationClass}.z0() not found (obfuscation changed?)")
            return
        }
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!ownsKillTurnNow()) return
                        Log.i(TAG, "skip native OPEN_BACKGROUND_APPS (kill taken over): \"$lastQueryText\"")
                        param.result = null   // void:置结果即跳过原方法,不模拟按键、不划出最近任务
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookBackgroundAppsNav error: $t")
                    }
                }
            })
            Log.i(TAG, "hooked ${symbols.uiNavOperationClass}.z0 (OPEN_BACKGROUND_APPS)")
        } catch (t: Throwable) {
            Log.i(TAG, "hook ${symbols.uiNavOperationClass}.z0 fail: $t")
        }
    }

    // 读 com.xiaomi.ai.api.common 那套 Optional 风格的包装(isPresent/get)
    private fun optionalString(opt: Any?): String? {
        if (opt == null) return null
        return try {
            val present = opt.javaClass.getMethod("isPresent").invoke(opt) as? Boolean ?: false
            if (!present) null else opt.javaClass.getMethod("get").invoke(opt)?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    // 拦第三条跳转路:AgentActionManager。两个 executeActionsAsync 重载都挂上,
    // 顺带把它们汇入的 execute 也挂上,免得有调用方绕过重载直接进去。
    private fun hookAgentAction(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.agentActionClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.agentActionClass} not found: $e")
            return
        }
        val targets = clazz.declaredMethods.filter {
            it.name == "executeActionsAsync" || it.name == "execute"
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "${symbols.agentActionClass}.executeActionsAsync not found")
            return
        }
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // 第一个 Agent.Action 类型的参数就是动作本体
                            // (execute 的静态桥接版第一个参数是 s0 自己,所以按类型找而不是按位置)
                            val action = param.args.firstOrNull {
                                it != null && it.javaClass.name.endsWith("Agent\$Action")
                            } ?: return
                            @Suppress("UNCHECKED_CAST")
                            val specs = (action.javaClass.getMethod("getAction").invoke(action)
                                as? List<String>).orEmpty()
                            if (!shouldBlockAgentAction(specs)) return
                            Log.i(TAG, "block agent action: query=\"$lastQueryText\" specs=$specs")
                            param.result = if (m.returnType == java.lang.Boolean.TYPE) true else null
                            onViewJumpBlocked(lastDialogId)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookAgentAction error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked ${symbols.agentActionClass}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            } catch (t: Throwable) {
                Log.i(TAG, "hook ${symbols.agentActionClass}.${m.name} fail: $t")
            }
        }
    }

    // 这条 Agent Action 该不该拦。判据同 shouldBlockJump:只看这一轮归不归我们,
    // 不看动作打向哪个包。specs 只用来确认"这确实是条动作指令"。
    private fun shouldBlockAgentAction(specs: List<String>): Boolean {
        if (specs.isEmpty()) return false
        return ownsCurrentTurn()
    }

    // 白名单直通命中的正则,编译失败(用户手写的正则语法有误)就当不生效处理,别把整个接管功能拖垮
    @Volatile private var skipTakeoverRegexCache: Pair<String, Regex?>? = null

    // 判定这句问话是不是命中了"白名单直通"正则:命中就完全不接管,原生行为照旧
    private fun isAiTakeoverSkip(q: String, cfg: AiConfig): Boolean {
        if (!cfg.skipTakeoverEnabled) return false
        val pattern = cfg.skipTakeoverPattern
        if (pattern.isBlank()) return false // 空正则不当"匹配所有"处理,避免误配置吞掉全部问话
        val cached = skipTakeoverRegexCache
        val regex = if (cached != null && cached.first == pattern) {
            cached.second
        } else {
            val compiled = runCatching { Regex(pattern) }
                .onFailure { Log.w(TAG, "skip-takeover pattern invalid, ignored: $pattern", it) }
                .getOrNull()
            skipTakeoverRegexCache = pattern to compiled
            compiled
        }
        return regex?.containsMatchIn(q) == true
    }

    // 命中放行词 = 用户明确要"打开/启动"点什么,而不是在问问题
    private fun hitsJumpAllowWord(q: String, cfg: AiConfig): Boolean {
        val allowWords = cfg.jumpAllowWords.split(',', ' ', '，', '、')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return allowWords.any { q.contains(it) }
    }

    // 判定一句话是不是"查看类、应拦跳转"的候选(不含跳转目标判定,那个只在 startActivitySafely 时才知道)
    private fun isViewBlockCandidate(q: String, cfg: AiConfig): Boolean {
        if (!cfg.blockViewJump) return false
        if (q.isBlank()) return false
        if (hitsJumpAllowWord(q, cfg)) return false
        // 必须是"查看类"问话
        return VIEW_WORDS.any { q.contains(it) }
    }

    // 这里曾经有个 isNativeLaunchCommand():想让"打开X"这类纯指令跳过模型调用。
    // 已撤销,两个理由都是实测打脸的:
    //  1) 它要解决的问题不存在 —— 以为"打开系统更新"末尾会白播一句"没有得到答案",
    //     实际日志里紧跟着就是 "not taken over, skip speaking",那句话从来没被念出来。
    //     真实收益只是省一次模型调用,用户感知不到。
    //  2) 代价是真的 —— "打开微信给文件传输助手发你好"里"发你好"不在 SEND_MESSAGE_VERBS,
    //     isSendMessageCommand 认不出来,整句被当成"打开微信"直接早退,发消息彻底失效。
    // 顺带记一笔:就算不撤,它在这条路上也基本失灵 —— setQueryInfo 拿到的文本是
    // 小爱改写过的("打开系统更新"→"系统更新"),放行词"打开"到这一步已经没了。

    /**
     * 是不是"让某个应用给某人发消息"。命中即由我们接管:起静音泵按住小爱那句
     * 固定话术,并允许模型调用动作类工具(send_message)。
     */
    private fun isSendMessageCommand(q: String): Boolean {
        if (q.isBlank()) return false
        if (SEND_MESSAGE_APPS.none { q.contains(it, ignoreCase = true) }) return false
        // 先按句式认(覆盖"发<任意内容>"),认不出再退回动词表(覆盖"告诉X…""带句话"这种没有"发"的)
        return SEND_MESSAGE_PATTERN.containsMatchIn(q) || SEND_MESSAGE_VERBS.any { q.contains(it) }
    }

    /**
     * 是不是"杀后台/清后台/强制停止应用"。命中即由我们接管:起静音泵按住小爱那句
     * "已为你清理/只能帮你到这啦",并把动作闸打开(允许模型用 run_shell 跑 am force-stop / kill)。
     *
     * 见 [KILL_BG_VERBS] 处的注释:动词 + 「后台/进程」锚点,或"强制/强行停止"独立成立。
     */
    private fun isKillBackgroundCommand(q: String): Boolean {
        if (q.isBlank()) return false
        if (KILL_BG_STRONG.containsMatchIn(q)) return true
        if (KILL_BG_HARD_VERBS.any { q.contains(it) }) return true
        return KILL_BG_VERBS.any { q.contains(it) } && KILL_BG_ANCHORS.any { q.contains(it) }
    }

    /** 是不是"冻结/解冻/清理缓存"类。命中即接管、放行 app_state_control(冻结/清缓存)。 */
    private fun isAppControlCommand(q: String): Boolean =
        q.isNotBlank() && APP_CTRL_PATTERN.containsMatchIn(q)

    /** 是不是"连接/断开蓝牙设备"类。命中即接管、放行 bluetooth_control 的 connect/disconnect。 */
    private fun isBtConnectCommand(q: String): Boolean {
        if (q.isBlank() || BT_CONN_QUESTION.containsMatchIn(q)) return false
        val hasVerb = BT_CONN_VERBS.any { q.contains(it, ignoreCase = true) }
        val hasAnchor = BT_CONN_ANCHORS.any { q.contains(it, ignoreCase = true) }
        return hasVerb && hasAnchor
    }

    /**
     * 这一轮交互归不归我们 —— 所有"要不要拦"的判定都问这一个问题。
     *
     * **刻意不看跳转目标是哪个 App。** 早先的做法是"问话是查看类 **且** 目标在
     * SETTINGS_PACKAGES 白名单里"才拦,那个白名单是个填不完的坑:实测"查看系统版本"
     * 跳的是 com.android.updater(「系统更新」是独立 app,不在设置里),不在名单里就漏了;
     * 而且它的失败是静默的 —— 跳转照常发生,日志里没有任何异常,只能靠真机复现才发现。
     *
     * 真正的判据在更上游:setQueryInfo 那一刻(比跳转早约 300ms)如果判定这句是查看类,
     * 就已经 pendingViewAnswer.add + 起静音泵 + 调模型了,"这轮我们自己答"是既成事实。
     * 此时小爱还要起 Activity,错不在"目标像不像设置",而在这轮已经不归它了 ——
     * 跳 App 是它回答问题的方式,而它的答案已经被我们抢走。
     *
     * 所以目标是什么包根本不重要,判据只剩:用户是在**问问题**(而不是让"打开"什么),
     * 且这次跳转和那句问话属于同一次交互。
     */
    private fun ownsCurrentTurn(): Boolean {
        val cfg = lastConfig ?: return false
        // 问话要足够新(和这次跳转是同一次交互);太旧就不管,避免误伤后续手动/其它跳转
        if (System.currentTimeMillis() - lastQueryTime > 12_000L) return false
        if (viewBlockCandidateNow(cfg)) return true
        // 杀后台类:我们已经接管这轮的 force-stop/kill,小爱原生那个"清理最近任务"动作
        // (会先放一段系统清后台动画)就不该再放出来 —— 否则先播清理动画、我们再杀,
        // 观感是两边各干各的。这条原生动作和查看类的跳转走的是同几个收口(AgentAction /
        // IntentLaunch / startActivitySafely),所以在这里一并认领即可,不用新挂 hook。
        // 只在 AI 接管开启时拦:关了我们不杀,得让小爱原生清后台照常。
        if (cfg.enabled && killBackgroundCommandNow()) return true
        return false
    }

    /**
     * 「这轮是不是查看类」的**当下**判定:ASR 原文和 setQueryInfo 改写版一起看。
     *
     * 单看 [lastQueryText] 会被小爱的改写坑到(见 [lastAsrText] 的注释)。取并集,且:
     *  - **放行词任一命中就放行** —— "打开系统更新"改写成"系统更新"后"打开"没了,
     *    只看改写版会把用户明确要求的跳转拦下来。放行是安全侧。
     *  - 查看词任一命中就算候选 —— 改写吃掉查看词时靠原文兜住。
     */
    private fun viewBlockCandidateNow(cfg: AiConfig): Boolean {
        if (!cfg.blockViewJump) return false
        val texts = currentQueryTexts()
        if (texts.isEmpty()) return false
        if (texts.any { hitsJumpAllowWord(it, cfg) }) return false
        return texts.any { t -> VIEW_WORDS.any { t.contains(it) } }
    }

    /**
     * 本轮所有可用的问话文本(改写版 + ASR 原文,去重)。
     *
     * ASR 原文带时效:它是上一次说话留下的,如果这轮压根没走语音(打字输入)就不该再算数,
     * 否则会拿上一句的内容判定这一句。用和跳转拦截同样的 12 秒窗口。
     */
    /** 「这轮是不是发消息类」的当下判定。同样两个文本都看,理由见 [viewBlockCandidateNow]。 */
    private fun sendMessageCommandNow(): Boolean =
        currentQueryTexts().any { isSendMessageCommand(it) }

    /** 「这轮是不是杀后台类」的当下判定。同样两个文本都看,理由见 [viewBlockCandidateNow]。 */
    private fun killBackgroundCommandNow(): Boolean =
        currentQueryTexts().any { isKillBackgroundCommand(it) }

    /** 「这轮是不是冻结/清缓存类」的当下判定。 */
    private fun appControlCommandNow(): Boolean =
        currentQueryTexts().any { isAppControlCommand(it) }

    /** 「这轮是不是连接/断开蓝牙设备类」的当下判定。 */
    private fun btConnectCommandNow(): Boolean =
        currentQueryTexts().any { isBtConnectCommand(it) }

    /**
     * 这轮是不是"我们已接管的杀后台"。用来决定要不要拦小爱那下"按最近任务键"的原生动作
     * (见 [UI_NAV_OPERATION_CLASS])。比 [ownsCurrentTurn] 收得更窄:只认杀后台类,不含查看类 ——
     * "查看后台应用"这种用户其实是想看最近任务的,不该把界面拦掉。
     */
    private fun ownsKillTurnNow(): Boolean {
        val cfg = lastConfig ?: return false
        if (!cfg.enabled) return false
        if (System.currentTimeMillis() - lastQueryTime > 12_000L) return false
        return killBackgroundCommandNow()
    }

    private fun currentQueryTexts(): List<String> {
        val out = ArrayList<String>(2)
        if (lastQueryText.isNotBlank()) out.add(lastQueryText)
        if (lastAsrText.isNotBlank() && lastAsrText != lastQueryText &&
            System.currentTimeMillis() - lastAsrTime <= 12_000L
        ) out.add(lastAsrText)
        return out
    }

    // 小爱答不上来的兜底:拦截 m2.startActivitySafely(Intent, String)。
    // 这个方法返回 int 错误码,0=成功、非 0 上层会当成启动失败(又触发一轮兜底播报),
    // 所以拦下来必须回 0 冒充成功。
    private fun hookWebSearchFallback(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.intentUtilsClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.intentUtilsClass} not found: $e")
            return
        }
        val method = try {
            clazz.getDeclaredMethod("startActivitySafely", android.content.Intent::class.java, String::class.java)
        } catch (e: Throwable) {
            Log.i(TAG, "m2.startActivitySafely(Intent,String) not found: $e")
            return
        }
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val intent = param.args[0] as? android.content.Intent ?: return
                        val srcUri = (param.args[1] as? String).orEmpty()
                        if (!shouldBlockWebSearch(intent, srcUri)) return
                        Log.i(TAG, "block web-search fallback: query=\"$lastQueryText\" intent=$intent srcUri=$srcUri")
                        param.result = 0   // 0 = 启动成功,别让上层判定失败
                        onViewJumpBlocked(lastDialogId)
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookWebSearchFallback error: $t")
                    }
                }
            })
            Log.i(TAG, "hooked m2.startActivitySafely(Intent, String)")
        } catch (t: Throwable) {
            Log.i(TAG, "hook m2.startActivitySafely fail: $t")
        }
    }

    // 第四条跳转路:Launcher.LaunchApp 指令 → LaunchAppOperation → m2 直接起 Activity
    //   (实测 "现在连接的WiFi密码是多少" → action=android.settings.WIFI_SETTINGS)
    // 它不经过 startActivitySafely,所以前面几个 hook 都拦不到。
    // m2.k(Context, Intent) 是 m2 里最底层那个真正启动 Activity 的方法 —— 早先的调用栈里
    //   m2.k ← m2.startIntentWithSplitSetting ← m2.n ← m2.l ← m2.startActivitySafely
    // 说明它在所有路径的最下游,拦这一层四条路都覆盖。
    // 方法名 k 是混淆产物,所以按 (Context, Intent) 签名找,不写死名字。
    private fun hookIntentLaunch(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.intentUtilsClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.intentUtilsClass} not found: $e")
            return
        }
        val targets = clazz.declaredMethods.filter {
            val p = it.parameterTypes
            p.size == 2 && p[0] == Context::class.java && p[1] == android.content.Intent::class.java
        }
        if (targets.isEmpty()) {
            Log.i(TAG, "m2.(Context,Intent) launcher not found")
            return
        }
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args[1] as? android.content.Intent ?: return
                            if (!ownsCurrentTurn()) return
                            Log.i(TAG, "block intent launch: query=\"$lastQueryText\" intent=$intent")
                            param.result = when (m.returnType) {
                                java.lang.Boolean.TYPE -> true
                                Integer.TYPE -> 0
                                else -> null
                            }
                            onViewJumpBlocked(lastDialogId)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookIntentLaunch error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked m2.${m.name}(Context, Intent)")
            } catch (t: Throwable) {
                Log.i(TAG, "hook m2.${m.name} fail: $t")
            }
        }
    }

    // 压制小爱写进历史库的兜底文案(只在我们拦了跳转、且自己的答案还没写进去的这段时间内)
    private fun hookChatHistory(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.chatDbManagerClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.chatDbManagerClass} not found: $e")
            return
        }
        // 拦 insert(bean) 而不是 recordToSpeak:那句兜底文案实测走的是 m(str, dialogId)
        // (即 recordToSpeakInternal),recordToSpeak(String) 根本没被调到。
        // 这几个入口最后都汇进 insert,拦这一处才不用赌混淆方法名。
        // 注意:a extends vl0.a<e, Long>,insert(e) 是泛型重写,编译器会另外生成 bridge 方法
        // insert(Object)。只挂其中一个会漏 —— m() 里是 getInstance().insert(eVar),静态类型
        // 就是 e,直接调真方法绕开 bridge。所以两个重载全挂上。
        val methods = clazz.declaredMethods.filter {
            it.name == "insert" && it.parameterTypes.size == 1
        }
        if (methods.isEmpty()) {
            Log.i(TAG, "ChatDbManager.insert(bean) not found")
            return
        }
        for (method in methods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (writingHistory.get() == true) return   // 我们自己写的,放行
                        val bean = param.args[0] ?: return
                        // 只管小爱说的话(isSend=0),用户自己的提问要留在历史里
                        val isSend = try {
                            bean.javaClass.getMethod("getIsSend").invoke(bean) as? Int ?: 0
                        } catch (t: Throwable) { 0 }
                        if (isSend > 0) return
                        val text = try {
                            bean.javaClass.getMethod("getUserContent").invoke(bean) as? String
                        } catch (t: Throwable) { null } ?: return
                        if (!shouldSuppressHistory(text)) return
                        Log.i(TAG, "suppress history insert: ${text.take(40)}")
                        param.result = false
                    } catch (t: Throwable) {
                        Log.i(TAG, "hookChatHistory error: $t")
                    }
                }
                })
                Log.i(TAG, "hooked ChatDbManager.insert(${method.parameterTypes[0].simpleName})")
            } catch (t: Throwable) {
                Log.i(TAG, "hook insert fail: $t")
            }
        }
    }

    // 要不要把这条历史写入压掉。两种情况都压:
    //  1) 内容就是那句兜底话术 —— 不管它写在拦截之前还是之后(实测两头都有)
    //  2) 这次对话被我们拦了、自己的答案还没写进去 —— 期间小爱说的都不该留在历史里
    // 都限制在最近一次问话的 60 秒内,免得把之后的正常对话也吃掉。
    private fun shouldSuppressHistory(text: String): Boolean {
        val cfg = lastConfig ?: return false
        if (!cfg.blockWebSearch || !cfg.enabled) return false
        if (System.currentTimeMillis() - lastQueryTime > 60_000L) return false
        if (FALLBACK_MARKERS.any { text.contains(it) }) return true
        return sessions.values.any { it.historyPending }
    }

    // 把我们自己的答案写进历史库,让 App 里的「历史对话」也显示替换后的内容
    private fun writeAnswerToHistory(dialogId: String, answer: String) {
        val s = sessionOrNull(dialogId) ?: return
        if (!s.historyPending) return
        if (!s.historyWritten.compareAndSet(false, true)) return   // 只写一次(原来靠 Set.add 的返回值)
        val cl = targetClassLoader ?: return
        try {
            writingHistory.set(true)
            val clazz = cl.loadClass(symbols.chatDbManagerClass)
            val instance = clazz.getMethod("getInstance").invoke(null) ?: return
            clazz.getDeclaredMethod("recordToSpeak", String::class.java).invoke(instance, answer)
            Log.i(TAG, "answer written to history dialogId=$dialogId")
        } catch (t: Throwable) {
            Log.i(TAG, "writeAnswerToHistory failed dialogId=$dialogId: $t")
            s.historyWritten.set(false)
        } finally {
            writingHistory.set(false)
            s.historyPending = false
        }
    }

    // 这次跳转是不是"小爱答不上来 → 甩给全局搜索"的兜底
    private fun shouldBlockWebSearch(intent: android.content.Intent, srcUri: String): Boolean {
        val cfg = lastConfig ?: return false
        if (!cfg.blockWebSearch) return false
        // 只有开了 AI 接管才拦 —— 否则拦掉又没人回答,用户就什么都得不到了
        if (!cfg.enabled || !cfg.isUsable) return false
        // 问话要足够新,和这次跳转属于同一次交互
        if (System.currentTimeMillis() - lastQueryTime > 12_000L) return false
        if (lastQueryText.isBlank()) return false
        // 用户明确说了"搜索/百度…" → 他要的就是搜索,放行
        val allowWords = cfg.webSearchAllowWords.split(',', ' ', '，', '、')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (allowWords.any { lastQueryText.contains(it) }) return false
        return isWebSearchIntent(intent, srcUri)
    }

    private fun isWebSearchIntent(intent: android.content.Intent, srcUri: String): Boolean {
        val action = intent.action.orEmpty()
        if (action == android.content.Intent.ACTION_WEB_SEARCH) return true
        if (action == android.content.Intent.ACTION_SEARCH) return true
        val pkg = intent.component?.packageName ?: intent.`package`.orEmpty()
        if (pkg == QUICK_SEARCH_PKG) return true
        if (intent.data?.scheme == "qsb") return true
        // 兜底 URI 形如 intent://query?words=后台程序&web_search=false#Intent;...
        return srcUri.contains("web_search=") || srcUri.contains("query?words=")
    }

    // 拦掉查看类跳转之后:掐掉那句"好的",并把这个对话标记为接管(答案就绪后塞进小爱自己的 toast 卡)
    private fun onViewJumpBlocked(dialogId: String) {
        try { startMutePump(dialogId) } catch (t: Throwable) { }
        val cfg = lastConfig ?: return
        if (!cfg.enabled) return                // 没开 AI 接管:只拦跳转 + 静音
        if (dialogId.isBlank()) return
        // 注意:不要把它标成 takenOver —— 那是 RN bridge 文本接管用的,
        // 会触发"拦截控制信令"逻辑导致小爱误判失败并播报"只能帮你到这了"。
        session(dialogId).pendingViewAnswer = true
        // 从现在起压制小爱写进历史库的兜底文案,直到我们的答案写进去
        session(dialogId).historyPending = true
        // 关键:趁界面还活着,立刻加一张占位卡(答案 6 秒后才到,那时界面已收起就晚了)
        ensureAnswerCard(dialogId)
    }

    // 捕获当前活跃的结果渲染 sink:App(flowableresult.d)和悬浮窗(widget.d)各用一套,
    // 两套的 addCard 都 hook,谁最近被调用(当前会话的 query/loading 卡就是它加的)谁就是活跃 sink。
    private fun hookCardSinks(cl: ClassLoader) {
        for (className in listOf(symbols.flowControllerClass, symbols.floatManagerClass)) {
            val clazz = try {
                cl.loadClass(className)
            } catch (e: Throwable) {
                Log.i(TAG, "$className not found: $e")
                continue
            }
            for (m in clazz.declaredMethods) {
                if (m.name != "addCard") continue
                if (m.parameterTypes.size != 1) continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 别把我们自己加的卡也当成"活跃 sink 信号"——不影响,直接更新即可
                            activeCardSink = WeakReference(param.thisObject)
                            // 排查用(2026-07-20):我们认领的 toast 卡 30 秒都没绑过视图,
                            // 怀疑它压根没被加进渲染列表。这个 hook 原来是静默的,
                            // "addCard 没被调用"和"调用了但卡没上屏"看不出区别 —— 一轮就几条,打得起。
                            val c = param.args.getOrNull(0)
                            if (c != null) {
                                try {
                                    val replacement = replyCardForSink(cl, c)
                                    if (replacement != null) {
                                        if (replyAddDepth.get() == 0 && replacement !== c && cardSinks[replacement]?.get() != null) {
                                            Log.i(TAG, "[reply-ui] skip duplicate source card for ${replyCards.keyFor(replacement)}")
                                            param.result = null
                                            return
                                        }
                                        replyAddDepth.set((replyAddDepth.get() ?: 0) + 1)
                                        param.setObjectExtra("replyCardAdd", true)
                                        // 两层 sink（浮窗 -> 分发器）会经过同一个对象，只替换参数，不再次 addCard。
                                        param.args[0] = replacement
                                        cardSinks[replacement] = WeakReference(param.thisObject)
                                        if (replacement !== c) Log.i(TAG, "[reply-ui] replace ${c.javaClass.name} with native text card@${System.identityHashCode(replacement)}")
                                    }
                                } catch (t: Throwable) { Log.w(TAG, "[reply-ui] card replacement failed: $t") }
                            }
                            Log.i(TAG, "[sink] ${param.thisObject.javaClass.simpleName}" +
                                    "@${System.identityHashCode(param.thisObject)} addCard " +
                                    "${c?.javaClass?.name}@${System.identityHashCode(c)}")
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.getObjectExtra("replyCardAdd") == true) {
                                replyAddDepth.set(((replyAddDepth.get() ?: 0) - 1).coerceAtLeast(0))
                                if (param.hasThrowable()) param.args.getOrNull(0)?.let { cardSinks.remove(it) }
                            }
                        }
                    })
                    Log.i(TAG, "hooked card sink $className.addCard")
                } catch (t: Throwable) {
                    Log.i(TAG, "hook card sink $className fail: $t")
                }
            }
        }
    }

    // hookCardSinks 是"守株待兔"式的:要先观察到有人调过 addCard 才知道 sink 是谁。
    // 但小爱刚重启后的第一次交互,拦截发生在唤醒后几十毫秒内,那时一次 addCard 都还没发生
    // (真机日志:block 与 query captured 只差 24ms,紧接着就是 no active card sink yet),
    // 占位卡就加不上了。这里主动去要 FloatManager(悬浮窗那套 sink,类型正是 widget.d)兜底。
    private fun resolveFloatManager(): Any? {
        return try {
            val ctx = currentApplicationContext() ?: return null
            val uiManagerClass = ctx.classLoader.loadClass("com.xiaomi.voiceassistant.UiManager")
            val instance = uiManagerClass.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                ?: return null
            val fm = instance.javaClass.getMethod("getFloatManager").invoke(instance)
            if (fm != null) {
                Log.i(TAG, "resolved float manager fallback: ${fm.javaClass.name}@${System.identityHashCode(fm)}")
            }
            fm
        } catch (t: Throwable) {
            Log.i(TAG, "resolveFloatManager failed: $t")
            null
        }
    }

    // 保证有一张答案卡:第一次(拦截时)造卡并加进当前活着的流式列表,内容用答案或占位;
    // 之后(答案就绪)再调一次,把已有卡片的文本更新为答案。
    private fun replyCardForSink(cl: ClassLoader, incoming: Any): Any? {
        replyCards.keyFor(incoming)?.let { return incoming }
        if (!replyTurns.ownsCurrent()) return null
        if (!cl.loadClass(symbols.rnCardClass).isInstance(incoming) &&
            !cl.loadClass(symbols.flowToastCardClass).isInstance(incoming)) return null
        val history = runCatching { incoming.javaClass.getMethod("isHistoryCard").invoke(incoming) as? Boolean }.getOrNull()
        if (history == true) return null
        val id = runCatching { incoming.javaClass.getMethod("getDialogId").invoke(incoming) as? String }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val key = replyTurns.attachOutput(id) ?: return null
        if (!replyTurns.isCurrent(key)) return null
        val position = runCatching { incoming.javaClass.getMethod("getInsertPosition").invoke(incoming) as Int }.getOrDefault(0)
        val card = nativeReplyCard(cl, key, id, position)
        session(id).takenOver = true
        session(id).pendingViewAnswer = true
        session(id).answerCard = WeakReference(card)
        utteranceDialogs.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(id)
        return card
    }

    private fun nativeReplyCard(cl: ClassLoader, key: String, dialogId: String, position: Int = 0): Any =
        replyCards.getOrCreate(key) {
            val type = cl.loadClass(symbols.flowToastCardClass)
            val text = utteranceAnswers[key] ?: sessionOrNull(dialogId)?.aiAnswer ?: THINKING_PLACEHOLDER
            val card = type.getConstructor(Integer.TYPE, String::class.java).newInstance(position, text)
            type.getMethod("setDialogId", String::class.java).invoke(card, dialogId)
            ourCardTexts[System.identityHashCode(card)] = text
            Log.i(TAG, "[reply-ui] created key=$key card@${System.identityHashCode(card)}")
            card
        }

    private fun ensureAnswerCard(dialogId: String) {
        val key = replyTurns.keyFor(dialogId) ?: return
        if (!replyTurns.isCurrent(key) || !replyTurns.ownsCurrent()) return
        val cl = targetClassLoader ?: return
        Handler(Looper.getMainLooper()).post {
            if (!replyTurns.isCurrent(key)) return@post
            try {
                val card = nativeReplyCard(cl, key, dialogId)
                val s = session(dialogId)
                s.answerCard = WeakReference(card)
                s.pendingViewAnswer = true
                val text = s.aiAnswer ?: utteranceAnswers[key] ?: THINKING_PLACEHOLDER
                ourCardTexts[System.identityHashCode(card)] = text
                card.javaClass.getMethod("updateCardText", String::class.java).invoke(card, text)
                // 卡片经过 r1 -> t0 两层路由不代表换了容器。已添加的对象不再重复 addCard。
                if (cardSinks[card]?.get() == null) {
                    val sink = activeCardSink?.get() ?: resolveFloatManager() ?: return@post
                    sink.javaClass.getMethod("addCard", cl.loadClass("com.xiaomi.voiceassistant.card.a"))
                        .invoke(sink, card)
                    cardSinks[card] = WeakReference(sink)
                    Log.i(TAG, "[reply-ui] added fallback key=$key card@${System.identityHashCode(card)}")
                }
                forceShowToastViewHolder(card, text)
                Log.i(TAG, "[reply-ui] text updated key=$key len=${text.length}")
            } catch (t: Throwable) { Log.w(TAG, "[reply-ui] render failed key=$key: $t") }
        }
    }

    // 在我们自己那张卡真正绑定视图的瞬间,把文本强制填进去并设为可见。
    // 只靠构造函数传文本是不够的:实测占位卡 createViewHolder/bindView/onCardAttached 全跑了,
    // 屏幕上却始终空白 —— bindView 内部会因为某些状态没满足而不给 toastTv 上文本。
    // 这里在 bindView 之后补一刀,并把 TextView 的实际状态打出来,便于确认到底有没有生效。
    private fun hookToastCardBind(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.flowToastCardClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.flowToastCardClass} not found: $e")
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name != "bindView") continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val card = param.thisObject
                            // 还没被认领、但这轮确实归我们 → 就地认领。
                            // 有些话术卡不经过 jb0.vd(微信双开那句就是),只能在这兜底,
                            // 否则语音被静音泵按住了、文字却照样显示出来。
                            // bindView 也会用于历史记录；只回填已登记卡片，绝不认领未知卡片。
                            val text = ourCardTexts[System.identityHashCode(card)]
                            // 排查用(2026-07-20):无差别记一笔谁被 bind 了。
                            // 只在"是我们的卡"时才做事,于是"没有任何 toast 卡被 bind"
                            // 和"别人的卡被 bind 了、我们的没有"在日志里长得一模一样。
                            Log.i(TAG, "[bind] card@${System.identityHashCode(card)}" +
                                    " ours=${text != null}")
                            if (text == null) return
                            forceShowToastViewHolder(card, text)
                        } catch (t: Throwable) {
                            Log.i(TAG, "hookToastCardBind error: $t")
                        }
                    }
                })
                Log.i(TAG, "hooked FlowTemplateToastCard.bindView(${m.parameterTypes.size} args)")
            } catch (t: Throwable) {
                Log.i(TAG, "hook FlowTemplateToastCard.bindView fail: $t")
            }
        }
    }

    /**
     * 认领一张 toast 卡:归到我们当前服务的 dialogId 名下,并把它的文字换成我们的答案(或占位)。
     *
     * 抽出来是因为有**两个**入口:
     *  - `jb0.vd.g0/i0` —— 通用话术卡的产地,查看类走这条。
     *  - `FlowTemplateToastCard.bindView` —— 兜底。实测微信双开那句
     *    ("暂不支持微信双开功能")由 ControlWxAudioSdkOperation 产出,压根不经过 vd,
     *    于是卡片从没被认领,语音虽然被静音泵按住了,**文字还是原样留在屏幕上**。
     *    与其再去挂一个 Operation 类(混淆名每版都变,而且下次换个话术又是新的类),
     *    不如挂在所有 toast 卡都必经的渲染入口上。
     *
     * @param onlyIfUnclaimed 兜底入口用:这轮已经有认领好的卡就别抢,避免把正主顶掉。
     */
    private fun claimToastCard(
        card: Any,
        opDialogId: String,
        via: String,
        onlyIfUnclaimed: Boolean = false
    ): Boolean {
        // 注意:这类固定话术 Operation 的 dialogId 是合成字面量
        // ("fakeDialogId"/"fakeErrorDialogId"),**不是**真实对话 id,匹配不上很正常。
        // 用"静音泵是否在为当前交互运行"来判定这轮是否归我们。
        val ours = isOurs(opDialogId)
        if (!ours && !isMutePumpActive()) return false

        val did = mutePumpDialogId.ifBlank { lastDialogId }
        if (did.isBlank()) return false

        val hash = System.identityHashCode(card)
        if (hash in commandeeredCards) return true          // already ours
        if (onlyIfUnclaimed && sessionOrNull(did)?.answerCard?.get() != null) return false

        // 一轮里 vd 那个 hook 会命中**两个**对象:先是 card.e(没有 updateCardText),
        // 再是 FlowTemplateToastCard(有)。后登记的覆盖先登记的,所以实测一直是能更新的
        // 那张赢 —— 但那是顺序运气。顺序一旦反过来,我们就攥着一张改不了字的卡,
        // 答案永远不上屏,现象就是"这轮没有卡片"。只认真正能改字的。
        val updater = try {
            card.javaClass.getMethod("updateCardText", String::class.java)
        } catch (t: Throwable) {
            Log.i(TAG, "skip non-updatable card@$hash class=${card.javaClass.name}")
            return false
        }
        val text = sessionOrNull(did)?.aiAnswer ?: THINKING_PLACEHOLDER
        session(did).answerCard = WeakReference(card)
        ourCardTexts[hash] = text
        try {
            updater.invoke(card, text)
        } catch (t: Throwable) {
            Log.i(TAG, "commandeer: updateCardText failed: $t")
        }
        commandeeredCards.add(hash)
        Log.i(TAG, "commandeered toast card@$hash via=$via opDialogId=$opDialogId -> dialogId=$did")
        return true
    }

    /**
     * 读卡片当前绑定的 ViewHolder;没绑定返回 null。
     *
     * **不要按名字找**。原来写的是 `card.javaClass.getDeclaredField("X3")`,有两个坑叠在一起:
     * `getDeclaredField` 只查类自己不查父类,而 `X3` 是混淆产物、换个版本就变名字 ——
     * 两种情况都抛 NoSuchFieldException,被 catch 成 null,于是日志打出
     * "view holder is null",**和"字段确实是 null"完全分不开**。
     * 2026-07-20 就是栽在这:探针显示认领后 2ms X3 就是 null 且 15 秒不变,
     * 差点据此断定"卡片压根没绑过视图"并去改渲染逻辑 —— 但那个 null 有可能只是找错了字段。
     *
     * 现在按**行为**认:遍历整条继承链上的字段,谁的值身上有 `getToastTv()` 谁就是 ViewHolder。
     * 找到后记住那个 Field 直接复用(每次全扫太贵,这个方法在探针里 200ms 一次)。
     */
    private fun viewHolderOf(card: Any): Any? {
        cachedVhField?.let { f ->
            if (f.declaringClass.isInstance(card)) {
                return runCatching { f.get(card) }.getOrNull()
            }
        }
        var c: Class<*>? = card.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val v = runCatching { f.isAccessible = true; f.get(card) }.getOrNull() ?: continue
                if (runCatching { v.javaClass.getMethod("getToastTv") }.isSuccess) {
                    if (cachedVhField == null) {
                        Log.i(TAG, "[vh] view holder field = ${c!!.simpleName}.${f.name}" +
                                " type=${v.javaClass.name}")
                    }
                    cachedVhField = f
                    return v
                }
            }
            c = c.superclass
        }
        return null
    }

    /** viewHolderOf 找到过的那个字段。null = 还没找到过(可能是没绑定,也可能是这个类真没有)。 */
    @Volatile
    private var cachedVhField: java.lang.reflect.Field? = null

    // 直接把已绑定 ViewHolder(字段 X3)里的 toastTv 设为可见并填文本
    private fun forceShowToastViewHolder(card: Any, text: String) {
        try {
            val vh = viewHolderOf(card)
            if (vh == null) {
                Log.i(TAG, "forceShow: no view holder bound, card@${System.identityHashCode(card)}" +
                        " class=${card.javaClass.name} cachedField=${cachedVhField?.name ?: "(none yet)"}")
                return
            }
            val tv = vh.javaClass.getMethod("getToastTv").invoke(vh) as? android.widget.TextView
            if (tv == null) {
                Log.i(TAG, "forceShow: toastTv is null, card@${System.identityHashCode(card)}")
                return
            }
            tv.visibility = android.view.View.VISIBLE
            tv.text = text
            Log.i(TAG, "forceShow: card@${System.identityHashCode(card)} tv set," +
                    " vis=${tv.visibility} h=${tv.height} parentVis=${(tv.parent as? android.view.View)?.visibility}")
        } catch (t: Throwable) {
            Log.i(TAG, "forceShowToastViewHolder failed: $t")
        }
    }

    // RN 的 JS 侧准备好接收数据时,会回调 native 的 rnStartReceiveInstruction(),
    // 此时卡片才把 Z3 置 true;在这之前 sendStreamDataToFront() 只会把 instruction 塞进队列,
    // 根本不会真正发给 JS。我们之前是绕过这个检查直接调 bridge.sendStreamData,
    // 所以如果 JS 还没 ready,数据就发到了空气里 —— 这正是"简单问题不显示"的原因。
    // 这里等 JS ready 之后再补一次注入。
    private fun hookRnJsReady(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.rnCardClass)
        } catch (e: Throwable) {
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name == "rnStartReceiveInstruction") {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val card = param.thisObject
                                val dialogId = card.javaClass.getMethod("getDialogId")
                                    .invoke(card) as? String ?: return
                                Log.i(TAG, "[rn] js ready dialogId=$dialogId hash=${System.identityHashCode(card)}")
                                if (dialogId.isNotBlank()) {
                                    val s = captureRnCard(card, dialogId)
                                    s.rnJsReady = true
                                    maybeInject(dialogId)
                                }
                            } catch (t: Throwable) {
                                Log.i(TAG, "[rn] js ready hook error: $t")
                            }
                        }
                    })
                    Log.i(TAG, "hooked rnStartReceiveInstruction")
                } catch (t: Throwable) {
                    Log.i(TAG, "hook rnStartReceiveInstruction fail: $t")
                }
            }
        }
    }

    // 读取卡片:RN JS 侧是否已经准备好接收数据
    private fun isRnFrontReady(dialogId: String): Boolean =
        sessionOrNull(dialogId)?.rnJsReady == true

    private fun captureRnCard(card: Any, dialogId: String): DialogSession {
        val s = session(dialogId)
        s.cardRef = WeakReference(card)
        val key = replyTurns.attachOutput(dialogId)
        if (key != null && replyTurns.isCurrent(key) && replyTurns.ownsCurrent()) {
            s.takenOver = true
            s.aiAnswer = s.aiAnswer ?: utteranceAnswers[key]
            utteranceDialogs.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(dialogId)
        }
        // RN ready 回调可能只 flush 了 Finish，没有普通 instruction 可供捕获 bridge。
        val bridgeType = targetClassLoader?.loadClass(symbols.bridgeClass) ?: return s
        var type: Class<*>? = card.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (!bridgeType.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                field.get(card)?.let { s.bridgeRef = WeakReference(it) }
            }
            type = type.superclass
        }
        return s
    }

    // TemplateReactNativeCard.onStop() 里,如果判定要清内存(getRNStatus)就会调用
    // RN delegate(kj0.n).onPause(),把底层 RN/JS 实例暂停掉。
    // 小爱自己的"简单问答"经常几百毫秒内就认为对话结束、走到这个 onStop,
    // 而我们调用第三方 AI 还要等几秒钟——RN 实例一旦被暂停,
    // 之后再怎么 sendStreamData 画面都不会更新。
    // 所以:在还没等到我们自己答案注入完成之前,直接跳过这次 onStop,保持 RN 存活。
    private fun hookRnCardStop(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.rnCardClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.rnCardClass} not found: $e")
            return
        }
        for (m in clazz.declaredMethods) {
            if (m.name == "onStop") {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val dialogId = param.thisObject.javaClass.getMethod("getDialogId")
                                    .invoke(param.thisObject) as? String ?: return
                                val s = sessionOrNull(dialogId)
                                if (s != null && s.takenOver && !s.injected.get()) {
                                    Log.i(TAG, "suppress onStop (keep RN alive) dialogId=$dialogId")
                                    param.result = null
                                }
                            } catch (t: Throwable) {
                                // getDialogId 拿不到就不管,按原逻辑走
                            }
                        }
                    })
                } catch (t: Throwable) {
                    Log.i(TAG, "hook onStop fail: $t")
                }
            }
        }
    }

    // 诊断用:记录每次真实创建的卡片子类,对比"写诗"和"你是谁"两种场景下卡片创建序列的差异
    private fun hookCardBaseDiagnostic(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass("com.xiaomi.voiceassistant.card.a")
        } catch (e: Throwable) {
            Log.i(TAG, "com.xiaomi.voiceassistant.card.a not found: $e")
            return
        }
        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dialogId = try {
                    param.thisObject.javaClass.getMethod("getDialogId").invoke(param.thisObject)
                } catch (t: Throwable) { null }
                Log.i(TAG, "[card] new ${param.thisObject.javaClass.name} dialogId=$dialogId hash=${System.identityHashCode(param.thisObject)}")
            }
        })
        for (name in listOf("onCardVisible", "onCardInvisible", "onCardDetached", "removeCard")) {
            for (m in clazz.declaredMethods) {
                if (m.name == name) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val hash = System.identityHashCode(param.thisObject)
                                if (name == "onCardDetached") detachedCards.add(hash)
                                Log.i(TAG, "[card] $name hash=$hash class=${param.thisObject.javaClass.name}")
                            }
                        })
                    } catch (t: Throwable) {
                        // 某些方法可能不存在于基类,忽略
                    }
                }
            }
        }
    }

    // 拦截"用户问了什么" —— OperationManager.setQueryInfo(dialogId, query, extraInfo),比具体某个卡片的构造函数更早更通用
    private fun hookOperationManager(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.operationManagerClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.operationManagerClass} not found: $e")
            return
        }
        val method = try {
            clazz.getDeclaredMethod("setQueryInfo", String::class.java, String::class.java, JSONObject::class.java)
        } catch (e: Throwable) {
            Log.i(TAG, "setQueryInfo method not found: $e")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val dialogId = param.args[0] as? String ?: return
                    val queryText = param.args[1] as? String ?: return
                    if (dialogId.isBlank() || queryText.isBlank()) return

                    val ctx = currentApplicationContext()
                    val config = readHookConfig(ctx)

                    // 只有新的交互才停旧播报；同 id 的 ASR 改写保留原轮身份。
                    val wasOurs = replyTurns.ownsCurrent()
                    val previousKey = replyTurns.keyFor(lastDialogId)
                    val turn = replyTurns.capture(dialogId, queryText, asr = false)
                    if (!replyTurns.isCurrent(turn.key)) return
                    if (previousKey != turn.key) {
                        stopMutePump()
                        if (wasOurs) stopOurTts()
                        lastAsrText = ""
                        lastAsrTime = 0L
                    }

                    // 先记录最近问话 + 配置 —— 跳转拦截独立于 AI 接管开关,始终要有这份信息
                    lastQueryText = queryText
                    lastQueryTime = System.currentTimeMillis()
                    lastDialogId = dialogId
                    if (config != null) lastConfig = config

                    // 以下是 AI 文本接管,需要功能启用
                    if (config == null || !config.enabled || !config.isUsable) {
                        return // 未启用 AI 接管,保持原生行为(但跳转拦截仍可生效)
                    }
                    // 白名单直通:这句命中用户自己配的正则 → 整句都不接管,原生行为原样放行。
                    if (isAiTakeoverSkip(turn.text, config)) {
                        Log.i(TAG, "skip takeover (whitelist pattern matched): $queryText")
                        return
                    }

                    // 立即标记接管并开启静音泵,从第 0ms 彻底掐死小爱云端流式下发的主音轨,
                    // 绝不给小爱原生 TTS 抢跑开口的机会(避免替换前后两段声音一起播放)。
                    val s = session(dialogId)
                    s.takenOver = true
                    replyTurns.claim(turn.key)
                    startMutePump(dialogId)

                    // 是"查看类"候选 → 预标记,以便在没有 RN 卡片时通过 FlowTemplateToastCard 撑开占位。
                    if (viewBlockCandidateNow(config)) {
                        s.pendingViewAnswer = true
                    }
                    // 发消息类:小爱这轮注定失败(双开/仅语音),我们接管。
                    if (sendMessageCommandNow()) {
                        s.pendingViewAnswer = true
                        Log.i(TAG, "send-message command, taking over: $queryText")
                    }
                    // 杀后台类:小爱原生做不到真正的 force-stop/kill,我们接管。
                    if (killBackgroundCommandNow()) {
                        s.pendingViewAnswer = true
                        Log.i(TAG, "kill-background command, taking over: $queryText")
                    }
                    // 冻结/解冻/清缓存类:同样接管以放行 app_state_control 动作闸。
                    if (appControlCommandNow()) {
                        s.pendingViewAnswer = true
                        Log.i(TAG, "app-control command, taking over: $queryText")
                    }
                    // 连接/断开蓝牙设备类:小爱原生连不了指定设备,接管以放行 bluetooth_control 动作闸。
                    if (btConnectCommandNow()) {
                        s.pendingViewAnswer = true
                        Log.i(TAG, "bt-connect command, taking over: $queryText")
                    }
                    startTrackedCall(turn.key, dialogId, turn.text, config)
                } catch (t: Throwable) {
                    Log.i(TAG, "hookOperationManager error: $t")
                }
            }
        })
        Log.i(TAG, "hooked ${symbols.operationManagerClass}.setQueryInfo")
    }

    // 记录 TemplateReactNativeCard 实例(按 dialogId),并在卡片隐藏/销毁/离开前台时及时停播 TTS
    private fun hookRnCard(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.rnCardClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.rnCardClass} not found: $e")
            return
        }
        for (name in listOf("bindView", "onCardAttached", "onCardInvisible", "onCardDetached", "onCardRemoved", "onDestroy")) {
            for (m in clazz.declaredMethods) {
                if (m.name == name) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val card = param.thisObject
                                    if (name in listOf("onCardInvisible", "onCardDetached", "onCardRemoved", "onDestroy")) {
                                        val id = card.javaClass.getMethod("getDialogId").invoke(card) as? String
                                        if (id == null || !replyTurns.isCurrentDialog(id)) return
                                        if (replyTurns.keyFor(id)?.let { replyCards.get(it) } != null) return
                                        Log.i(TAG, "[rn] card exit/invisible ($name), stopping TTS")
                                        stopOurTts()
                                        return
                                    }
                                    val getDialogId = card.javaClass.getMethod("getDialogId")
                                    val dialogId = getDialogId.invoke(card) as? String ?: return
                                    if (dialogId.isNotBlank()) {
                                        captureRnCard(card, dialogId)
                                        maybeInject(dialogId)
                                    }
                                } catch (t: Throwable) {
                                    // getDialogId 可能暂时拿不到,忽略
                                }
                            }
                        })
                        Log.i(TAG, "hooked ${symbols.rnCardClass}.$name")
                    } catch (t: Throwable) {
                        Log.i(TAG, "hook $name fail: $t")
                    }
                }
            }
        }
    }

    // 拦截 RN 桥接的 sendStreamData(type, content):
    //  - 对已接管的 dialogId,拦下真实内容(不让原方法执行,即不显示小爱自己的回答)
    //  - 我们自己发起的调用(injectingNow=true)直接放行
    private fun hookBridge(cl: ClassLoader) {
        val clazz = try {
            cl.loadClass(symbols.bridgeClass)
        } catch (e: Throwable) {
            Log.i(TAG, "${symbols.bridgeClass} not found: $e")
            return
        }
        val method = try {
            clazz.getDeclaredMethod("sendStreamData", String::class.java, String::class.java)
        } catch (e: Throwable) {
            Log.i(TAG, "sendStreamData method not found: $e")
            return
        }
        sendStreamDataMethod = method
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (injectingNow.get() == true) return
                    val type = param.args[0] as? String ?: return
                    val content = (param.args[1] as? String).orEmpty()
                    val dialogId = extractDialogId(content) ?: lastDialogId
                    val key = replyTurns.attachOutput(dialogId)
                    if (key != null && !replyTurns.isCurrent(key)) {
                        // 旧轮的迟到输出不能显示到当前页面。
                        if (isOurs(dialogId)) param.result = null
                        return
                    }
                    if (key != null && replyTurns.ownsCurrent()) {
                        val s = session(dialogId)
                        s.takenOver = true
                        s.aiAnswer = s.aiAnswer ?: utteranceAnswers[key]
                        utteranceDialogs.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(dialogId)
                    }

                    // "Finish" / "cancel" 这类控制信令的 content 是空的,里面没有 dialog_id。
                    // 只要还有接管中、尚未注入完成的对话或静音泵在运行,就先把这些控制信令拦下来,
                    // 避免前端提前关闭流导致我们后续的 sendStreamData 无法上屏。
                    if (type == "Finish" || type == "cancel") return

                    if (dialogId.isNotBlank()) {
                        session(dialogId).bridgeRef = WeakReference(param.thisObject)
                    }

                    val takenOverNow = sessionOrNull(dialogId)?.takenOver == true || isMutePumpActive()
                    if (takenOverNow && type == "instruction") {
                        val filtered = filterOutToastStream(content)
                        if (filtered == null) {
                            Log.i(TAG, "suppress whole content dialogId=$dialogId (nothing left after filtering)")
                            param.result = null
                        } else if (filtered != content) {
                            Log.i(TAG, "filtered ToastStream out, keep rest dialogId=$dialogId: $filtered")
                            param.args[1] = filtered
                        } else {
                            Log.i(TAG, "no ToastStream found, pass through dialogId=$dialogId")
                        }
                    }
                } catch (t: Throwable) {
                    Log.i(TAG, "hookBridge error: $t")
                }
            }
        })
        Log.i(TAG, "hooked ${symbols.bridgeClass}.sendStreamData")
    }

    // content 可能是单个 instruction 的 JSON 对象,也可能是打包了多条 instruction 的 JSON 数组。
    // 把里面 header.name == "ToastStream" 的条目去掉(那是小爱真实的大模型文字内容),
    // 保留其余的(比如 UpdateStreamProperties,前端用它来初始化"这是一个大模型卡片"的渲染状态)。
    // 返回 null 表示过滤完什么都不剩,应该整个拦掉。
    private fun filterOutToastStream(content: String): String? {
        val trimmed = content.trim()
        try {
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                val kept = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optJSONObject("header")?.optString("name")
                    if (name != "ToastStream") kept.put(obj)
                }
                return if (kept.length() == 0) null else kept.toString()
            } else if (trimmed.startsWith("{")) {
                val obj = org.json.JSONObject(trimmed)
                val name = obj.optJSONObject("header")?.optString("name")
                return if (name == "ToastStream") null else content
            }
        } catch (t: Throwable) {
            Log.i(TAG, "filterOutToastStream parse error: $t")
        }
        return content
    }

    // 开启静音泵:在 windowMs 内每 250ms 掐一次主音轨。
    // 早先的做法是拦截当下打 0/350/900/1600ms 四发定时补刀,但小爱的答案是云端流式回来的,
    // 起播时刻不确定,超过 1.6s 才开口就完全漏掉 —— 现象就是"替换过的和没替换过的一起放"。
    // 改成持续钳制。只掐主音轨,我们自己的答案在 TOAST_STREAM_TTS 那条音轨上,不受影响。
    private fun startMutePump(dialogId: String, windowMs: Long = 15_000L) {
        mutePumpDialogId = dialogId
        mutePumpUntil = System.currentTimeMillis() + windowMs
        if (mutePumpRunning) return   // 已有泵在跑,上面刷新了截止时间就够了
        mutePumpRunning = true
        val startedAt = System.currentTimeMillis()
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= mutePumpUntil) {
                    mutePumpRunning = false
                    Log.i(TAG, "mute pump stopped dialogId=$mutePumpDialogId")
                    return
                }
                try { muteAudio() } catch (t: Throwable) { }
                // 开头两秒密集敲(小爱基本都在这个窗口起播),之后放缓省开销。
                // 250ms 的间隔会漏出一个字的音("应用已经…"能听到"应"),60ms 基本听不出来。
                val elapsed = System.currentTimeMillis() - startedAt
                h.postDelayed(this, if (elapsed < 2_000L) 60L else 250L)
            }
        }
        h.post(tick)
        Log.i(TAG, "mute pump started dialogId=$dialogId window=${windowMs}ms")
    }

    // 泵是否正在为当前这次交互运行。等价于"这轮我们接管了,小爱该闭嘴"。
    private fun isMutePumpActive(): Boolean =
        replyTurns.ownsCurrent() || mutePumpUntil > System.currentTimeMillis()

    // 停泵:新一轮提问时调用,别把下一次交互的正常播报也掐了
    private fun stopMutePump() {
        if (mutePumpUntil != 0L) Log.i(TAG, "mute pump cancelled (new query)")
        mutePumpUntil = 0L
        mutePumpDialogId = ""
    }

    // 用小爱自己的 TTS 念出我们的答案。
    // 主路径 n1.speakTts 播在 TOAST_STREAM_TTS 音轨上,和静音泵掐的主音轨互不干扰,
    // 所以泵可以一直跑着,不用为了让我们开口而放开对小爱的钳制。
    private fun speakAnswer(key: String, answer: String) {
        if (!replyTurns.isCurrent(key)) return
        val cfg = lastConfig ?: return
        if (!cfg.enabled || !cfg.speakAnswer) return
        // 只念我们真正接管了的对话 —— 没拦下小爱的话,它自己已经在说了,再念就是两个人抢话。
        // 这次问话名下任何一个 dialogId 被接管,就算接管。
        val dialogIds = utteranceDialogs[key].orEmpty()
        if (dialogIds.none { isOurs(it) }) {
            Log.i(TAG, "not taken over, skip speaking key=$key")
            return
        }
        // 一次问话只出一次声。同一句话的多个 dialogId 走到这里只有第一个能过。
        if (!spokenUtterances.add(key)) {
            Log.i(TAG, "already spoken, skip key=$key")
            return
        }
        val dialogId = dialogIds.firstOrNull { isOurs(it) } ?: return

        val speakable = toSpeakable(answer)
        if (speakable.isBlank()) {
            Log.i(TAG, "nothing speakable after cleanup key=$key")
            spokenUtterances.remove(key)
            return
        }
        val cl = targetClassLoader ?: return
        Handler(Looper.getMainLooper()).post {
            if (!replyTurns.isCurrent(key)) return@post
            // 先掐掉小爱可能还在念的兜底话术,再开口,避免两句话叠在一起
            muteAudio()
            // 不管念得成不成,都要把 b2 换成我们的文本:否则用户点卡片右下角喇叭
            // 重播时放的仍是小爱的原始答案,声音和屏幕上的字对不上。
            syncSpeakContent(cl, dialogId, speakable)
            if (speakViaToastPlayer(cl, speakable)) {
                Log.i(TAG, "spoke answer via n1 dialogId=$dialogId len=${speakable.length}")
                return@post
            }
            // 主引擎可能已销毁且 speak() 会静默返回；释放拦截还会让原生回答重新开口。
            Log.w(TAG, "Toast TTS unavailable; answer remains on card key=$key dialogId=$dialogId")
            spokenUtterances.remove(key)
        }
    }

    // 把 b2(SpeakContentManager)的内容替换成我们的答案。
    // 必须先 clean():addFragment 只在 dialogId **变化**时才重置缓冲区,同一个 dialogId
    // 直接 append 会把我们的答案接在小爱原文后面。clean() 把 curDialogId 置 null,
    // 之后 addFragment 就会重建缓冲区。
    private fun syncSpeakContent(cl: ClassLoader, dialogId: String, text: String) {
        try {
            injectingNow.set(true)
            val clazz = cl.loadClass(symbols.speakContentClass)
            val instance = kotlinObjectInstance(clazz) ?: return
            clazz.getMethod("clean").invoke(instance)
            clazz.getMethod("addFragment", String::class.java, String::class.java)
                .invoke(instance, dialogId, text)
            Log.i(TAG, "speak content synced dialogId=$dialogId")
        } catch (t: Throwable) {
            Log.i(TAG, "syncSpeakContent failed dialogId=$dialogId: $t")
        } finally {
            injectingNow.set(false)
        }
    }

    /** 在宿主入队/写 PCM 前截断，静音泵只负责清理接管前已排队的声音。 */
    private fun hookNativeAudio(cl: ClassLoader) {
        try {
            val clazz = cl.loadClass(symbols.audioTrackManagerClass)
            val getTrack = clazz.getMethod("getAudioTrackByName", String::class.java)
            val methods = clazz.declaredMethods.filter {
                it.name in setOf("startPlay", "startGroupPlay", "resumePlay", "put") &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
            }
            for (method in methods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isMutePumpActive()) return
                        try {
                            if (param.thisObject === getTrack.invoke(null, OUR_AUDIO_TRACK)) return
                            param.result = null
                        } catch (t: Throwable) {
                            Log.w(TAG, "native audio guard failed: $t")
                        }
                    }
                })
            }
            Log.i(TAG, "hooked native audio gate ${clazz.name}: ${methods.size} methods")
        } catch (t: Throwable) {
            Log.w(TAG, "native audio gate unavailable; using mute pump: $t")
        }
    }

    /** 新版 configCardPressMenu 的 speakText 会启动另一条 StreamingTextTts 音轨。 */
    private fun hookAuthoritativeText(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(cl.loadClass(symbols.rnCardClass),
                "configCardPressMenu", JSONObject::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val id = param.thisObject.javaClass.getMethod("getDialogId")
                                .invoke(param.thisObject) as? String ?: return
                            if (!isOurs(id) || !replyTurns.isCurrentDialog(id)) return
                            val answer = sessionOrNull(id)?.aiAnswer.orEmpty()
                            val json = JSONObject((param.args[0] as? JSONObject ?: JSONObject()).toString())
                            json.put("totalText", answer)
                            // 模块统一在最终答案就绪后播报，避免 RN 再启动一次流式合成。
                            json.put("speakText", "")
                            json.put("isLlmContentDisplayComplete", answer.isNotBlank())
                            json.put("isIllegalContent", false)
                            param.args[0] = json
                        } catch (t: Throwable) { Log.w(TAG, "press menu text guard failed: $t") }
                    }
                })
        } catch (t: Throwable) { Log.w(TAG, "press menu guard unavailable: $t") }
        try {
            XposedHelpers.findAndHookMethod(cl.loadClass(symbols.speakContentClass),
                "addFragment", String::class.java, String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (injectingNow.get() == true) return
                        val id = param.args[0] as? String ?: return
                        if (isOurs(id) && replyTurns.isCurrentDialog(id)) param.result = null
                    }
                })
        } catch (t: Throwable) { Log.w(TAG, "speak content guard unavailable: $t") }
    }

    private fun readHookConfig(ctx: Context?): AiConfig? {
        val config = ctx?.let { ConfigClient.readOrNull(it) }
        if (config != null) lastConfig = config
        else Log.w(TAG, "config provider unavailable; reuse last successful configuration")
        return config ?: lastConfig
    }

    // 当用户返回桌面、关闭浮窗或卡片被移出屏幕时,立即终止 TTS 播报(对齐小爱原生行为)
    private fun stopOurTts() {
        try {
            val cl = targetClassLoader ?: return
            // 1. 停止大模型专属 ToastStreamPlayer (wf1.v1)
            try {
                val clazz = cl.loadClass(symbols.toastStreamPlayerClass)
                kotlinObjectInstance(clazz)?.let { instance ->
                    clazz.getMethod("stopPlay").invoke(instance)
                    Log.i(TAG, "stopped ToastStreamPlayer TTS")
                }
            } catch (t: Throwable) { }

            // 2. 停止备用 TTS 引擎 (com.xiaomi.voiceassistant.l2)
            try {
                val ttsClazz = cl.loadClass(symbols.ttsBridgeClass)
                val instance = ttsClazz.getMethod("getInstance").invoke(null)
                if (instance != null) {
                    for (m in listOf("stop", "stopPlay", "stopSpeak")) {
                        try {
                            ttsClazz.getMethod(m).invoke(instance)
                        } catch (t: Throwable) { }
                    }
                }
            } catch (t: Throwable) { }

            // 3. 停止所有音轨播放 (含 toastStreamTts)
            try {
                val trackClazz = cl.loadClass(symbols.audioTrackManagerClass)
                val tracks = allAudioTracks(trackClazz)
                for ((_, track) in tracks) {
                    stopTrack(track)
                }
            } catch (t: Throwable) { }
        } catch (t: Throwable) {
            Log.i(TAG, "stopOurTts error: $t")
        }
    }

    // 走卡片自己的 ToastStreamPlayer。会话已经拆掉也能播,而且播放状态监听会驱动喇叭图标动画。
    private fun speakViaToastPlayer(cl: ClassLoader, text: String): Boolean {
        return try {
            val clazz = cl.loadClass(symbols.toastStreamPlayerClass)
            val instance = kotlinObjectInstance(clazz) ?: return false
            clazz.getMethod("stopPlay").invoke(instance)
            // speakTts 成功返回合成事件 id,文本为空时返回 null
            val speakId = clazz.getMethod("speakTts", String::class.java).invoke(instance, text)
            speakId != null
        } catch (t: Throwable) {
            Log.i(TAG, "speakViaToastPlayer failed: $t")
            false
        }
    }

    // 取 Kotlin object 单例。字段名(b2.f48589a / n1.f95882a)是混淆产物,版本一变就失效,
    // 所以按"类型等于类自身的静态字段"来找,不写死名字。
    private fun kotlinObjectInstance(clazz: Class<*>): Any? {
        try {
            val f = clazz.getDeclaredField("INSTANCE")
            f.isAccessible = true
            f.get(null)?.let { return it }
        } catch (t: Throwable) { }
        for (f in clazz.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (f.type != clazz) continue
            try {
                f.isAccessible = true
                f.get(null)?.let { return it }
            } catch (t: Throwable) { }
        }
        Log.i(TAG, "no singleton instance found for ${clazz.name}")
        return null
    }

    // 把 markdown 答案压成适合朗读的纯文本。
    // 代码块整段丢掉(念代码毫无意义),链接只留锚文本,各种标记符去掉,最后限长。
    private fun toSpeakable(raw: String): String {
        var s = raw
        s = s.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")   // 围栏代码块
        s = s.replace(Regex("`([^`]*)`"), "$1")                               // 行内代码
        s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")                // 图片
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")              // 链接留锚文本
        s = s.replace(Regex("https?://\\S+"), " ")                            // 裸链接
        s = s.replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")// 标题
        s = s.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")     // 无序列表
        s = s.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")         // 引用
        s = s.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")                 // 粗体/斜体
        s = s.replace(Regex("~~([^~]*)~~"), "$1")                             // 删除线
        s = s.replace(Regex("^\\s*[-*_]{3,}\\s*$", RegexOption.MULTILINE), " ")// 分隔线
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\s*\\n\\s*"), "，")   // 换行读成停顿,别让 TTS 一口气连读
        s = s.replace(Regex("，{2,}"), "，").trim().trim('，')
        return if (s.length <= MAX_SPEAK_CHARS) s else s.take(MAX_SPEAK_CHARS).trimEnd('，') + "……详细内容请看屏幕"
    }

    // 静音:停掉**除我们自己那条之外**的所有音轨。
    // v20.e 内部有个静态注册表 ConcurrentHashMap<String, e>,实测同时存在五条:
    //   main / tts / toastStreamTts / getAuthorization / getNoAccountAuthorization
    // 早先这里只掐 getMainAudioTrack()(即 "main" 一条),所以走别的音轨的话术照样播得出来
    // —— 比如"应用已经不支持这个功能了"这类固定话术。
    // 我们自己的答案在 OUR_AUDIO_TRACK 上(n1.speakTts 用的那条),按名字跳过它。
    private fun muteAudio() {
        try {
            val ctx = currentApplicationContext() ?: return
            val clazz = ctx.classLoader.loadClass(symbols.audioTrackManagerClass)
            val tracks = allAudioTracks(clazz)
            if (tracks.isEmpty()) {
                muteMainTrackOnly(clazz)   // 注册表拿不到就退回老做法,至少掐住主音轨
                return
            }
            var stopped = 0
            for ((name, track) in tracks) {
                if (name == OUR_AUDIO_TRACK) continue   // 那是我们自己在说话
                if (stopTrack(track)) stopped++
            }
            Log.i(TAG, "muted $stopped/${tracks.size} audio tracks (kept $OUR_AUDIO_TRACK)")
        } catch (t: Throwable) {
            Log.i(TAG, "muteAudio failed: $t")
        }
    }

    // 取 v20.e 的静态音轨注册表。字段名是混淆产物,按"静态 Map 字段"找,不写死名字。
    @Suppress("UNCHECKED_CAST")
    private fun allAudioTracks(clazz: Class<*>): Map<String, Any> {
        for (f in clazz.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (!Map::class.java.isAssignableFrom(f.type)) continue
            try {
                f.isAccessible = true
                val map = f.get(null) as? Map<*, *> ?: continue
                // 注册表的键是音轨名(String),值是 v20.e 实例
                val out = HashMap<String, Any>()
                for ((k, v) in map) {
                    if (k is String && v != null && clazz.isInstance(v)) out[k] = v
                }
                if (out.isNotEmpty()) return out
            } catch (t: Throwable) { }
        }
        return emptyMap()
    }

    private fun stopTrack(track: Any): Boolean {
        var ok = false
        for (name in listOf("stopPlayAndClearQueue", "stop")) {
            try {
                track.javaClass.getMethod(name).invoke(track)
                ok = true
            } catch (t: Throwable) {
                // 忽略,尝试下一个方法名
            }
        }
        return ok
    }

    private fun muteMainTrackOnly(clazz: Class<*>) {
        try {
            val track = clazz.getMethod("getMainAudioTrack").invoke(null) ?: return
            stopTrack(track)
            Log.i(TAG, "muted main audio track (registry unavailable)")
        } catch (t: Throwable) {
            Log.i(TAG, "muteMainTrackOnly failed: $t")
        }
    }

    private fun extractDialogId(content: String): String? {
        val m = Regex("\"dialog_id\"\\s*:\\s*\"([^\"]+)\"").find(content)
        return m?.groupValues?.get(1)
    }

    // ── 流式显示 ──────────────────────────────────────────────────────────────
    // 只驱动「显示」:把模型正文的增量推到这次问话名下已存在的答案卡,做打字机效果。
    // TTS 不受影响,仍是拿到全文后整串播(speakAnswer)。当前没有卡片就静默丢弃 ——
    // 最终答案由原有的 applyAnswer 路径兜底,流式纯属锦上添花,不承担正确性。
    private fun streamSinkFor(key: String) = object : AiClient.StreamSink {
        override fun onDelta(text: String) {
            // 文本约定降级时,工具轮的 <tool_call> 残渣别上屏;节流,避免主线程被 token 刷爆。
            if (text.isBlank() || text.contains("<tool_call")) return
            val now = System.currentTimeMillis()
            if (now - lastStreamRenderAt < STREAM_RENDER_INTERVAL_MS) return
            lastStreamRenderAt = now
            pushStreamingText(key, text)
        }
        override fun onComplete(text: String) {
            // 定稿:不节流,保证卡片停在完整答案上(节流可能吞掉了最后一帧)。
            lastStreamRenderAt = 0L
            if (text.isNotBlank()) pushStreamingText(key, text)
        }
        override fun onDiscard() {
            // 这轮是工具调用:把乐观推上去的正文撤回占位,等下一轮真答案。
            lastStreamRenderAt = 0L
            pushStreamingText(key, THINKING_PLACEHOLDER)
        }
    }

    // 把文本推到这次问话名下所有活着的答案卡(自建卡 + 认领来的话术卡都算)。best-effort。
    private fun pushStreamingText(key: String, text: String) {
        if (!replyTurns.isCurrent(key)) return
        val card = replyCards.get(key) ?: return
        Handler(Looper.getMainLooper()).post {
            if (!replyTurns.isCurrent(key)) return@post
            try {
                ourCardTexts[System.identityHashCode(card)] = text
                card.javaClass.getMethod("updateCardText", String::class.java).invoke(card, text)
                forceShowToastViewHolder(card, text)
            } catch (t: Throwable) { Log.w(TAG, "[reply-ui] streaming update failed: $t") }
        }
    }

    // 一次问话只调一次模型;答案回来后分发给这次问话名下的所有 dialogId。
    private fun startTrackedCall(key: String, dialogId: String, queryText: String, config: AiConfig) {
        if (!replyTurns.isCurrent(key)) return
        val s = session(dialogId)
        utteranceDialogs.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(dialogId)
        val firstCapture = synchronized(s) {
            if (s.queryText != null) false else { s.queryText = queryText; true }
        }
        // ASR 和 setQueryInfo 两条入口共用一把请求闸，网络失败后的同轮回调也不自动重发。
        if (!firstCapture) return
        val ready = utteranceAnswers[key]
        if (ready != null) {
            applyAnswer(key, dialogId, ready)
            return
        }
        if (replyTurns.tryStart(key)) startAiCall(key, queryText, config)
    }

    private fun startAiCall(key: String, queryText: String, config: AiConfig) {
        Thread {
            try {
                Log.i(TAG, "calling AI for key=$key ...")
                // Context 给工具用(装了什么应用、启动应用、调音量都要它)。
                // 拿不到也能跑,只是那几个工具会返回 "no context available"。
                //
                // allowMutating:这轮到底归不归我们。**在工具执行的那一刻**才求值 ——
                // 接管信号(takeOver / 静音泵)常常比模型调用晚到,一开始就判会误杀。
                // 不这么关的话,像"打开微信帮我给X发信息"这种命中放行词、本该由小爱
                // 自己处理的话,我们的模型也会并行跑一遍并真的去 launch_app。
                // 上下文在**发请求前**取一次:一小时窗口内的旧问答,不含本轮。
                // 开关关着时顺手清空:设置页在**另一个进程**,那边调 clear() 清不到这份内存,
                // 不在这清的话关了再开会接上一小时前的话茬。
                val history = if (config.contextEnabled) {
                    ChatHistory.recent()
                } else {
                    ChatHistory.clear()
                    emptyList()
                }
                // 流式已重开:此前工具全挂是因为 arguments/name 的 JSON null 被 optString 拼成
                // 字面量 "null" 毁了参数(已加 isNull 防护)。带着调试日志一起放出去,真机核对。
                val answer = AiClient.chat(
                    config, queryText, currentApplicationContext(), history, streamSinkFor(key)
                ) {
                    replyTurns.isCurrent(key) && utteranceDialogs[key].orEmpty().any { isOurs(it) }
                }
                if (!replyTurns.isCurrent(key)) return@Thread
                Log.i(TAG, "AI answer ready key=$key: $answer")
                // 软失败([AiClient.FAILED_ANSWER])要当失败处理,不能缓存也不能记历史。
                // 它是**正常返回**的字符串,不像硬失败会抛异常走下面的 catch,所以得显式认。
                // 缓存了的后果见 FAILED_ANSWER 的注释:用户重问命中 15 秒缓存,重播失败、
                // 不重试。记历史的后果是这句废话会跟着进后续几轮的上下文。
                val failed = answer == AiClient.FAILED_ANSWER
                if (failed) Log.w(TAG, "soft failure, not cached/recorded key=$key")
                if (!failed) {
                    utteranceAnswers[key] = answer
                    // 答成了才记进历史 —— 失败的轮次不该污染后面的上下文。
                    // 一次问话只走到这里一次(ReplyTurns.tryStart 挡住了并发,答案命中
                    // utteranceAnswers 缓存的重复提问根本不会再调 startAiCall),所以不会重复记。
                    if (config.contextEnabled) ChatHistory.record(queryText, answer)
                }
                // 先把卡片/注入/历史都落好,再开口,让画面和声音基本同时到
                for (id in utteranceDialogs[key].orEmpty()) {
                    applyAnswer(key, id, answer)
                }
                // 播报按 key 走,保证一次问话只出一次声(哪怕有好几个 dialogId)
                speakAnswer(key, answer)
            } catch (t: Throwable) {
                Log.i(TAG, "AI call failed key=$key: $t")
                if (replyTurns.isCurrent(key)) {
                    for (id in utteranceDialogs[key].orEmpty()) applyAnswer(key, id, AiClient.FAILED_ANSWER)
                    speakAnswer(key, AiClient.FAILED_ANSWER)
                }
            }
        }.start()
    }

    // 把答案落到某个具体 dialogId 上:注入 RN、更新卡片、写历史。
    // 播报不在这里 —— 那是按 key 的,否则同一句话有几个 dialogId 就念几遍。
    private fun applyAnswer(key: String, dialogId: String, answer: String) {
        if (!replyTurns.isCurrent(key)) return
        try {
            session(dialogId).aiAnswer = answer
            targetClassLoader?.let { syncSpeakContent(it, dialogId, toSpeakable(answer)) }
            // 替换回答统一由原生文字卡显示，RN 的计算器/业务页面不承担聊天文字渲染。
            ensureAnswerCard(dialogId)
            // 历史库同步换成我们的答案,否则回 App 看历史还是那句兜底话术
            writeAnswerToHistory(dialogId, answer)
        } catch (t: Throwable) {
            Log.i(TAG, "applyAnswer failed dialogId=$dialogId: $t")
        }
    }

    // AI 回复 + bridge 实例都齐了才注入,只注入一次
    private fun maybeInject(dialogId: String) {
        val key = replyTurns.keyFor(dialogId) ?: return
        if (!replyTurns.isCurrent(key)) return
        if (replyCards.get(key) != null) return // 已由原生文字卡承载，不再开第二条显示通道
        val s = sessionOrNull(dialogId) ?: return
        if (s.injected.get()) return
        val answer = s.aiAnswer ?: return   // 答案还没到,正常路过,不值得记
        // 下面两个缺一样都等于"这轮永远不会上屏"。原先是静默 return,
        // 于是"卡片一直空白"在日志里和"根本没走到这"长得一模一样,只能靠猜。
        val bridge = s.bridgeRef?.get()
        if (bridge == null) {
            Log.i(TAG, "no bridge for dialogId=$dialogId (last=$lastDialogId), cannot inject")
            return
        }
        val method = sendStreamDataMethod
        if (method == null) {
            Log.i(TAG, "sendStreamData method unavailable, cannot inject dialogId=$dialogId")
            return
        }

        // RN 的 JS 侧还没 ready 的话,现在发过去等于石沉大海。
        // 先不标记 injected,等 rnStartReceiveInstruction 回调时会再调一次这里。
        val ready = isRnFrontReady(dialogId) || sessions.entries.any { (id, other) ->
            replyTurns.keyFor(id) == key && other.rnJsReady && other.bridgeRef?.get() === bridge
        }
        if (ready == false) {
            Log.i(TAG, "RN front not ready yet, defer inject dialogId=$dialogId")
            return
        }
        Log.i(TAG, "RN front ready=$ready, injecting dialogId=$dialogId")

        if (!s.injected.compareAndSet(false, true)) return   // 只注入一次(原来靠 Set.add 返回值)

        Handler(Looper.getMainLooper()).post {
            if (!replyTurns.isCurrent(key)) {
                s.injected.set(false)
                return@post
            }
            if (injectedBridges[bridge] == key) return@post
            // 兜底:万一 RN 实例在这之前已经被 onStop/onPause 掉了,注入前再唤醒一次
            try {
                val card = s.cardRef?.get()
                if (card != null) {
                    try { card.javaClass.getMethod("onResume").invoke(card) } catch (t: Throwable) { }
                }
            } catch (t: Throwable) { }

            // 【2026-07-25 去重】曾经把这条 ToastStream 删掉,只留 p1,理由是"两条通道各渲一块"。
            // 【当天晚些时候修正】那个判断错了,p1 根本不画东西 —— 反编译实锤
            // (TemplateReactNativeCard.p1,调用方 configCardPressMenu):它只往数据模型
            // stream.c.d 里写 totalText/isLlmContentDisplayComplete,没有任何 view/JS 通知,
            // 消费方是长按菜单的复制内容和喇叭重播的文本。RN 卡片屏幕上的字**只有**
            // sendStreamData(instruction, ToastStream) 这一条来源。
            // 删掉之后现象:"手机现在开放端口有几个"答案 9.4 秒就回来了、TTS 也念了,
            // 卡片却全程空白(带"AI生成"角标的空卡),日志里 p1 还报告 rendered 成功。
            // 当时看到的两段其实是"我们的 ToastStream + 小爱漏网的 ToastStream" ——
            // 小爱给搜索类答案用的 dialogId 和 ASR 那个对不上,老的 takeOver 判据漏了它。
            // 那一漏已由同 commit 新增的静音泵兜底过滤(见 hookBridge)堵死,所以这里恢复
            // 正文块不会让双段回来。
            try {
                injectingNow.set(true)
                val transactionId = UUID.randomUUID().toString().replace("-", "")
                val contentPayload = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("name", "ToastStream")
                        put("namespace", "Template")
                        put("dialog_id", dialogId)
                        put("id", UUID.randomUUID().toString().replace("-", ""))
                        put("transaction_id", transactionId)
                    })
                    put("payload", JSONObject().apply { put("markdown_text", answer) })
                }
                method.invoke(bridge, "instruction", contentPayload.toString())
                Log.i(TAG, "injected answer via ToastStream dialogId=$dialogId len=${answer.length}")

                val finalPayload = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("name", "ToastStream")
                        put("namespace", "Template")
                        put("transaction_id", transactionId)
                        put("id", UUID.randomUUID().toString())
                        put("dialog_id", dialogId)
                    })
                    put("payload", JSONObject().apply { put("markdown_text", "<FINAL>") })
                }
                method.invoke(bridge, "instruction", finalPayload.toString())
                method.invoke(bridge, "Finish", "")
                injectedBridges[bridge] = key
                Log.i(TAG, "closed xiaoai stream (<FINAL>+Finish) dialogId=$dialogId")
            } catch (t: Throwable) {
                Log.i(TAG, "stream close failed dialogId=$dialogId: $t")
                s.injected.set(false)
            } finally {
                injectingNow.set(false)
            }

            // p1 设 totalText:**不是显示通道**(理由见上面的注释),但它是「权威文本」——
            // 长按复制、卡片上喇叭按钮重播读的都是它。不设的话那两处还留着小爱的兜底话。
            // 这里给纯文本版:复制/朗读不需要 **…** / `…` 这些 markdown 标记,
            // 而屏幕显示走的 ToastStream 是 markdown_text,原样发,富文本由前端渲。
            try {
                val card = s.cardRef?.get()
                if (card != null) {
                    val targetMethod = card.javaClass.getMethod("configCardPressMenu", JSONObject::class.java)
                    if (targetMethod != null) {
                        targetMethod.isAccessible = true
                        val plain = answer.replace("**", "").replace("`", "")
                        val obj = JSONObject().apply {
                            put("totalText", plain)
                            put("isLlmContentDisplayComplete", true)
                            put("isIllegalContent", false)
                        }
                        targetMethod.invoke(card, obj)
                        Log.i(TAG, "synced authoritative text via ${targetMethod.name}(totalText) dialogId=$dialogId")
                    } else {
                        Log.i(TAG, "no totalText sync method found on card class ${card.javaClass.name}")
                    }
                } else {
                    // 没拿到卡不影响显示(那条走 bridge),只是复制/重播读到的还是小爱的话。
                    Log.i(TAG, "no RN card for authoritative text dialogId=$dialogId")
                }
            } catch (t: Throwable) {
                Log.i(TAG, "authoritative text render failed dialogId=$dialogId: $t")
            }
        }
    }

    private fun currentApplicationContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getMethod("currentApplication")
            currentApplication.invoke(null) as? Context
        } catch (t: Throwable) {
            Log.i(TAG, "currentApplicationContext failed: $t")
            null
        }
    }
}

package io.mo.xiaoaiplug.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.xiaoaiplug.ModuleStatus
import io.mo.xiaoaiplug.auto.AccessibilityGuard
import io.mo.xiaoaiplug.config.AiClient
import io.mo.xiaoaiplug.config.AiConfig
import io.mo.xiaoaiplug.config.ConfigRepository
import io.mo.xiaoaiplug.config.HostControl
import io.mo.xiaoaiplug.config.LogEntry
import io.mo.xiaoaiplug.config.LogStore
import io.mo.xiaoaiplug.config.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 「测试连接」的状态机。 */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val reply: String) : TestState
    data class Failed(val message: String) : TestState
}

/** 底部提示条停留多久（不含淡出动画的时长）。 */
private const val TOAST_DURATION_MS = 2000L

/** 「已删除 · 撤销」留多久。比提示条长：要给人反应过来、伸手去点的时间。 */
private const val UNDO_WINDOW_MS = 4000L

/** 输入框停手多久后写盘。 */
private const val TEXT_WRITE_DELAY_MS = 400L

/** 主页要显示的运行状况。 */
data class StatusSnapshot(
    val moduleActive: Boolean = false,
    val accessibilityOn: Boolean = false,
    val todayChats: Int = 0,
    val todayTools: Int = 0,
    val todayFailures: Int = 0,
    val dexStatus: io.mo.xiaoaiplug.config.DexStatusInfo = io.mo.xiaoaiplug.config.DexStatusInfo()
)

class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConfigRepository.get(app)

    val config: StateFlow<AiConfig> = repo.config
    val reachable: StateFlow<Boolean> = repo.reachable

    private val _status = MutableStateFlow(StatusSnapshot())
    val status: StateFlow<StatusSnapshot> = _status.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _showDexDialog = MutableStateFlow(false)
    val showDexDialog: StateFlow<Boolean> = _showDexDialog.asStateFlow()

    private val _isScanningDex = MutableStateFlow(false)
    val isScanningDex: StateFlow<Boolean> = _isScanningDex.asStateFlow()

    fun openDexDialog() {
        refreshStatus()
        _showDexDialog.value = true
    }

    fun closeDexDialog() {
        _showDexDialog.value = false
    }

    /** Scan in the module and publish a validated result for the host's next startup. */
    fun rescanDexSymbols() = rescanDexSymbols(auto = false)

    /**
     * [auto] = 打开应用时发现交给小爱的扫描结果已失效(见 DexCacheArtifact.needsRescan),
     * 不等用户手动点就重扫。小爱进程里扫不了,不重扫的话插件整体停用 ——
     * 升 SCHEMA_VERSION 或小爱更新后,用户只会看到"什么都不灵了"。
     */
    private fun rescanDexSymbols(auto: Boolean) {
        if (_isScanningDex.value) return
        _isScanningDex.value = true
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val pi = app.packageManager.getPackageInfo("com.miui.voiceassist", 0)
                    val apkPath = pi.applicationInfo?.sourceDir
                        ?: return@withContext "未找到小爱 APK"
                    if (auto) {
                        val apk = java.io.File(apkPath)
                        val current = io.mo.xiaoaiplug.hook.dex.ApkIdentity(
                            apk.absolutePath, pi.longVersionCode, apk.lastModified(), apk.length()
                        )
                        val staged = io.mo.xiaoaiplug.config.ConfigClient.readDexArtifact(app)
                        if (!io.mo.xiaoaiplug.hook.dex.DexCacheArtifact.needsRescan(staged, current)) {
                            return@withContext null
                        }
                        android.util.Log.i("XiaoAiProbe", "dex handoff stale, auto rescanning")
                    }
                    val result = io.mo.xiaoaiplug.hook.dex.DexAdapter.forceRescan(
                        apkPath, app.cacheDir, pi.longVersionCode
                    )
                    val reported = io.mo.xiaoaiplug.config.ConfigClient.reportDexSymbols(
                        app, result, "v${pi.longVersionCode}", manual = true
                    )
                    val prefix = if (auto) "符号缓存已失效，已自动重扫：" else ""
                    prefix + when {
                        !reported -> "扫描结果保存失败，请重试"
                        result.scan.cacheable -> "扫描完成，点「重启小爱」加载新符号"
                        else -> result.scan.summary + "；点「重启小爱」应用停用状态"
                    }
                }
                if (message == null) return@launch
                refreshStatus()
                showToast(message)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                android.util.Log.e("XiaoAiProbe", "Dex rescan failed (auto=$auto)", t)
                // 自动检查失败(比如没装小爱)不打扰用户,手动点的才提示
                if (!auto) showToast("未找到小爱 APK 或扫描失败")
            } finally {
                _isScanningDex.value = false
            }
        }
    }

    /** 底栏上方那条一闪而过的提示。null = 不显示。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var toastJob: Job? = null

    /**
     * 弹一条提示，[TOAST_DURATION_MS] 后自己收回（收回时那边会播淡出动画）。
     *
     * 每次都把上一个计时器取消掉：连着弹两条时，不取消的话第一条的计时器会在
     * 第二条刚出来 0.几秒时把它一起清掉。
     */
    private fun showToast(message: String) {
        toastJob?.cancel()
        _toast.value = message
        toastJob = viewModelScope.launch {
            delay(TOAST_DURATION_MS)
            _toast.value = null
        }
    }

    /**
     * 验 root 期间的防重入标记。
     *
     * **故意不是 StateFlow、也不暴露给界面。** 之前它驱动着开关的 enabled 和 summary，
     * 结果没 root 时 su 几毫秒就失败，summary 在两行和一行之间闪一个来回，
     * 卡片高度跟着缩一下再弹回来 —— 整页看着"闪一下"。检查状态不值得用布局变化去表达。
     *
     * **必须同步置位**，不能挪进 launch 里：协程启动有延迟，连点时好几次都会先过掉
     * 这道检查，再各自 fork 一个 su。
     */
    private var autoFixChecking = false

    init {
        refreshStatus()
        autoRepairOnLaunch()
        rescanDexSymbols(auto = true)
    }

    /**
     * 打开应用时把无障碍修回来。
     *
     * **为什么光有 ConfigProvider 那道自愈不够:** 那道只在小爱真的调 ui_dump /
     * send_message 时才触发。用户清了后台、点开应用一看还是红的 —— 因为在他
     * 说下一句话之前，压根没有任何东西调过 provider。他只能手动点「去开启」，
     * 那自动恢复就名不副实了。
     */
    private fun autoRepairOnLaunch() {
        viewModelScope.launch {
            // 必须等配置真读上来。加载完成前 repo.config 是一份默认值，而
            // autoFixAccessibility 默认为 false —— 不等就会把「已经打开了」的用户
            // 当成没开，启动时这次自愈直接白跳过。
            repo.loaded.first { it }
            if (!config.value.autoFixAccessibility) return@launch
            withContext(Dispatchers.IO) { AccessibilityGuard.ensureRunning(getApplication()) }
            refreshStatus()
        }
    }

    fun update(transform: (AiConfig) -> AiConfig) {
        val before = config.value
        repo.update(transform = transform)
        afterConfigChange(before)
    }

    /**
     * 输入框专用:内存立刻更新,写盘等停手 [TEXT_WRITE_DELAY_MS] 后合并成一次。
     * 开关之类一次性的改动还是走 [update],立刻落盘。
     */
    fun updateText(transform: (AiConfig) -> AiConfig) {
        val before = config.value
        repo.update(TEXT_WRITE_DELAY_MS, transform)
        afterConfigChange(before)
    }

    /** 界面退到后台时把还没写的输入落盘。 */
    fun flushConfig() = repo.flush()

    /**
     * 连接相关的四项变了,上一次「测试OK / 失败」就不再说明当前配置 —— 留着它,
     * 用户改完 Key 看到绿字会以为新 Key 也测过了。repo.update 是同步更新内存的,
     * 所以这里读到的 config.value 已经是新值。
     */
    private fun afterConfigChange(before: AiConfig) {
        val after = config.value
        if (before.provider != after.provider || before.endpoint != after.endpoint ||
            before.apiKey != after.apiKey || before.model != after.model
        ) {
            clearTestState()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val store = LogStore.get(getApplication())
                val since = startOfToday()
                val counts = store.countsSince(since)
                val dex = io.mo.xiaoaiplug.config.ConfigClient.readDexSymbols(getApplication())
                StatusSnapshot(
                    // isActive() 常量返回 false，被 hook 改写后才是 true —— 见 ModuleStatus
                    moduleActive = ModuleStatus.isActive(),
                    accessibilityOn = isAccessibilityEnabled(),
                    todayChats = counts[LogEntry.TYPE_CHAT] ?: 0,
                    todayTools = counts[LogEntry.TYPE_TOOL] ?: 0,
                    todayFailures = store.failureCountSince(since),
                    dexStatus = dex
                )
            }
            _status.value = snapshot
        }
    }

    /**
     * 拿当前配置真发一次请求。
     *
     * 复用 [AiClient.chat]，但**把工具全关掉**（`Tools.NONE`）：测试只想知道端点、
     * 密钥、模型名对不对，不该在这里真去执行 launch_app 之类会动设备的东西。
     * `ctx` 传 null，顺带让 LogClient 直接跳过，测试不会污染记录页。
     */
    fun testConnection() {
        if (_testState.value == TestState.Running) return
        val generation = ++testGeneration
        viewModelScope.launch {
            _testState.value = TestState.Running
            val cfg = config.value
            if (!cfg.isUsable) {
                _testState.value = TestState.Failed("请先填写 API Key")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AiClient.chat(
                        config = cfg.copy(enabledTools = Tools.NONE, useNativeTools = false),
                        userText = "回复两个字：收到",
                        ctx = null
                    )
                }
            }
            // 测试途中改了配置:这个结果测的是旧配置,丢掉,别把它挂在新配置下面
            if (generation != testGeneration) return@launch
            _testState.value = result.fold(
                onSuccess = { TestState.Ok(it.trim().take(200)) },
                onFailure = { TestState.Failed(AiClient.failureDetail(it)) }
            )
        }
    }

    /** 每次开测和每次作废都加一,测试协程回来时对不上号就说明结果已过期。 */
    private var testGeneration = 0

    fun clearTestState() {
        testGeneration++
        _testState.value = TestState.Idle
    }

    /**
     * 「重启小爱」:用 root 结束小爱进程,等同于用户去应用详情里点「结束运行」。
     * 下次唤醒时小爱重新启动,加载刚扫出来的符号。
     */
    fun restartHost() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { HostControl.forceStop() }
            showToast(
                if (ok) "已结束小爱，下次唤醒即加载新符号"
                else "没有 root 权限，请到应用详情里结束运行超级小爱"
            )
            // 宿主读到新符号后才会回报;这里先刷一下,真正变绿要等下次唤醒
            refreshStatus()
        }
    }

    // ---- 二次确认 ----

    /** 删不可恢复的东西前先问一句。弹窗画在 AppRoot 最外层,见 ConfirmDialog。 */
    data class ConfirmRequest(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val onConfirm: () -> Unit
    )

    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    val confirm: StateFlow<ConfirmRequest?> = _confirm.asStateFlow()

    fun requestConfirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) {
        _confirm.value = ConfirmRequest(title, message, confirmLabel, onConfirm)
    }

    fun resolveConfirm(accepted: Boolean) {
        val request = _confirm.value ?: return
        _confirm.value = null
        if (accepted) request.onConfirm()
    }

    // ---- 撤销 ----

    /** [seq] 让连着两条一样的文案也能被界面认成新的一条(重新计时、重播动画)。 */
    data class UndoOffer(val seq: Long, val message: String)

    private val _undo = MutableStateFlow<UndoOffer?>(null)
    val undo: StateFlow<UndoOffer?> = _undo.asStateFlow()

    private var undoSeq = 0L
    private var undoAction: (() -> Unit)? = null
    private var commitAction: (() -> Unit)? = null
    private var undoJob: Job? = null

    /**
     * 底部弹一条「已删除 · 撤销」。[UNDO_WINDOW_MS] 内点撤销就调 [onUndo],否则调 [onCommit]
     * 把删除真正落库。来了新的一条时,上一条立刻 commit —— 同一时间只留一个能撤销的操作。
     */
    fun offerUndo(message: String, onUndo: () -> Unit, onCommit: () -> Unit) {
        settleUndo()
        undoAction = onUndo
        commitAction = onCommit
        _undo.value = UndoOffer(++undoSeq, message)
        undoJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            settleUndo()
        }
    }

    fun undo() {
        val action = undoAction
        undoJob?.cancel()
        undoJob = null
        undoAction = null
        commitAction = null
        _undo.value = null
        action?.invoke()
    }

    private fun settleUndo() {
        val commit = commitAction
        undoJob?.cancel()
        undoJob = null
        undoAction = null
        commitAction = null
        _undo.value = null
        commit?.invoke()
    }

    override fun onCleared() {
        // 界面没了还挂着的删除照样落库,不然用户以为删掉的东西下次打开又回来了
        settleUndo()
        repo.flush()
    }

    /**
     * 拨动「自动恢复无障碍」。
     *
     * 打开之前先验一次「能不能写系统设置」——**验不过就不让开**，而不是开了之后
     * 每次要用时才静悄悄失败一遍。这个开关承诺的是「以后不用管了」，开得成却不
     * 干活比一开始就开不了更糟。
     *
     * 关掉不用验：收回权限不需要权限。
     */
    fun setAutoFixAccessibility(on: Boolean) {
        if (!on) {
            update { it.copy(autoFixAccessibility = false) }
            return
        }
        if (autoFixChecking) return
        autoFixChecking = true
        viewModelScope.launch {
            try {
                // fork su，还可能停在 KernelSU 的授权弹窗上等用户点，绝不能在主线程做。
                val ok = withContext(Dispatchers.IO) {
                    AccessibilityGuard.canSelfHeal(getApplication())
                }
                if (!ok) {
                    showToast("没有 root 权限，无法开启")
                    return@launch
                }
                update { it.copy(autoFixAccessibility = true) }
                // 刚开启就顺手修一次，省得用户还要再去主页点一下「去开启」。
                // 不复用 autoRepairOnLaunch()：update{} 是异步落盘的，那边读
                // config.value 会读到还没更新的旧值，白跑一趟。
                withContext(Dispatchers.IO) {
                    AccessibilityGuard.ensureRunning(getApplication())
                }
                refreshStatus()
            } finally {
                // 走 finally：中间任何一步抛了异常，标记也得放开，
                // 否则开关从此再也点不动，而界面上完全看不出为什么。
                autoFixChecking = false
            }
        }
    }

    /**
     * 「去开启」：先自己修，修不好才把用户送去系统设置页。
     *
     * 有 root 或 WRITE_SECURE_SETTINGS 时用户根本不用离开这个页面，
     * 点一下红点就变绿了。[onNeedsManual] 是最后的退路。
     */
    fun repairAccessibility(onNeedsManual: () -> Unit) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                AccessibilityGuard.ensureRunning(getApplication())
            }
            refreshStatus()
            if (failure != null) onNeedsManual()
        }
    }

    /**
     * 查系统设置里无障碍服务的开启状态。
     *
     * 比 `UiAutoService.instance != null` 可靠：那个静态引用只有在服务真被系统拉起来
     * 之后才有值，用户刚在设置里打开、服务还没连上的窗口期会误报成未开启。
     */
    private fun isAccessibilityEnabled(): Boolean =
        AccessibilityGuard.isEnabledInSettings(getApplication())

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

package io.mo.xiaoaiplug.config

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 界面侧的配置读写。
 *
 * 包住已有的 [ConfigClient],不改它的跨进程协议 —— hook 侧还在直接用 [ConfigClient.read]。
 * 这里解决的是界面自己的两个老毛病:
 *  1. `contentResolver.call` 原先在主线程同步跑(onCreate 里一次、保存按钮里一次)
 *  2. 读失败被吞成一份空配置,provider 挂了看起来跟全新安装一模一样
 */
class ConfigRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化写盘,避免两次写交错、旧的那份后落地把新的盖掉。 */
    private val writeLock = Mutex()

    private val _config = MutableStateFlow(defaultConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    /** provider 是否可达。false 时界面要明确报错,而不是显示一份看似正常的空配置。 */
    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    /** 最后一次成功落盘(或读上来)的配置。写失败时回滚到它。 */
    @Volatile private var persisted: AiConfig? = null

    /** 还没执行的那次写盘。新的改动会取消它、重新排一次,所以多次改动只写最后一版。 */
    private var pendingWrite: Job? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val read = ConfigClient.readOrNull(appContext)
        if (read == null) {
            _reachable.value = false
        } else {
            _reachable.value = true
            _config.value = read
            persisted = read
        }
        _loaded.value = true
    }

    /**
     * 改一项配置并落盘(界面是改动即存,没有保存按钮)。
     *
     * **内存里的 StateFlow 同步更新**,写盘另排。早先连内存更新都在 IO 协程里、还要排队等
     * 上一次跨进程写完 —— 输入框绑着这份值,打字快时显示值落后于输入,字会被吞、光标乱跳。
     * 现在调用方返回时 [config] 已经是新值。
     *
     * @param writeDelayMs 写盘前等多久。输入框传几百毫秒:每敲一个字都整份配置跨进程写一遍
     *   没有意义,停手后写一次就够。开关传 0。不管等多久,写的都是写那一刻的最新配置。
     */
    fun update(writeDelayMs: Long = 0, transform: (AiConfig) -> AiConfig) {
        var changed = false
        _config.update { before ->
            transform(before).also { changed = it != before }
        }
        if (changed) scheduleWrite(writeDelayMs)
    }

    /** 把还在等的写盘立刻做掉。界面退到后台时调,免得进程被杀丢掉最后几个字。 */
    fun flush() {
        if (pendingWrite?.isActive == true) scheduleWrite(0)
    }

    @Synchronized
    private fun scheduleWrite(delayMs: Long) {
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            if (delayMs > 0) delay(delayMs)
            persist()
        }
    }

    /**
     * 写失败就回滚到 [persisted],并把 [reachable] 置 false 让界面能报出来。
     * 写的过程中用户又改了的话不回滚 —— 那份更新的改动会排下一次写,不能被这里抹掉。
     */
    private suspend fun persist() = writeLock.withLock {
        val target = _config.value
        if (target == persisted) return@withLock
        val ok = ConfigClient.write(appContext, target)
        _reachable.value = ok
        if (ok) {
            persisted = target
        } else {
            val last = persisted
            if (last != null) _config.compareAndSet(target, last)
        }
    }

    /**
     * 默认配置。跟 [ConfigClient.read] 在拿不到任何数据时的结果保持一致 ——
     * 尤其是几个「空 = 开」的字段,不能在这里写成 false,否则界面第一帧会把
     * 拦截开关显示成关闭的,用户以为自己没开。
     */
    private fun defaultConfig() = AiConfig(
        provider = "",
        endpoint = "",
        apiKey = "",
        model = "",
        systemPrompt = "",
        enabled = false,
        blockViewJump = true,
        jumpAllowWords = DEFAULT_JUMP_ALLOW_WORDS,
        blockWebSearch = true,
        webSearchAllowWords = DEFAULT_WEB_SEARCH_ALLOW_WORDS,
        speakAnswer = true,
        enabledTools = "",
        useNativeTools = true,
        contextEnabled = true,
        skipTakeoverEnabled = true,
        skipTakeoverPattern = DEFAULT_SKIP_TAKEOVER_PATTERN,
        autoFixAccessibility = false,
        mcpServersRaw = ""
    )
}

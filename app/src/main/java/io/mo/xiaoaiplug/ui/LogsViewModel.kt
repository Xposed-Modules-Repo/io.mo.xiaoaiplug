package io.mo.xiaoaiplug.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.xiaoaiplug.config.LogEntry
import io.mo.xiaoaiplug.config.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 搜索框停手多久后才查。 */
private const val KEYWORD_DEBOUNCE_MS = 250L

class LogsViewModel(app: Application) : AndroidViewModel(app) {

    /** null = 不限类型。 */
    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val store get() = LogStore.get(getApplication())

    /** 左滑删掉、还在撤销窗口里的行:界面上已经摘掉,库里还在。落库在 IO 线程,所以用并发集合。 */
    private val pendingDeletes: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * 正在跑的那次查询。新查询开始前先取消它 —— 搜索框每敲一个字都会查一次,
     * 不取消的话慢的旧查询可能比新的晚回来,把列表盖成旧关键词的结果。
     */
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun setType(type: String?) {
        if (_typeFilter.value == type) return
        _typeFilter.value = type
        refresh()
    }

    fun setKeyword(keyword: String) {
        if (_keyword.value == keyword) return
        _keyword.value = keyword
        // 停手一小会儿再查:连着打字时中间那几次查询的结果反正会被下一次盖掉
        refresh(delayMs = KEYWORD_DEBOUNCE_MS)
    }

    fun refresh(delayMs: Long = 0) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            _loading.value = true
            val rows = withContext(Dispatchers.IO) {
                // 滚动上限就是 500 条,一次全取出来比做分页简单,量也扛得住。
                store.query(
                    type = _typeFilter.value,
                    keyword = _keyword.value.trim().ifBlank { null },
                    limit = LogStore.MAX_ROWS
                )
            }
            // 撤销窗口里的行库里还在,别被这次重查又带回来
            _entries.value = rows.filterNot { it.id in pendingDeletes }
            _loading.value = false
        }
    }

    /**
     * 落库不挂在 viewModelScope 上:撤销计时由 ConfigViewModel 管,它触发落库时
     * 这个 ViewModel 可能已经被清掉了,跟着取消的话删掉的记录下次打开又回来。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 删掉单条 —— 先只从界面上摘掉,真正落库等撤销窗口过了再由 [commitDelete] 做。
     *
     * 先把内存里的列表摘掉:列表的移除动画要立刻跟手,等 IO 回来才更新会顿一下。
     * 删完**故意不 refresh** —— 重查会整个换掉 _entries,正在播的移除动画会被拦腰打断。
     */
    fun delete(id: Long) {
        pendingDeletes.add(id)
        _entries.value = _entries.value.filterNot { it.id == id }
    }

    /** 撤销:库里本来就还在,重查一遍它就按原来的时间顺序回到原位。 */
    fun undoDelete(id: Long) {
        if (pendingDeletes.remove(id)) refresh()
    }

    fun commitDelete(id: Long) {
        ioScope.launch {
            store.delete(id)
            // 删完才放手,中间要是正好有一次 refresh,也不会把它带回来
            pendingDeletes.remove(id)
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            refresh()
        }
    }
}

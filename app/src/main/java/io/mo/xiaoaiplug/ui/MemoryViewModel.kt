package io.mo.xiaoaiplug.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.xiaoaiplug.config.MemoryEntry
import io.mo.xiaoaiplug.config.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    /** 上一次写入的失败原因(记忆满了之类),null = 没有。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val store get() = MemoryStore.get(getApplication())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // 撤销窗口里的记忆库里还在,别被这次重查又带回来
            _entries.value = withContext(Dispatchers.IO) { store.all() }
                .filterNot { it.id in pendingDeletes }
        }
    }

    /** 手动加一条。加完要 refresh —— 新行的 id 是数据库给的,本地编不出来。 */
    fun add(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                store.append(content, MemoryEntry.SOURCE_MANUAL)
            }
            // append 的失败约定是 "error:" 前缀(给模型看的),界面上把前缀去掉再显示
            _error.value = if (result.startsWith("error:")) result.removePrefix("error:").trim() else null
            refresh()
        }
    }

    /** 改一条。先动内存再落库,免得输入框失焦到列表刷新之间闪一下旧文字。 */
    fun edit(id: Long, content: String) {
        if (content.isBlank()) return
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(content = content.trim(), source = MemoryEntry.SOURCE_MANUAL) else it
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.update(id, content) }
        }
    }

    /** 左滑删掉、还在撤销窗口里的记忆。理由和做法同 LogsViewModel。 */
    private val pendingDeletes: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** 落库不挂在 viewModelScope 上,理由同 LogsViewModel.ioScope。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 理由同 LogsViewModel.delete:先摘掉、删完故意不 refresh,免得打断移除动画;
     * 真正落库等撤销窗口过了再由 [commitDelete] 做。
     */
    fun delete(id: Long) {
        pendingDeletes.add(id)
        _entries.value = _entries.value.filterNot { it.id == id }
    }

    /** 撤销:库里本来就还在,重查一遍就按原顺序回到原位(记忆按 id 排序,重新插入会跑到最前面)。 */
    fun undoDelete(id: Long) {
        if (pendingDeletes.remove(id)) refresh()
    }

    fun commitDelete(id: Long) {
        ioScope.launch {
            store.delete(id)
            pendingDeletes.remove(id)
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            refresh()
        }
    }

    fun dismissError() {
        _error.value = null
    }
}

package com.xiaoai.plug.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoai.plug.config.LogEntry
import com.xiaoai.plug.config.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val rows = withContext(Dispatchers.IO) {
                // 滚动上限就是 500 条,一次全取出来比做分页简单,量也扛得住。
                store.query(
                    type = _typeFilter.value,
                    keyword = _keyword.value.trim().ifBlank { null },
                    limit = LogStore.MAX_ROWS
                )
            }
            _entries.value = rows
            _loading.value = false
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            refresh()
        }
    }
}

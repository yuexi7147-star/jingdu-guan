package com.jingdu.guan.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jingdu.guan.data.market.RssSourceMarket
import com.jingdu.guan.data.market.SourceHealthCheck
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 源市场：从源仓库拉列表、搜索、批量检测。
 *
 * 检测是三级（结构 → 可达 → 内容），越往后越慢；并发 12 个，每个超时由 OkHttp 客户端管。
 * 结果按状态分组，用户默认只导入通过的，但能看到每一类有多少、为什么。
 */
class SourceMarketViewModel(
    private val market: RssSourceMarket,
    private val checker: SourceHealthCheck,
) : ViewModel() {

    private val _all = MutableStateFlow<List<RssSourceMarket.Entry>>(emptyList())
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _page = MutableStateFlow(1)
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 当前可见（按搜索词过滤后）的条目 */
    val visible: StateFlow<List<RssSourceMarket.Entry>> = _all

    private val _checked = MutableStateFlow<Map<String, SourceHealthCheck.Result>>(emptyMap())
    val checked: StateFlow<Map<String, SourceHealthCheck.Result>> = _checked.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private var loadJob: Job? = null
    private var checkJob: Job? = null

    fun load(reset: Boolean = false) {
        if (_loading.value) return
        if (reset) {
            _page.value = 1
            _hasMore.value = true
            _all.value = emptyList()
        }
        if (!_hasMore.value) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { market.fetchPage(_page.value).getOrThrow() }
                .onSuccess { list ->
                    _all.value = (_all.value + list).distinctBy { it.id }
                    _hasMore.value = list.isNotEmpty()
                    _page.value = _page.value + 1
                }
                .onFailure { _error.value = it.message ?: "拉取失败" }
            _loading.value = false
        }
    }

    fun search(q: String) {
        _query.value = q
    }

    /** 过滤后的列表 */
    fun filtered(): List<RssSourceMarket.Entry> {
        val q = _query.value.trim()
        if (q.isEmpty()) return _all.value
        return _all.value.filter { it.name.contains(q, true) || it.url.contains(q, true) }
    }

    /** 对当前可见的条目跑三级检测 */
    fun checkAll() {
        if (_checking.value) return
        val targets = filtered().filter { _checked.value[it.id] == null }
        if (targets.isEmpty()) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _checking.value = true
            _progress.value = 0 to targets.size
            val sem = Semaphore(12)
            var done = 0
            val results = targets.map { entry ->
                async {
                    sem.withPermit {
                        val r = checker.check(entry, market)
                        synchronized(this@SourceMarketViewModel) {
                            done++
                            _progress.value = done to targets.size
                            _checked.value = _checked.value + (entry.id to r)
                        }
                        r
                    }
                }
            }.awaitAll()
            _checked.value = _checked.value + results.associateBy { it.entry.id }
            _checking.value = false
        }
    }

    fun stopCheck() {
        checkJob?.cancel()
        _checking.value = false
    }

    fun clearCheck() {
        _checked.value = emptyMap()
    }
}

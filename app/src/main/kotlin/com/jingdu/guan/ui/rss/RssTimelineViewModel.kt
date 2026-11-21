package com.jingdu.guan.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jingdu.guan.core.source.rss.RssSort
import com.jingdu.guan.core.source.rss.RssSourceRule
import com.jingdu.guan.data.repo.RssFeed
import com.jingdu.guan.data.repo.RssFeedRepository
import com.jingdu.guan.data.repo.RssRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 订阅页上的一个源（chip 一个） */
data class RssTimelineSource(val id: String, val name: String, val sorts: List<RssSort>)

data class RssTimelineUiState(
    /** 开着的、有文章列表的订阅源；null = 还没从库里读回来（先什么都不画，别闪一下空态） */
    val sources: List<RssTimelineSource>? = null,
    /** 开着的订阅源里有网页类的：它们没有文章列表，不进时间线。全是网页类时空态得说清楚 */
    val hasWebSources: Boolean = false,
    /** 只看哪个源；null = 全部 */
    val selectedSource: String? = null,
    val sortIndex: Int = 0,
    val sections: List<TimelineSection> = emptyList(),
    /** 缓存从库里读回来过没有（第一次读回来之前不画空态） */
    val articlesLoaded: Boolean = false,
    /** 当前这几条列表有正在取的（或马上要取的） */
    val fetching: Boolean = false,
    /** 本地搜索词（空 = 不搜，走正常时间线） */
    val searchQuery: String = "",
    /** 搜索结果；searchQuery 非空时用它代替 sections */
    val searchResults: List<TimelineItem> = emptyList(),
    /** 下拉刷新的转圈：只跟着用户自己拉的那一次 */
    val userRefreshing: Boolean = false,
    /** 当前这几条列表里取失败的：源名 → 原因 */
    val failures: List<Pair<String, String>> = emptyList(),
) {
    val selected: RssTimelineSource? get() = sources?.firstOrNull { it.id == selectedSource }
    val isEmpty: Boolean get() = sections.isEmpty()
}

/**
 * 订阅页：所有开着的订阅源的文章排成一条（「全部」），或者只看一个源（它有分类就再选分类）。
 *
 * 进页先显示库里缓存的，同时把这次打开 App 还没取过的列表取一遍；取过的不再取，下拉才重取（见 [RssFeedRepository]）。
 * 「全部」里每个源只取第一个分类 —— 分类多的源（书单、排行）全取一遍要好几倍的请求，时间线也会被一个源刷屏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssTimelineViewModel(
    initialSourceId: String?,
    private val repo: RssRepository,
    private val feeds: RssFeedRepository,
    private val articleDao: com.jingdu.guan.data.db.dao.RssArticleDao,
    private val engine: com.jingdu.guan.core.source.rss.RssEngine,
) : ViewModel() {

    private data class Selection(val sourceId: String?, val sortIndex: Int)

    /** 开着的源解析好的规则（解析 JSON 不便宜，订阅源变了才重算） */
    private data class Catalog(
        val sources: List<RssTimelineSource>,
        val rules: Map<String, RssSourceRule>,
        val hasWeb: Boolean,
    )


    /** 源内搜索结果；null = 没在搜 */
    private val _sourceSearch = MutableStateFlow<SourceSearchState?>(null)
    val sourceSearch: kotlinx.coroutines.flow.StateFlow<SourceSearchState?> = _sourceSearch

    /**
     * 源内搜索：走源自己的 searchUrl，只对当前选中的那个源有效。
     *
     * 「全部」下不给搜 —— 几十个源各发一次请求，既慢又容易被站点限流；
     * 想看某个源里有什么，先选它。
     */
    fun searchInSource(keyword: String) {
        val sid = state.value.selectedSource ?: return
        if (keyword.isBlank()) { _sourceSearch.value = null; return }
        _sourceSearch.value = SourceSearchState(keyword = keyword, loading = true, items = emptyList(), error = null)
        viewModelScope.launch {
            val r = runCatching { repo.searchInSource(sid, keyword) }
            _sourceSearch.value = SourceSearchState(
                keyword = keyword,
                loading = false,
                items = r.getOrDefault(emptyList()).map { a ->
                    TimelineItem(
                        row = com.jingdu.guan.data.db.entity.RssArticleRow(
                            feed = a.sourceId, articleKey = a.link.ifBlank { a.title },
                            sourceId = a.sourceId, title = a.title, link = a.link,
                            image = a.image, pubDate = a.pubDate, pubTime = 0,
                            firstSeenAt = 0, lastSeenAt = 0, position = 0, readAt = 0,
                        ),
                        sourceName = state.value.sources?.firstOrNull { it.id == a.sourceId }?.name,
                    )
                },
                error = r.exceptionOrNull()?.message?.take(60),
            )
        }
    }

    fun clearSourceSearch() { _sourceSearch.value = null }

    /** 本地搜索词（空 = 不搜） */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: kotlinx.coroutines.flow.StateFlow<String> = _searchQuery

    /** 搜索结果；null = 没在搜 */
    private val _searchResults = MutableStateFlow<List<TimelineItem>?>(null)
    val searchResults: kotlinx.coroutines.flow.StateFlow<List<TimelineItem>?> = _searchResults

    private var searchJob: Job? = null

    /**
     * 本地搜索：在已拉下来的条目里按标题找。
     *
     * RSS 只给最近 N 条，搜不到更早的历史 —— 这是本地搜索的边界。
     * 换来的即时、离线、不耗流量值得。
     */
    fun search(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }
        searchJob = viewModelScope.launch {
            val rows = runCatching { articleDao.searchByTitle(query.trim()) }.getOrDefault(emptyList())
            val names = state.value.sources.orEmpty().associate { it.id to it.name }
            _searchResults.value = rows.mapNotNull { r ->
                val name = names[r.sourceId] ?: return@mapNotNull null
                TimelineItem(row = r, sourceName = name)
            }
        }
    }

    private val selection = MutableStateFlow(Selection(initialSourceId, 0))

    private val catalog: StateFlow<Catalog?> = repo.sources
        .map { list ->
            val parsed = list.filter { it.enabled }.mapNotNull { e -> repo.ruleOf(e)?.let { e to it } }
            val feedRules = parsed.filter { (_, r) -> !r.singleUrl }
            Catalog(
                sources = feedRules.map { (e, r) -> RssTimelineSource(e.id, e.name, r.sortsOrDefault()) },
                rules = feedRules.associate { (e, r) -> e.id to r },
                hasWeb = parsed.any { (_, r) -> r.singleUrl },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前看的是什么：哪几条列表、是不是只看一个源（选中的源关掉、删掉了就回到「全部」） */
    private data class View(val feeds: List<RssFeed>, val single: Boolean) {
        val keys: List<String> get() = feeds.map { it.key }
    }

    private val view: StateFlow<View?> = combine(catalog.filterNotNull(), selection) { c, sel ->
        val one = sel.sourceId?.let { c.rules[it] }
        if (one != null) {
            View(listOfNotNull(one.sortsOrDefault().getOrNull(sel.sortIndex)?.let { RssFeed(one, it) }), single = true)
        } else {
            View(c.sources.mapNotNull { s -> c.rules[s.id]?.let { r -> RssFeed(r, r.sortsOrDefault().first()) } }, single = false)
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 正在取的列表（feed key）；取的过程跨越切换 chip，不因为换了看哪个就掐掉 */
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** 取失败的列表：feed key → 原因。成功一次就去掉 */
    private val failed = MutableStateFlow<Map<String, String>>(emptyMap())

    private val userRefreshing = MutableStateFlow(false)

    private data class Articles(val feeds: List<String>, val sections: List<TimelineSection>)

    private val articles: StateFlow<Articles?> = view.filterNotNull()
        .flatMapLatest { v ->
            val keys = v.keys
            if (keys.isEmpty()) return@flatMapLatest flowOf(Articles(keys, emptyList()))
            val names = catalog.value?.sources?.associate { it.id to it.name }.orEmpty()
            // 表按整张失效：只看一个源时别的源写库、点开一篇标成读过（同一篇在别的分类里那行）也会再查一遍，
            // 查回来一模一样就别重排、别让整页重组
            feeds.observe(keys).distinctUntilChanged().map { rows -> Articles(keys, buildTimeline(rows, names, v.single)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Status(val inFlight: Set<String>, val failed: Map<String, String>, val userRefreshing: Boolean)

    private val status = combine(inFlight, failed, userRefreshing) { a, b, c -> Status(a, b, c) }

    val state: StateFlow<RssTimelineUiState> =
        combine(catalog, selection, view, articles, status) { c, sel, v, arts, st ->
            val visible = v?.feeds
            val visibleKeys = v?.keys.orEmpty()
            val names = c?.sources?.associate { it.id to it.name }.orEmpty()
            RssTimelineUiState(
                sources = c?.sources,
                hasWebSources = c?.hasWeb == true,
                // 选中的源不在了（关掉、删掉）就回到「全部」；订阅源还没读回来时先照进来时的选
                selectedSource = if (v == null) sel.sourceId else sel.sourceId.takeIf { v.single },
                sortIndex = sel.sortIndex,
                // 换 chip 时新一组从库里读回来要几毫秒：这期间接着摆上一组，别整页先白一下
                sections = arts?.sections.orEmpty(),
                articlesLoaded = arts != null,
                // 这次还没取过、也没失败的算「要取」：进页第一帧取的协程还没起来，不算的话空缓存先闪一下「还没有文章」
                fetching = visibleKeys.any { it in st.inFlight || (it !in st.failed && !feeds.fetchedThisSession(it)) },
                userRefreshing = st.userRefreshing,
                failures = visible.orEmpty().mapNotNull { f ->
                    st.failed[f.key]?.let { reason -> (names[f.rule.id] ?: f.rule.name) to reason }
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RssTimelineUiState(selectedSource = initialSourceId))

    init {
        // 看的东西一变（进页、切 chip、新开了一个源），把这次还没取过的取一遍
        viewModelScope.launch {
            view.filterNotNull().collect { v -> fetch(v.feeds, force = false) }
        }
    }

    fun selectSource(sourceId: String?) {
        if (sourceId == selection.value.sourceId) return
        selection.value = Selection(sourceId, 0)
    }

    fun selectSort(index: Int) {
        selection.update { it.copy(sortIndex = index) }
    }

    /** 下拉刷新：当前看的这几条全部重取 */
    fun refresh() {
        val list = view.value?.feeds ?: return
        viewModelScope.launch {
            userRefreshing.value = true
            try {
                fetch(list, force = true).join()
            } finally {
                userRefreshing.value = false
            }
        }
    }

    /** 「没更新上」那一行的重试：只重取失败的 */
    fun retryFailed() {
        val list = view.value?.feeds?.filter { it.key in failed.value } ?: return
        fetch(list, force = true)
    }

    /** 点开一篇：标成读过（标题变灰），返回文章页要的参数。全文不随参数走，文章页按 feed + articleKey 从库里取 */

    /**
     * 这条能不能直接播：从库里读正文，找视频直链。
     *
     * 找不到就给 null，页面照旧进文章页 —— 图文条目本来就没有视频。
     * 这里只在**点击时**读一次库，列表滚动时不碰 description（那个字段可能有几十 KB）。
     */
    /**
     * 这条能不能直接播：跑源的正文规则，看它输出的是不是一条视频直链。
     *
     * 必须真的跑 ruleContent —— 视频源（type=2）的地址就是脚本现算出来的，
     * 只在已存的正文里正则找 .m3u8 是找不到的（正文里根本没写）。
     */
    suspend fun mediaUrlOf(item: TimelineItem): String? {
        val row = item.row
        return runCatching {
            val rule = repo.getRule(row.sourceId) ?: return@runCatching null
            val desc = articleDao.getDescription(row.feed, row.articleKey)
            val article = com.jingdu.guan.core.source.rss.RssArticle(
                sourceId = row.sourceId,
                title = row.title,
                link = row.link,
                description = desc,
                pubDate = row.pubDate,
            )
            // 直接问引擎要内容：它会跑 ruleContent
            val content = engine.content(rule, article)
            content.mediaUrl
                ?: com.jingdu.guan.ui.player.MediaUrlExtractor.extract(desc)
        }.getOrNull()
    }

    fun open(item: TimelineItem): RssArticleArgs {
        val row = item.row
        viewModelScope.launch { feeds.markRead(row.sourceId, row.articleKey) }
        return RssArticleArgs(
            sourceId = row.sourceId,
            title = row.title,
            link = row.link,
            pubDate = row.pubDate,
            feed = row.feed,
            articleKey = row.articleKey,
        )
    }

    /**
     * 取 [list] 里该取的那些。已经在取的不重复发（切走又切回来、下拉时自动取还没完），
     * 各条成功的去掉失败记号、失败的记下原因。
     */
    private fun fetch(list: List<RssFeed>, force: Boolean) = viewModelScope.launch {
        val todo = list.filter { f ->
            f.key !in inFlight.value && (force || !feeds.fetchedThisSession(f.key))
        }
        if (todo.isEmpty()) return@launch
        val keys = todo.map { it.key }.toSet()
        inFlight.update { it + keys }
        try {
            val errors = feeds.refresh(todo, force = true)
            failed.update { old ->
                (old - keys) + todo.mapNotNull { f -> errors[f.rule.id]?.let { f.key to it } }
            }
        } finally {
            inFlight.update { it - keys }
        }
    }
}

/** 源内搜索的状态 */
data class SourceSearchState(
    val keyword: String,
    val loading: Boolean,
    val items: List<TimelineItem>,
    val error: String?,
)

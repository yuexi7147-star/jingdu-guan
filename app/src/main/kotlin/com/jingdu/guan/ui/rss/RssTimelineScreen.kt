package com.jingdu.guan.ui.rss

import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.icon.extended.Search
import com.jingdu.guan.ui.components.CompactTextField
import com.jingdu.guan.ui.theme.AppShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingdu.guan.ui.components.AppIconButton
import com.jingdu.guan.ui.components.AppLoadingIndicator
import com.jingdu.guan.ui.components.AppScaffold
import com.jingdu.guan.ui.components.AppTextButton
import com.jingdu.guan.ui.components.AppToolbar
import com.jingdu.guan.ui.components.BackButton
import com.jingdu.guan.ui.components.BookCover
import com.jingdu.guan.ui.components.ChipRow
import com.jingdu.guan.ui.components.ContentListDefaults
import com.jingdu.guan.ui.components.ContentListItem
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.EmptyState
import com.jingdu.guan.ui.components.ErrorState
import com.jingdu.guan.ui.components.LoadingState
import com.jingdu.guan.ui.components.SectionHeader
import com.jingdu.guan.ui.components.SquircleCorners
import com.jingdu.guan.ui.components.ToolbarTitle
import com.jingdu.guan.ui.components.formatRelativeTime
import com.jingdu.guan.ui.components.groupedCardItem
import com.jingdu.guan.ui.components.horizontalOnly
import com.jingdu.guan.ui.components.pageScroll
import com.jingdu.guan.ui.components.settingsPageColor
import com.jingdu.guan.ui.components.siteOrigin
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

/**
 * 订阅页：打开就是文章。所有开着的订阅源的文章排成一条，新的在上、按天分段；标题下面一排来源 chip 只看一个。
 * 订阅源的增删、开关在右上角进的「订阅源」页（[RssSourceScreen]）。
 *
 * 顶栏用窄栏（AppToolbar）不用折叠大标题：下面压着下拉刷新，两套下拉手势叠在一起会互相抢（同书架首页）。
 */
@Composable
fun RssTimelineScreen(
    onPlay: (url: String, title: String, subtitle: String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenArticle: (RssArticleArgs) -> Unit,
    viewModel: RssTimelineViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var searchKey by remember { mutableStateOf("") }
    val pageColor = settingsPageColor()
    val sources = state.sources

    AppScaffold(
        containerColor = pageColor,
        topBar = {
            AppToolbar(
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor,
                actions = {
                    // 源内搜索：只有选中具体某个源时才出现 —— 「全部」下发请求等于朝几十个站同时打
                    if (state.selectedSource != null) {
                        AppIconButton(onClick = { showSearch = !showSearch }) {
                            Icon(MiuixIcons.Search, contentDescription = "在源内搜索")
                        }
                    }
                    AppIconButton(onClick = onOpenSources) {
                        Icon(MiuixIcons.Tune, contentDescription = "订阅源")
                    }
                },
                // 来源 chip、分类 chip 钉在栏底下：文章从它们下面滚过去，一起被毛玻璃盖住
                bottomContent = {
                    if (showSearch) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompactTextField(
                                value = searchKey,
                                onValueChange = { searchKey = it },
                                placeholder = "搜这个源里的内容",
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "搜索",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { viewModel.searchInSource(searchKey) }
                                    .padding(horizontal = Dimens.gapM, vertical = Dimens.gapS),
                            )
                        }
                    }
                    if (!sources.isNullOrEmpty()) SourceChips(state, viewModel)
                },
            ) {
                ToolbarTitle("订阅")
            }
        },
    ) { padding ->
        when {
            // 订阅源还没从库里读回来：先空着，别闪一下空态
            sources == null -> Unit
            sources.isEmpty() -> EmptyState(
                icon = Icons.Outlined.RssFeed,
                title = if (state.hasWebSources) "开着的订阅源都是网页" else "还没有开着的订阅源",
                hint = if (state.hasWebSources) {
                    "网页类订阅源没有文章列表，在「订阅源」里点开它"
                } else {
                    "粘一个 RSS/Atom 地址就能订阅，也支持导入阅读的订阅源"
                },
                actionLabel = "订阅源",
                onAction = onOpenSources,
                modifier = Modifier.padding(padding),
            )
            !state.articlesLoaded -> Unit
            state.isEmpty && state.fetching -> LoadingState(Modifier.padding(padding), label = "正在取文章…")
            state.isEmpty && state.failures.isNotEmpty() -> ErrorState(
                message = state.failures.joinToString("\n") { (name, reason) -> "$name：$reason" },
                title = "没取到文章",
                modifier = Modifier.padding(padding),
                onRetry = viewModel::retryFailed,
            )
            else -> PullToRefresh(
                isRefreshing = state.userRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding.horizontalOnly()),
                pullToRefreshState = rememberPullToRefreshState(),
                // 只用 top：刷新圈从栏下沿冒出来，而不是藏在玻璃栏后面
                contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                refreshTexts = REFRESH_TEXTS,
            ) {
                // 换一个源、换一个分类就回到顶上：接着上一组的滚动位置，看到的是新一组的半中间
                val listState = rememberSaveable(state.selectedSource, state.sortIndex, saver = LazyListState.Saver) {
                    LazyListState()
                }
                // 只让开两侧：栏高垫在列表开头（文章滚到毛玻璃栏下面），导航栏高度垫在末尾（见 AppScaffold）
                LazyColumn(
                    Modifier.fillMaxSize().pageScroll(),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + Dimens.gapS,
                        bottom = padding.calculateBottomPadding() + Dimens.gapS,
                    ),
                    overscrollEffect = null,
                ) {
                    statusItem(state, viewModel)
                    if (state.isEmpty) {
                        item(key = "empty", contentType = "empty") {
                            EmptyState(
                                icon = Icons.Outlined.RssFeed,
                                title = "这里还没有文章",
                                hint = "下拉刷新试试",
                            )
                        }
                    }
                    timelineItems(state.sections, onOpen = { item ->
                        viewModel.openOrPlay(
                            item,
                            onPlay = { url, title, sub -> onPlay(url, title, sub) },
                            onArticle = onOpenArticle,
                        )
                    })
                }
            }
        }
    }
}

/** 「全部」+ 每个源；选中的源有好几个分类时，下面再一排分类 */
@Composable
private fun SourceChips(state: RssTimelineUiState, viewModel: RssTimelineViewModel) {
    val sources = state.sources.orEmpty()
    val selectedIndex = state.selectedSource?.let { id -> sources.indexOfFirst { it.id == id } + 1 } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
        ChipRow(
            options = listOf("全部") + sources.map { it.name },
            selectedIndex = selectedIndex,
            onSelect = { i -> viewModel.selectSource(if (i == 0) null else sources[i - 1].id) },
            contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
            // 从订阅源页点进来的那个源可能排在条外面：亮着的 chip 自己横滚进来
            bringSelectedIntoView = true,
        )
        val sorts = state.selected?.sorts.orEmpty()
        if (sorts.size > 1) {
            ChipRow(
                options = sorts.map { it.title },
                selectedIndex = state.sortIndex,
                onSelect = viewModel::selectSort,
                contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
            )
        }
    }
}

/**
 * 列表顶上的一行状态：有源没取到时「某某 没更新上（原因）· 重试」，别的源的文章照常显示；
 * 有缓存、后台还在取时「正在更新…」（没缓存时整页转圈，不走这里）。
 */
private fun LazyListScope.statusItem(state: RssTimelineUiState, viewModel: RssTimelineViewModel) {
    val failures = state.failures
    if (failures.isEmpty() && !state.fetching) return
    item(key = "status", contentType = "status") {
        Row(
            Modifier
                .padding(bottom = Dimens.gapS)
                .groupedCardItem(0, 1)
                .heightIn(min = Dimens.touchTarget)
                .padding(start = Dimens.listHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            if (failures.isEmpty()) {
                AppLoadingIndicator(size = Dimens.buttonSpinner)
                Text(
                    "正在更新…",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    failureLine(failures),
                    Modifier.weight(1f).padding(vertical = Dimens.gapS),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AppTextButton("重试", onClick = viewModel::retryFailed, enabled = !state.fetching)
            }
        }
    }
}

/** 一个源：「机核 没更新上（连接超时）」；好几个：「机核、爱范儿 没更新上」（原因各不相同，写不下） */
private fun failureLine(failures: List<Pair<String, String>>): String =
    if (failures.size == 1) {
        val (name, reason) = failures.single()
        "$name 没更新上（$reason）"
    } else {
        failures.joinToString("、") { it.first } + " 没更新上"
    }

/** 按天分段：每段一个小标题 + 一张卡；只看一个源时只有一张卡、没有小标题 */
private fun LazyListScope.timelineItems(sections: List<TimelineSection>, onOpen: (TimelineItem) -> Unit) {
    val today = LocalDate.now()
    sections.forEach { section ->
        val day = section.day
        if (day != null) {
            item(key = "day:$day", contentType = "day") { SectionHeader(sectionTitle(day, today)) }
        }
        itemsIndexed(section.items, key = { _, it -> it.key }, contentType = { _, _ -> "article" }) { index, item ->
            MediaCard(
                item,
                onClick = { onOpen(item) },
                modifier = Modifier.padding(bottom = Dimens.gapM),
            )
        }
    }
}

/**
 * 一篇：标题（最多两行，读过的变灰）、来源 · 多久以前，有配图的右边一张缩略图。
 * 只看一个源时不写来源。发布时间认不出（规则源抓回来的怪格式）就原样写那一串，好过什么都不写。
 */
@Composable
private fun ArticleRow(item: TimelineItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val row = item.row
    val read = row.readAt > 0
    val time = remember(row.pubTime, row.pubDate) {
        if (row.pubTime > 0) formatRelativeTime(row.pubTime) else row.pubDate?.trim()?.takeIf { it.isNotEmpty() }
    }
    val meta = listOfNotNull(item.sourceName, time).joinToString(" · ").takeIf { it.isNotEmpty() }
    ContentListItem(
        onClick = onClick,
        modifier = modifier,
        // 底与圆角由外面的卡（groupedCardItem）画，行本身透明、直角
        colors = ContentListDefaults.plainColors(),
        corners = SquircleCorners.Square,
        trailingContent = row.image?.takeIf { it.isNotBlank() }?.let { url ->
            {
                BookCover(
                    title = row.title,
                    coverModel = url,
                    modifier = Modifier.size(width = Dimens.articleThumbWidth, height = Dimens.articleThumbHeight),
                    placeholderChars = 2,
                    // 标题是这一行的名字，配图不再进读屏
                    contentDescription = null,
                    // 防盗链的图床：取不到再带上文章网站的来源取一次
                    retryReferer = siteOrigin(row.link),
                )
            }
        },
        supportingContent = meta?.let {
            {
                Text(it, style = ContentListDefaults.overlineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        content = {
            Text(
                row.title,
                style = ContentListDefaults.titleStyle,
                // 读过的变灰：一眼看出哪些是新的（说明色的对比度在 AppThemesTest 里钉过）
                color = if (read) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 下拉刷新的四段状态字：下拉中 / 过了阈值 / 刷新中 / 完成（顺序是 Miuix PullToRefresh 规定的） */
private val REFRESH_TEXTS = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")


/**
 * 一条媒体的卡片：大封面 + 标题 + 来源时间。
 *
 * 视频/音频共用一张卡 —— 这个 App 只收这两类，再用不同的行样式区分意义不大；
 * 真正要区分的是横竖屏（封面比例），那要等拿到图片真实尺寸，下一版再做。
 * 看过的条目封面压暗 + 标题变淡，和书架里读过的那本一个观感。
 */
@Composable
private fun MediaCard(item: TimelineItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val row = item.row
    val read = row.readAt > 0
    val time = remember(row.pubTime, row.pubDate) {
        if (row.pubTime > 0) formatRelativeTime(row.pubTime) else row.pubDate?.trim()?.takeIf { it.isNotEmpty() }
    }
    val meta = listOfNotNull(item.sourceName, time).joinToString(" · ").takeIf { it.isNotEmpty() }

    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(bottom = Dimens.gapM),
    ) {
        val image = row.image?.takeIf { it.isNotBlank() }
        if (image != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = AppShapes.radiusMedium, topEnd = AppShapes.radiusMedium)),
            ) {
                BookCover(
                    title = row.title,
                    coverModel = image,
                    modifier = Modifier.fillMaxSize(),
                )
                if (read) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f)))
                }
            }
        }
        Column(Modifier.padding(horizontal = Dimens.gapM, vertical = Dimens.gapS)) {
            Text(
                text = row.title,
                style = MiuixTheme.textStyles.body1,
                color = if (read) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Spacer(Modifier.height(Dimens.gapXS))
                Text(
                    text = meta,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

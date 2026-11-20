package com.jingdu.guan.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.horizontalOnly
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingdu.guan.data.market.SourceHealthCheck
import com.jingdu.guan.ui.components.AppLoadingIndicator
import com.jingdu.guan.ui.components.AppScaffold
import com.jingdu.guan.ui.components.AppTextButton
import com.jingdu.guan.ui.components.AppToolbar
import com.jingdu.guan.ui.components.BatchAction
import com.jingdu.guan.ui.components.BatchActionBar
import com.jingdu.guan.ui.components.DeterminateProgressBar
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.EmptyState
import com.jingdu.guan.ui.components.ErrorState
import com.jingdu.guan.ui.components.SearchField
import com.jingdu.guan.ui.components.ToolbarTitle
import com.jingdu.guan.ui.components.groupedCardItem
import com.jingdu.guan.ui.components.pageScroll
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.jingdu.guan.ui.components.CardViewport
import com.jingdu.guan.ui.components.ContentListItem
import com.jingdu.guan.ui.components.ContentListDefaults

/**
 * 源市场：列出源仓库里的订阅源，勾选后批量检测、批量订阅。
 *
 * 不做内容过滤 —— 这是用户自己挑源的地方，判定交给导入后的自动校验与手动删除。
 */
@Composable
fun SourceMarketScreen(
    viewModel: SourceMarketViewModel,
    onBack: () -> Unit,
) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val checked by viewModel.checked.collectAsStateWithLifecycle()

    val list = viewModel.filtered()
    val okCount = checked.values.count { it.status == SourceHealthCheck.Status.OK }
    val deadCount = checked.values.count { it.status == SourceHealthCheck.Status.DEAD }
    val suspectCount = checked.values.count { it.status == SourceHealthCheck.Status.SUSPECT }
    val notMedia = checked.values.count { it.status == SourceHealthCheck.Status.NOT_MEDIA }

    AppScaffold(
        topBar = {
            AppToolbar(
                title = { ToolbarTitle("源市场") },
                navigationIcon = { com.jingdu.guan.ui.components.BackButton(onBack) },
                bottomContent = {
                    SearchField(
                        value = query,
                        onValueChange = viewModel::search,
                        placeholder = "搜索源名称或地址",
                    )
                },
            )
        },
        floatingToolbar = {
            BatchActionBar(
                visible = !checking,
                onHeightChange = {},
                actions = listOf(
                    BatchAction(
                        icon = top.yukonga.miuix.kmp.icon.extended.Refresh,
                        label = "检测",
                        onClick = viewModel::checkAll,
                    ),
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding.horizontalOnly())) {
            when {
                error != null && list.isEmpty() -> ErrorState(
                    message = error ?: "",
                    onRetry = { viewModel.load(true) },
                    modifier = Modifier.padding(padding),
                )
                list.isEmpty() && loading -> Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { AppLoadingIndicator() }
                list.isEmpty() -> EmptyState(
                    icon = top.yukonga.miuix.kmp.icon.extended.Search,
                    title = "没有找到来源",
                    hint = "换个关键词，或下拉刷新",
                    modifier = Modifier.padding(padding),
                )
                else -> {
                    if (checking) {
                        Column(Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS)) {
                            Text(
                                text = "正在检测 " + progress.first + " / " + progress.second,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(Dimens.gapXS))
                            DeterminateProgressBar(
                                progress = if (progress.second == 0) 0f
                                else progress.first.toFloat() / progress.second,
                            )
                            Spacer(Modifier.height(Dimens.gapXS))
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
                                Text("可用 " + okCount, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
                                Text("失效 " + deadCount, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                                Text("疑似 " + suspectCount, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text("非媒体 " + notMedia, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }

                    LazyColumn(
                        Modifier.fillMaxSize().pageScroll(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = padding.calculateTopPadding() + Dimens.gapS,
                            bottom = padding.calculateBottomPadding() + Dimens.gapXXL,
                        ),
                        overscrollEffect = null,
                    ) {
                        itemsIndexed(list, key = { _, it -> it.id }) { index, entry ->
                            MarketRow(
                                name = entry.name,
                                url = entry.url,
                                result = checked[entry.id],
                                index = index,
                                count = list.size,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketRow(
    name: String,
    url: String,
    result: SourceHealthCheck.Result?,
    index: Int,
    count: Int,
) {
    val (statusText, statusColor) = when (result?.status) {
        SourceHealthCheck.Status.OK -> "可用 · " + result.detail to MiuixTheme.colorScheme.primary
        SourceHealthCheck.Status.DEAD -> "失效 · " + result.detail to MiuixTheme.colorScheme.error
        SourceHealthCheck.Status.SUSPECT -> "疑似 · " + result.detail to MiuixTheme.colorScheme.onSurfaceVariantSummary
        SourceHealthCheck.Status.NOT_MEDIA -> "非视频/音频 · " + result.detail to MiuixTheme.colorScheme.onSurfaceVariantSummary
        null -> null to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    ContentListItem(
        title = name,
        titleStyle = ContentListDefaults.titleStyle,
        supporting = statusText ?: url,
        supportingStyle = ContentListDefaults.supportingStyle,
        supportingColor = statusColor,
        modifier = Modifier.groupedCardItem(index, count),
        onClick = {},
    )
}

package com.jingdu.guan.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingdu.guan.data.market.SourceHealthCheck
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.PrimaryButton
import com.jingdu.guan.ui.components.SecondaryButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 源市场：列出源仓库里的订阅源，勾选后批量检测。
 *
 * 这里刻意用最基础的组件铺页面 —— 它是个工具页，不是阅读页，密度比装饰重要。
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
    val ok = checked.values.count { it.status == SourceHealthCheck.Status.OK }
    val dead = checked.values.count { it.status == SourceHealthCheck.Status.DEAD }
    val suspect = checked.values.count { it.status == SourceHealthCheck.Status.SUSPECT }
    val notMedia = checked.values.count { it.status == SourceHealthCheck.Status.NOT_MEDIA }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(text = "返回", onClick = onBack)
            Spacer(Modifier.padding(horizontal = Dimens.gapS))
            Text("源市场", style = MiuixTheme.textStyles.title3)
        }

        TextField(
            value = query,
            onValueChange = viewModel::search,
            label = "搜索源名称或地址",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding),
        )

        Spacer(Modifier.height(Dimens.gapS))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            PrimaryButton(
                text = if (checking) "检测中 " + progress.first + "/" + progress.second else "检测全部",
                onClick = { if (checking) viewModel.stopCheck() else viewModel.checkAll() },
            )
            if (checked.isNotEmpty()) {
                SecondaryButton(text = "清空结果", onClick = viewModel::clearCheck)
            }
        }

        if (checked.isNotEmpty()) {
            Text(
                text = "可用 " + ok + " · 失效 " + dead + " · 疑似 " + suspect + " · 非媒体 " + notMedia,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapXS),
            )
        }

        when {
            error != null && list.isEmpty() -> Column(
                Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error ?: "", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(Dimens.gapM))
                PrimaryButton(text = "重试", onClick = { viewModel.load(true) })
            }

            list.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在拉取…", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "正在拉取…" else "没有找到来源",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    top = Dimens.gapS,
                    bottom = Dimens.gapXXL,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
            ) {
                items(list, key = { it.id }) { entry ->
                    val r = checked[entry.id]
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.name, style = MiuixTheme.textStyles.body1, maxLines = 1)
                        Text(
                            text = when (r?.status) {
                                SourceHealthCheck.Status.OK -> "可用 · " + r.detail
                                SourceHealthCheck.Status.DEAD -> "失效 · " + r.detail
                                SourceHealthCheck.Status.SUSPECT -> "疑似 · " + r.detail
                                SourceHealthCheck.Status.NOT_MEDIA -> "非视频/音频 · " + r.detail
                                null -> entry.url
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = when (r?.status) {
                                SourceHealthCheck.Status.OK -> MiuixTheme.colorScheme.primary
                                SourceHealthCheck.Status.DEAD -> MiuixTheme.colorScheme.error
                                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

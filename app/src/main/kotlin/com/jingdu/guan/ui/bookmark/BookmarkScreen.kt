package com.jingdu.guan.ui.bookmark

import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingdu.guan.data.db.entity.BookmarkEntity
import com.jingdu.guan.ui.components.AppAlertDialog
import com.jingdu.guan.ui.components.AppIconButton
import com.jingdu.guan.ui.components.AppScaffold
import com.jingdu.guan.ui.components.AppToolbar
import com.jingdu.guan.ui.components.BackButton
import com.jingdu.guan.ui.components.BookCover
import com.jingdu.guan.ui.components.CompactTextField
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.EmptyState
import com.jingdu.guan.ui.components.ToolbarTitle
import com.jingdu.guan.ui.components.groupedCardItem
import com.jingdu.guan.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发现页：书签集合。
 *
 * 定位是「常去的站，随手点」——比订阅源轻，比浏览器书签近。
 * 点一行直接进应用内网页（那里有嗅探悬浮球，视频能直接抓出来播）。
 */
@Composable
fun BookmarkScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: BookmarkViewModel,
) {
    val list by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    AppScaffold(
        topBar = {
            AppToolbar(
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    AppIconButton(onClick = {
                        input = ""
                        showAdd = true
                    }) {
                        Icon(MiuixIcons.Add, contentDescription = "添加书签")
                    }
                },
            ) {
                ToolbarTitle("发现")
            }
        },
    ) { padding ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = MiuixIcons.SearchDevice,
                    title = "还没有书签",
                    hint = "点右上角 ＋ 把常去的站存进来",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = padding.calculateBottomPadding() + Dimens.gapS,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
            ) {
                items(list, key = { it.url }) { item ->
                    BookmarkRow(
                        item = item,
                        onClick = { onOpenUrl(item.url) },
                        onDelete = { viewModel.remove(item.url) },
                        modifier = Modifier.groupedCardItem(list.indexOf(item), list.size),
                    )
                }
            }
        }
    }

    if (showAdd) {
        AppAlertDialog(
            title = "添加书签",
            text = "填网址，不用带 https://",
            confirmText = "添加",
            confirmEnabled = input.isNotBlank(),
            onConfirm = {
                viewModel.add(context, input)
                showAdd = false
            },
            onDismissRequest = { showAdd = false },
            content = {
                CompactTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "example.com",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    message?.let {
        // 简单提示，两秒后自己散
        androidx.compose.runtime.LaunchedEffect(it) {
            kotlinx.coroutines.delay(1500)
            viewModel.clearMessage()
        }
    }
}

@Composable
private fun BookmarkRow(
    item: BookmarkEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.gapM, vertical = Dimens.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 站点图标；拿不到就用首字母占位（BookCover 自动处理）
        Box(
            Modifier
                .size(Dimens.iconXL)
                .clip(AppShapes.small)
                .background(MiuixTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (item.icon.isNotBlank()) {
                BookCover(
                    title = item.title,
                    coverModel = item.icon,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = item.title.take(1).uppercase(),
                    style = MiuixTheme.textStyles.headline2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.size(Dimens.gapM))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { item.url },
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dimens.gapXS / 2))
            Text(
                text = runCatching { java.net.URI(item.url).host ?: item.url }.getOrDefault(item.url),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

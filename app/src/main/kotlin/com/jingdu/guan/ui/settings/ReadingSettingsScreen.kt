package com.jingdu.guan.ui.settings

import com.jingdu.guan.ui.components.horizontalOnly
import com.jingdu.guan.ui.components.pageScroll
import com.jingdu.guan.ui.components.SectionHeader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.jingdu.guan.data.prefs.AppPrefs
import com.jingdu.guan.ui.components.AppTopBar
import com.jingdu.guan.ui.components.Dimens
import com.jingdu.guan.ui.components.SettingGroup
import com.jingdu.guan.ui.components.SettingGroupPosition
import com.jingdu.guan.ui.components.SettingRow
import com.jingdu.guan.ui.components.SwitchRow
import com.jingdu.guan.ui.components.settingsPageColor
import com.jingdu.guan.ui.components.rememberAppTopBarScroll
import com.jingdu.guan.ui.components.topBarScroll
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.jingdu.guan.ui.components.AppScaffold

@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenReplaceRules: () -> Unit,
) {
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    // 读预热好的那份，第一帧就是真值：从前关过的开关每次进页都要从「开」滑到「关」（见 AppPrefs 的「设置页读的预热版」）
    val checkAuthor = appPrefs.changeSourceCheckAuthorState.collectAsStateWithLifecycle().value ?: true
    val autoChangeSource = appPrefs.autoChangeSourceState.collectAsStateWithLifecycle().value ?: true
    val searchHistoryEnabled = appPrefs.searchHistoryEnabledState.collectAsStateWithLifecycle().value ?: true

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("来源与净化", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(top = padding.calculateTopPadding() + Dimens.gapS, bottom = padding.calculateBottomPadding()),
        ) {
            // 从前第一组叫「规则」（书源管理 + 净化替换规则），页面叫「阅读与规则」：「规则」是书源格式里的词，
            // 用户不认它；排版这些「阅读」设置又都在阅读页的面板里，标题里有「阅读」反倒让人进来找字号。
            // 换源本来就是书源的事，和书源管理合成一组；净化单独一组
            SectionHeader("来源")
            SettingGroup {
                SettingRow(
                    title = "来源管理",
                    onClick = onOpenSources,
                    position = SettingGroupPosition.First,
                )
                SwitchRow(
                    title = "自动换源",
                    checked = autoChangeSource,
                    onCheckedChange = { on -> scope.launch { appPrefs.setAutoChangeSource(on) } },
                    position = SettingGroupPosition.Middle,
                )
                SwitchRow(
                    title = "换源时匹配作者",
                    checked = checkAuthor,
                    onCheckedChange = { on -> scope.launch { appPrefs.setChangeSourceCheckAuthor(on) } },
                    position = SettingGroupPosition.Last,
                )
            }
            SectionHeader("正文")
            SettingGroup {
                SettingRow(
                    title = "净化替换",
                    onClick = onOpenReplaceRules,
                    position = SettingGroupPosition.Alone,
                )
            }
            SectionHeader("搜索")
            SettingGroup {
                // 关掉会顺带清空已有历史（见 AppPrefs.setSearchHistoryEnabled），副标题得把这个后果说在前面
                SwitchRow(
                    title = "记录搜索历史",
                    subtitle = if (searchHistoryEnabled) "搜索页显示最近搜过的词，点一下直接重搜。关闭会清空已有记录"
                    else "已关闭，搜过的词不会留在这台设备上",
                    checked = searchHistoryEnabled,
                    onCheckedChange = { on -> scope.launch { appPrefs.setSearchHistoryEnabled(on) } },
                    position = SettingGroupPosition.Alone,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}

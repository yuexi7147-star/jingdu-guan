package com.jingdu.guan.di

import androidx.room3.Room
import com.jingdu.guan.core.model.BookParserRegistry
import com.jingdu.guan.core.parser.epub.EpubParser
import com.jingdu.guan.core.parser.mobi.MobiParser
import com.jingdu.guan.core.parser.txt.TxtParser
import com.jingdu.guan.core.source.BookSourceEngine
import com.jingdu.guan.core.source.SourceHttpClient
import com.jingdu.guan.data.db.InkwellDb
import com.jingdu.guan.data.prefs.ReaderPrefs
import com.jingdu.guan.data.prefs.WebDavPrefs
import com.jingdu.guan.data.repo.BookRepository
import com.jingdu.guan.data.repo.BookSourceRepository
import com.jingdu.guan.data.repo.ChapterContentCache
import com.jingdu.guan.data.repo.AutoSourceSwitcher
import com.jingdu.guan.data.repo.SourceReadHealth
import com.jingdu.guan.data.repo.NetBookRepository
import com.jingdu.guan.data.repo.ReplaceRuleRepository
import com.jingdu.guan.data.repo.WebDavRepository
import com.jingdu.guan.ui.bookshelf.BookshelfViewModel
import com.jingdu.guan.ui.reader.ReaderViewModel
import com.jingdu.guan.ui.search.SearchViewModel
import com.jingdu.guan.ui.sourcedetail.SourceDetailViewModel
import com.jingdu.guan.ui.sourcemanage.SourceManageViewModel
import com.jingdu.guan.ui.webdav.WebDavViewModel
import com.jingdu.guan.util.KeyEventBus
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), InkwellDb::class.java, "inkwell.db")
            .addMigrations(*InkwellDb.ALL_MIGRATIONS)
            // 用户侧载旧版 APK（库版本比代码新）时，Room 默认抛异常 → 每次启动崩溃死循环。
            // 仅**降级**时销毁重建（升级仍走上面的迁移，不丢数据）。
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }
    single { com.jingdu.guan.data.repo.SourceQuarantine(androidContext()) }
    single { com.jingdu.guan.data.repo.SuspiciousCounter(androidContext()) }
    single { com.jingdu.guan.data.repo.SourceLightCheck(get()) }
    single { com.jingdu.guan.data.repo.SourceAutoCheck(androidContext(), get(), get(), get(), get(), get()) }
    single { com.jingdu.guan.data.repo.SourceAuditService(get(), get()) }
    single { get<InkwellDb>().bookDao() }
    single { get<InkwellDb>().chapterDao() }
    single { get<InkwellDb>().bookSourceDao() }
    single { get<InkwellDb>().bookSourceHitDao() }
    single { get<InkwellDb>().replaceRuleDao() }
    single { get<InkwellDb>().rssSourceDao() }
    single { get<InkwellDb>().readingStatDao() }
    single { get<InkwellDb>().rssArticleDao() }
    single { com.jingdu.guan.data.repo.ReadingStatsRepository(get(), get(), get()) }

    // EPUB/MOBI 的 sniff 靠魔数，txt 兜底放最后
    // txt 的解码结果与拆章表缓存在系统可清理的缓存目录：没了只是下次打开慢一次（见 TxtParser / TxtCache）
    single {
        BookParserRegistry(
            listOf(EpubParser(), MobiParser(), TxtParser(cacheDir = java.io.File(androidContext().cacheDir, "txt"))),
        )
    }

    single { com.jingdu.guan.data.repo.ReaderFontStore(androidContext()) }
    // 字体库按需再取：ReaderPrefs 在 Application.onCreate 就被预热，别让它把扫字体目录也拽到启动路径上
    single { ReaderPrefs(androidContext()) { id -> get<com.jingdu.guan.data.repo.ReaderFontStore>().isAvailable(id) } }
    single { WebDavPrefs(androidContext()) }
    single { com.jingdu.guan.data.prefs.AppPrefs(androidContext()) }
    single { com.jingdu.guan.util.RunLogRecorder(androidContext()) }
    single { KeyEventBus() }
    single { ChapterContentCache(java.io.File(androidContext().filesDir, "cache")) }

    // 抓取日志接到 logcat：成功 debug、失败/重试 warn。开发者模式的运行日志录的就是 logcat
    single {
        SourceHttpClient(
            logger = { warn, msg, e ->
                if (warn) android.util.Log.w("SourceHttp", msg, e) else android.util.Log.d("SourceHttp", msg)
            },
        )
    }
    // Rhino 解释执行 JS 规则（与 Legado 同引擎）；脚本里的繁简转换用系统 ICU，java.androidId() 给本机随机的设备号
    single<com.jingdu.guan.core.source.js.ScriptRuntime> {
        val deviceId = com.jingdu.guan.data.source.ScriptDeviceId(androidContext())
        com.jingdu.guan.core.source.js.RhinoScriptRuntime(
            chineseConverter = com.jingdu.guan.data.source.IcuChineseConverter(),
            deviceId = deviceId::get,
        )
    }
    // JS 渲染回退：静态 HTML 解析不出内容时用 WebView 执行页面 JS 再试（详情/目录/正文三级）
    single<com.jingdu.guan.core.source.PageRenderer> {
        com.jingdu.guan.data.source.WebViewPageRenderer(androidContext())
    }
    single {
        val replaceRules = get<ReplaceRuleRepository>()
        BookSourceEngine(
            http = get(),
            scriptRuntime = get(),
            renderer = get(),
            globalPurify = { source, bookTitle -> replaceRules.purifyFor(source, bookTitle) },
        )
    }
    single { com.jingdu.guan.core.source.rss.RssEngine(http = get(), scriptRuntime = get(), renderer = get()) }
    // 新建书源的页面识别：与引擎共用抓取客户端（同一套 UA、Cookie、私网防护）、WebView 渲染回退、
    // 脚本运行时与全局净化 —— 「高级」页的试抓要和加书后的正式引擎一个样
    single {
        val replaceRules = get<ReplaceRuleRepository>()
        com.jingdu.guan.core.source.detect.SiteAnalyzer(
            http = get(),
            renderer = get(),
            scriptRuntime = get(),
            globalPurify = { source, bookTitle -> replaceRules.purifyFor(source, bookTitle) },
        )
    }
    single { com.jingdu.guan.data.repo.RssRepository(get(), get()) }
    single { com.jingdu.guan.data.repo.RssFeedRepository(get(), get(), get()) }
    // 更新用的通用 HTTP 客户端（检查 + 下载安装包 + 反馈上报）
    single {
        okhttp3.OkHttpClient.Builder()
            // OkHttp 默认这三项各 10 秒，显式写出来是为了把「没配」和「配成 10 秒」区分开。
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .writeTimeout(java.time.Duration.ofSeconds(15))
            // **刻意不设 callTimeout**：这个客户端要下载几十 MB 的安装包，
            // 慢网下整次调用超过任何合理总闸都是正常的。逐段的 readTimeout 已经能识别真卡死。
            .build()
    }
    single { com.jingdu.guan.update.UpdateChecker(get()) }
    single { com.jingdu.guan.update.ServerUpdateChecker(get()) }
    single { com.jingdu.guan.data.repo.FeedbackRepository(get()) }
    single {
        com.jingdu.guan.update.UpdateInstaller(
            get(),
            com.jingdu.guan.update.InstalledAppAuthenticity(androidContext()),
        )
    }
    single { com.jingdu.guan.update.UpdateManager(get(), get(), get()) }
    // 启动时查更新、书架齿轮与设置页的红点读它（见 UpdateNotifier）
    single { com.jingdu.guan.update.UpdateNotifier(androidContext(), get(), get()) }

    single { BookRepository(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    single { SourceReadHealth(get<InkwellDb>().sourceReadHealthDao()) }
    single { BookSourceRepository(get(), get(), get(), get()) }
    single { ReplaceRuleRepository(get()) }
    single { com.jingdu.guan.data.repo.LocalFolderScanner(androidContext()) }
    single { com.jingdu.guan.data.repo.LocalFolderLibrary(androidContext(), get(), get()) }
    single { NetBookRepository(get(), get(), get(), get(), get(), get()) }
    single { AutoSourceSwitcher(get(), get()) }
    single { com.jingdu.guan.util.LocalNetworkAccess(androidContext()) }
    single { WebDavRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { com.jingdu.guan.data.net.OfficialWebDavClient(get()) }
    single { com.jingdu.guan.data.repo.OfficialWebDavRepository(get(), get()) }

    viewModel { BookshelfViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (bookId: String) ->
        ReaderViewModel(bookId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    viewModel { SearchViewModel(get(), get(), get(), get()) }
    viewModel { (results: List<com.jingdu.guan.core.source.SearchResult>) ->
        com.jingdu.guan.ui.preview.BookPreviewViewModel(results, get(), get(), get())
    }
    viewModel { com.jingdu.guan.ui.explore.ExploreViewModel(get(), get(), get(), get()) }
    viewModel { (bookId: String) -> com.jingdu.guan.ui.detail.BookDetailViewModel(bookId, get(), get(), get(), get(), get()) }
    // 校验挂在应用级单例上，离开书源管理页也照跑（见 SourceChecker）
    single { com.jingdu.guan.ui.sourcemanage.SourceChecker(get(), get(), get()) }
    // 整本缓存同理：离开详情页照常跑
    single { com.jingdu.guan.data.repo.OfflineDownloader(get(), get(), get(), get(), get()) }
    viewModel { SourceManageViewModel(androidContext(), get(), get(), get(), get()) }
    viewModel { (sourceId: String) -> SourceDetailViewModel(sourceId, get(), get(), get()) }
    viewModel { com.jingdu.guan.ui.sourcewizard.SourceWizardViewModel(get(), get(), get(), get()) }
    viewModel { WebDavViewModel(get(), get(), get(), get()) }
    viewModel { com.jingdu.guan.ui.feedback.FeedbackViewModel(androidContext(), get(), get(), get()) }
    viewModel { com.jingdu.guan.ui.settings.DeveloperViewModel(get(), get()) }
    viewModel { com.jingdu.guan.ui.stats.ReadingStatsViewModel(get(), get()) }
    viewModel { com.jingdu.guan.ui.replace.ReplaceRuleViewModel(androidContext(), get(), get(), get()) }
    viewModel { com.jingdu.guan.ui.rss.RssSourceViewModel(androidContext(), get(), get()) }
    // 空串 = 看「全部」（Koin 的参数别传 null）
    viewModel { com.jingdu.guan.ui.player.PlayerViewModel() }
    viewModel { (sourceId: String) -> com.jingdu.guan.ui.rss.RssTimelineViewModel(sourceId.ifBlank { null }, get(), get(), get()) }
    // 后两个：文章的原网页才有的地址、标题；单网页源传空串（参数按位置解构，两个入口都得传满三个）
    viewModel { (sourceId: String, url: String, title: String) ->
        com.jingdu.guan.ui.rss.RssWebViewModel(sourceId, get(), url.ifBlank { null }, title.ifBlank { null })
    }
    viewModel { (args: com.jingdu.guan.ui.rss.RssArticleArgs) ->
        com.jingdu.guan.ui.rss.RssArticleViewModel(args, get(), get())
    }
}

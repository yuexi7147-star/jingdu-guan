package com.jingdu.guan.ui.rss

import top.yukonga.miuix.kmp.icon.extended.Blocklist
import android.webkit.JsResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateListOf
import com.jingdu.guan.ui.theme.AppShapes
import com.jingdu.guan.ui.components.Dimens
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingdu.guan.data.source.WebViewNetworkGuard
import com.jingdu.guan.ui.components.AppIconButton
import com.jingdu.guan.ui.components.AppScaffold
import com.jingdu.guan.ui.components.AppSnackbarHost
import com.jingdu.guan.ui.components.AppToolbar
import com.jingdu.guan.ui.components.BackButton
import com.jingdu.guan.ui.components.CollectMessages
import com.jingdu.guan.ui.components.DeterminateProgressBar
import com.jingdu.guan.ui.components.ErrorState
import com.jingdu.guan.ui.components.LoadingState
import com.jingdu.guan.ui.components.MessageBus
import com.jingdu.guan.ui.components.NO_BROWSER_MESSAGE
import com.jingdu.guan.ui.components.ToolbarTitle
import com.jingdu.guan.ui.components.rememberExternalLinkOpener
import com.jingdu.guan.ui.components.settingsPageColor
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 应用内的网页页（单网页源、文章的原网页）：窄顶栏 + 网页铺满（阅读的 singleUrl 点开就是内置浏览器，照做）。
 *
 * - 顶栏是实色：WebView 不在 Compose 里画，录不进毛玻璃取样的图层（同来源向导的网页）。窄栏而不是大标题栏：
 *   WebView 不接嵌套滚动，大标题收不起来，白占一截。
 * - 标题是订阅源的名字（文章的原网页是文章标题）；右边刷新、用浏览器打开（先把完整地址亮出来问一句，同文章页的「浏览器打开原文」）。
 * - 系统返回先在网页里后退，退到头才离开；顶栏的 ‹ 直接回订阅列表。
 * - 加载进度是叠在网页顶上的一条线，不放进顶栏 —— 放进顶栏的话它一出一收，顶栏跟着变高变矮，整页网页上下跳。
 * - 主文档打不开（断网、域名失效）盖一层错误态 +「重试」，别留一张 WebView 自己的报错页。
 */

/** 在页面里找一个可播放的直链：video/src/source，以及常见的 m3u8 文本 */
private const val SNIFF_JS = """
(function(){
  function ok(u){ return !!u && /^https?:/i.test(u); }
  try {
    var v = document.querySelector('video');
    if (v) {
      if (ok(v.currentSrc)) return v.currentSrc;
      if (ok(v.src)) return v.src;
      var s = v.querySelectorAll('source');
      for (var i = 0; i < s.length; i++) { if (ok(s[i].src)) return s[i].src; }
    }
    var all = document.querySelectorAll('source');
    for (var j = 0; j < all.length; j++) {
      var src = all[j].src || '';
      if (ok(src) && /\.(mp4|m3u8|m4a|mp3|webm)/i.test(src)) return src;
    }
    // 页面本身是 JSON（很多源的链接直接指向 API）：从文本里找媒体地址
    // textContent 而不是 innerText：Chrome 的 JSON 查看器把内容放在 shadow DOM 里，
    // innerText 有时读不到；textContent 能拿到整段原文
    var body = (document.body && (document.body.textContent || document.body.innerText)) || '';
    if (body.length > 20) {
      // JSON 里的地址是 https:\/\/xxx 这样转义的，先把转义斜杠还原
      var clean = body.replace(/\\\//g, '/');
      var m = clean.match(/https?:[^"'\s]+?\.(m3u8|mp4|m4a|mp3|webm)([^"'\s]*)?/i);
      if (m && ok(m[0])) return m[0];
    }
    return '';
  } catch (e) { return ''; }
})()
"""


/** 媒体直链：m3u8/mp4 等。分片（.ts）不算 —— 那是流里的一段，不是整片地址 */
private fun isMediaUrl(url: String): Boolean {
    val u = url.lowercase()
    if (u.startsWith("blob:") || u.startsWith("data:") || !u.startsWith("http")) return false
    return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".m4a") ||
        u.contains(".mp3") || u.contains(".webm") || u.contains(".flv")
}


/** 把隐藏规则转成一段可执行的 JS：创建/更新一个 <style>，避免每次重复注入 */
private val AD_CSS_JS: String by lazy {
    val css = com.jingdu.guan.data.source.AdBlockRules.HIDE_CSS
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    "(function(){var s=document.getElementById('__jg_ad');" +
        "if(!s){s=document.createElement('style');s.id='__jg_ad';" +
        "(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=\"$css\";})()"
}


/** 地址显示成「域名/文件名」：原始 URL 后面挂一长串 token，直接显示什么都看不出来 */
private fun shortUrl(url: String): String {
    return try {
        val u = java.net.URI(url)
        val host = u.host ?: ""
        val name = u.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: ""
        if (name.length > 18) host + "/…" + name.takeLast(12) else host + "/" + name
    } catch (e: Exception) {
        url.take(36)
    }
}

@Composable
fun RssWebScreen(
    onBack: () -> Unit,
    viewModel: RssWebViewModel,
    /** 网页里嗅到视频直链时交给播放页；不传就只提示 */
    onPlay: (url: String, referer: String?, title: String) -> Unit = { _, _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)
    val links = rememberExternalLinkOpener { messages.emit(NO_BROWSER_MESSAGE) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    // 页面里嗅到的视频直链；非空时顶部出一条「播放」提示
    val sniffed = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var purifyMode by remember { mutableStateOf(false) }
    // 0..100；100 = 这一页加载完了，进度线收起
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 当前在看的那一页（点进去之后不再是订阅源地址）：「用浏览器打开」打开的是它
    var currentUrl by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    fun reload() {
        pageError = null
        webView?.reload()
    }

    val pageColor = settingsPageColor()
    AppScaffold(
        blurBars = false,
        containerColor = pageColor,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            AppToolbar(
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor,
                actions = {
                    val url = currentUrl ?: state.url
                    if (url != null) {
                        // 一键净化：点了立刻藏广告、关弹窗、拦跨域跳转；再点恢复
                        // 净化：Blocklist 图标，开着时变成强调色 —— 和旁边的刷新图标形状差得远，不会认错
                        AppIconButton(onClick = {
                            purifyMode = !purifyMode
                            if (purifyMode) {
                                webView?.evaluateJavascript(com.jingdu.guan.data.source.AdBlockRules.HIDE_JS, null)
                                webView?.evaluateJavascript(com.jingdu.guan.data.source.AdBlockRules.DISMISS_JS, null)
                                webView?.evaluateJavascript(AD_CSS_JS, null)
                            }
                        }) {
                            Icon(
                                MiuixIcons.Blocklist,
                                contentDescription = if (purifyMode) "已净化，点击恢复" else "一键净化",
                                tint = if (purifyMode) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        AppIconButton(onClick = ::reload) {
                            Icon(MiuixIcons.Refresh, contentDescription = "刷新")
                        }
                        AppIconButton(onClick = { links.open(url) }) {
                            Icon(MiuixIcons.Forward, contentDescription = "用浏览器打开")
                        }
                    }
                },
            ) {
                ToolbarTitle(state.title)
            }
        },
    ) { padding ->
        // 不滚动的整页：整块让开系统栏；网页里有输入框，键盘弹起时把网页一起顶上去
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val url = state.url
            val error = state.error
            when {
                error != null -> ErrorState(message = error, onRetry = viewModel::load)
                url == null -> LoadingState()
                else -> {
                    SourceWebView(
                        url = url,
                        headers = state.headers,
                        enableJs = state.enableJs,
                        onCreated = { webView = it },
                        onReleased = { webView = null },
                        onProgress = { progress = it },
                        onPageStarted = {
                            currentUrl = it
                            pageError = null
                        },
                        onHistoryChanged = { canGoBack = it },
                        onMainFrameError = { pageError = it },
                        onSniff = { u -> if (u !in sniffed && sniffed.size < 20) sniffed.add(u) },
                        purifyMode = purifyMode,
                        onExternalLink = links::open,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 悬浮球：拇指够得到的手势入口，不用去够顶栏
                    com.jingdu.guan.ui.components.FloatingBall(
                        count = sniffed.size,
                        onTap = {
                            webView?.evaluateJavascript(SNIFF_JS) { raw ->
                                val v = raw?.trim()?.trim('"')?.replace("\\/", "/")
                                if (!v.isNullOrBlank() && v != "null" && v.startsWith("http") && v !in sniffed) {
                                    sniffed.add(v)
                                }
                            }
                        },
                        onLongPress = { if (sniffed.isNotEmpty()) { onPlay(sniffed.first(), currentUrl, state.title); sniffed.clear() } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = Dimens.gapL, bottom = Dimens.gapXXL),
                    )

                    if (sniffed.isNotEmpty()) {
                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = Dimens.touchTarget)
                                .background(MiuixTheme.colorScheme.primary, AppShapes.medium)
                                .padding(Dimens.gapS),
                        ) {
                            Text(
                                "找到 " + sniffed.size + " 个地址",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onPrimary,
                            )
                            sniffed.take(6).forEachIndexed { i, u ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = Dimens.gapXS),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = (i + 1).toString() + ". " + shortUrl(u),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onPrimary,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onPlay(u, currentUrl, state.title); sniffed.clear() },
                                    )
                                    Text(
                                        "外部",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .clickable {
                                                com.jingdu.guan.data.source.MediaOpenExternal
                                                    .open(context, u, "用其他应用打开")
                                            }
                                            .padding(horizontal = Dimens.gapS),
                                    Text(
                                        "下载",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .clickable {
                                                com.jingdu.guan.data.source.MediaDownloader
                                                    .enqueue(context, u, currentUrl, state.title)
                                            }
                                            .padding(horizontal = Dimens.gapS),
                                    )
                                }
                            }
                        }
                    }

                    if (progress in 0 until 100) {
                        DeterminateProgressBar(
                            progress = progress / 100f,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    pageError?.let { message ->
                        ErrorState(
                            message = message,
                            // 盖住 WebView 自己那张报错页
                            modifier = Modifier.background(pageColor),
                            onRetry = ::reload,
                            secondaryLabel = "用浏览器打开",
                            onSecondary = { links.open(currentUrl ?: url) },
                        )
                    }
                }
            }
        }
    }

    // 切到后台、盖上别的页：网页里的视频、计时器停下来（WebView 不会自己停）
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * 网页本身。和来源向导的 WizardWebView 是两回事（那个要截表单、点选元素），只有两条一致：
 * 页面不许碰本机文件和内网（[WebViewNetworkGuard]，同一道拦截）。
 *
 * - 请求头照订阅源里写的：User-Agent 设给 WebView（之后每个请求都带），其余随第一次请求带上（WebView 只给 loadUrl 这一下带头）。
 *   没写 UA 就用 WebView 自己的（手机版 Chrome）—— 给人看的网页，站点按它出手机版。
 * - `weixin://`、`intent://` 这类拉起别的应用的链接、下载，交给 [onExternalLink]（先问一句再跳）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SourceWebView(
    url: String,
    headers: Map<String, String>,
    enableJs: Boolean,
    onCreated: (WebView) -> Unit,
    onReleased: () -> Unit,
    onProgress: (Int) -> Unit,
    onPageStarted: (String) -> Unit,
    onHistoryChanged: (canGoBack: Boolean) -> Unit,
    onMainFrameError: (String) -> Unit,
    onSniff: (String) -> Unit = {},
    purifyMode: Boolean = false,
    onExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by rememberUpdatedState(onProgress)
    val started by rememberUpdatedState(onPageStarted)
    val history by rememberUpdatedState(onHistoryChanged)
    val mainError by rememberUpdatedState(onMainFrameError)
    val external by rememberUpdatedState(onExternalLink)
    val released by rememberUpdatedState(onReleased)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = enableJs
                settings.domStorageEnabled = true
                // 第三方页面不该摸到本机文件和其它应用的 content://（allowContentAccess 默认是开的）
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 电脑版的页面按整页宽度缩进屏幕，能双指放大；手机版的页面不受影响
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                if (!ua.isNullOrBlank()) settings.userAgentString = ua
                // 不给页面开新窗口：弹窗广告全靠这个
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false

                webChromeClient = object : WebChromeClient() {
                    // alert / confirm 自动点掉：它们只挡内容，信息量通常是「请下载 App」
                    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                        result.confirm(); return true
                    }
                    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                        result.confirm(); return true
                    }
                    // window.open 直接吞掉
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false

                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        progress(newProgress)
                    }

                }
                webViewClient = object : WebViewClient() {
                    private val guard = WebViewNetworkGuard()

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // 媒体请求是最可靠的线索：页面怎么播都要把地址发出去，比翻 <video> 标签管用
                        val u = request.url?.toString().orEmpty()
                        if (isMediaUrl(u)) onSniff(u)
                        // 广告/统计域名直接掐断，连下载都省了
                        if (com.jingdu.guan.data.source.AdBlockRules.blockedHost(request.url?.host)
                            || com.jingdu.guan.data.source.AdBlockRules.blockedPath(u)
                        ) {
                            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                        return guard.intercept(request)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (guard.blocksNavigation(request)) return true
                        // 净化开着才拦：且只拦「没手指参与 + 跨域名」的跳转
                        // 同域跳转放行 —— 很多站是 SPA，点链接走 JS 路由不一定带手势标记
                        if (purifyMode) {
                            val curHost = view.url?.let { android.net.Uri.parse(it).host }
                            val toHost = request.url.host
                            if (!request.hasGesture() && toHost != null && toHost != curHost) return true
                        }
                        return when (request.url.scheme?.lowercase()) {
                            "http", "https" -> false
                            // 不出网、留在页面里的那几种照常
                            "about", "data", "blob", "javascript" -> false
                            else -> {
                                // 拉起别的应用：只认人点出来的那一下，页面脚本自己跳的不理（广告页常这么干）
                                if (request.hasGesture()) external(request.url.toString())
                                true
                            }
                        }
                    }

                    override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                        started(startedUrl)
                    }


                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        // 视频地址多半是页面脚本跑完才挂上去的，隔一会儿再找两遍
                        // 只有净化开着才清理 —— 默认不动网页，避免误伤
                        if (purifyMode) {
                            view.evaluateJavascript(AD_CSS_JS, null)
                            listOf(800L, 2500L, 6000L).forEach { d ->
                                view.postDelayed({
                                    if (purifyMode) {
                                        view.evaluateJavascript(com.jingdu.guan.data.source.AdBlockRules.HIDE_JS, null)
                                        view.evaluateJavascript(com.jingdu.guan.data.source.AdBlockRules.DISMISS_JS, null)
                                    }
                                }, d)
                            }
                        }

                        listOf(800L, 2500L, 6000L).forEach { delayMs ->
                            view.postDelayed({
                                view.evaluateJavascript(SNIFF_JS) { raw ->
                                    val v = raw?.trim()?.trim('"')?.replace("\\/", "/")
                                    if (!v.isNullOrBlank() && v != "null" && v.startsWith("http")) {
                                        onSniff(v)
                                    }
                                }
                            }, delayMs)
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView, historyUrl: String?, isReload: Boolean) {
                        history(view.canGoBack())
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        // 只管主文档：页面里一张图、一个广告脚本加载失败不算「打不开」
                        if (request.isForMainFrame) mainError(error.description?.toString().orEmpty().ifBlank { "网页打不开" })
                    }
                }
                setDownloadListener { downloadUrl, _, _, _, _ -> external(downloadUrl) }
                onCreated(this)
                loadUrl(url, headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
            }
        },
        onRelease = {
            released()
            it.stopLoading()
            it.destroy()
        },
    )
}

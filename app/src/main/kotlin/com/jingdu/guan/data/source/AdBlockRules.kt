package com.jingdu.guan.data.source

/**
 * 网页广告清理。
 *
 * 三层，从便宜到贵：
 * 1. 域名：请求直接掐断，连下载都省了（[blockedHost]）
 * 2. 元素：注入 CSS 隐藏（[HIDE_CSS]）—— 一次注入，后续动态插入的也吃得到
 * 3. 浮层：定时扫，干掉固定定位的遮罩与弹窗（[HIDE_JS]）
 *
 * 只处理"一看就是广告"的：域名黑名单、类的关键词、固定定位的遮罩。
 * 不做启发式content识别 —— 那会误伤正文，用户看到的是「内容没了」，比广告更糟。
 */
object AdBlockRules {

    /** 广告与统计域名。只列常见的，多了维护不起，也容易误伤 CDN */
    private val BLOCKED_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "googletagmanager.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "hm.baidu.com", "pos.baidu.com", "cpro.baidu.com", "union.baidu.com",
        "tanx.com", "alimama.com", "mmstat.com", "cnzz.com", "51.la",
        "umeng.com", "growingio.com", "sensorsdata.cn", "clarity.ms",
        "popads.net", "propellerads.com", "adsterra.com", "juicyads.com",
        "exoclick.com", "trafficjunky.com", "tsyndicate.com",
    )

    /** 路径里出现这些片段也算广告 */
    private val BLOCKED_PATH = listOf(
        "/ads/", "/ad/", "/advert", "/banner/", "/popunder", "/popup.js",
        "/analytics.js", "/gtag/js", "/track.php", "/pixel.php",
    )

    fun blockedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return BLOCKED_HOSTS.any { h == it || h.endsWith("." + it) }
    }

    fun blockedPath(url: String): Boolean {
        val u = url.lowercase()
        return BLOCKED_PATH.any { u.contains(it) }
    }

    /**
     * 隐藏规则。
     *
     * 用属性选择器匹配 class/id 里的关键词 —— 各家广告位的类名不同，但基本都带 ad/banner/popup 这类词。
     * [class*=] 是子串匹配，比逐个列举类名覆盖广得多。
     */
    val HIDE_CSS: String = """
        [class*="ad-"], [class*="-ad"], [class*="ads"], [class*="advert"],
        [id*="ad-"], [id*="-ad"], [id*="ads"], [id*="advert"],
        [class*="banner"], [id*="banner"],
        [class*="popup"], [class*="pop-under"], [class*="popunder"],
        [class*="float-ad"], [class*="fixed-ad"], [class*="sticky-ad"],
        [class*="download-app"], [class*="openApp"], [class*="open-app"],
        [class*="guide-mask"], [class*="mask-layer"], [class*="overlay-ad"],
        iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
        iframe[src*="cpro.baidu"], iframe[src*="tanx"],
        a[href*="app."][class*="btn"], div[class*="qrcode"]
        { display: none !important; visibility: hidden !important; height: 0 !important; }
    """.trimIndent()

    /**
     * 浮层清理：定时扫，处理 CSS 抓不到的（脚本动态插入、内联样式写死的）。
     *
     * 判据收紧到「固定/绝对定位 + 覆盖面积大 + 不在正常文档流里」，
     * 只认遮罩与弹窗，不动正文容器 —— 误删正文比留着广告糟得多。
     */
    val HIDE_JS: String = """
        (function(){
          try {
            var killed = 0;
            var els = document.querySelectorAll('div,section,aside,ins');
            for (var i = 0; i < els.length && killed < 40; i++) {
              var e = els[i];
              var s = getComputedStyle(e);
              if (s.position !== 'fixed' && s.position !== 'absolute') continue;
              var r = e.getBoundingClientRect();
              var area = r.width * r.height;
              var screen = window.innerWidth * window.innerHeight;
              // 盖住大半屏、且不是 video 容器
              if (area > screen * 0.45 && !e.querySelector('video')) {
                var txt = (e.textContent || '').slice(0, 40);
                // 只清「像广告/引导」的：有下载、打开App、继续观看这类词，或整块是空遮罩
                if (/下载|打开|继续|APP|客户端|扫码|关注/.test(txt) || txt.trim() === '') {
                  e.style.setProperty('display','none','important');
                  killed++;
                }
              }
            }
            return String(killed);
          } catch (err) { return '0'; }
        })()
    """.trimIndent()
}

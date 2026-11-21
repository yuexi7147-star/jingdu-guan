package com.jingdu.guan.data.source

/**
 * 从一个网址找到它的 RSS/Atom feed。
 *
 * 用户手上多半是「网站首页」，不是 feed 地址。三步走，从最准到最粗：
 * 1. 页面里声明的 link rel=alternate type=application/rss+xml —— 站方给的，最可靠
 * 2. 常见路径（/feed、/rss、/rss.xml…）—— 覆盖大部分博客和 CMS
 * 3. 都没有就返回 null，让上层说清楚「这个站没提供订阅」
 *
 * 只认「解析得出条目」的结果：有的站对任意路径都返回 200 + HTML，光看状态码会误判。
 */
object FeedDiscover {

    private val LINK_TAG = Regex(
        "<link[^>]+type=[\"']application/(?:rss|atom)\\+xml[\"'] [^>]*>".replace("[\"'] [^>]", "[\"'] [^>]"),
        RegexOption.IGNORE_CASE,
    )
    private val HREF = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 常见 feed 路径，按命中概率排 */
    private val COMMON_PATHS = listOf(
        "/feed", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml",
        "/feeds/posts/default", "/?feed=rss2", "/feed/",
    )

    suspend fun discover(
        pageUrl: String,
        fetch: suspend (String) -> String,
        isFeed: (String) -> Boolean,
    ): String? {
        val base = pageUrl.trim()

        // 1) 先看这地址本身是不是 feed
        runCatching { fetch(base) }.getOrNull()?.let { if (isFeed(it)) return base }

        // 2) 页面里声明的
        val html = runCatching { fetch(base) }.getOrNull()
        if (html != null) {
            LINK_TAG.findAll(html).forEach { tag ->
                HREF.find(tag.value)?.groupValues?.getOrNull(1)?.let { href ->
                    val abs = absolutize(base, href)
                    if (abs != null) {
                        runCatching { fetch(abs) }.getOrNull()?.let { if (isFeed(it)) return abs }
                    }
                }
            }
        }

        // 3) 常见路径
        val root = origin(base)
        for (p in COMMON_PATHS) {
            val candidate = root + p
            runCatching { fetch(candidate) }.getOrNull()?.let { if (isFeed(it)) return candidate }
        }

        return null
    }

    private fun absolutize(base: String, href: String): String? {
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) return href
        return runCatching { java.net.URI(base).resolve(href).toString() }.getOrNull()
    }

    private fun origin(url: String): String {
        return runCatching {
            val u = java.net.URI(url)
            val port = if (u.port > 0) ":" + u.port else ""
            u.scheme + "://" + u.host + port
        }.getOrDefault(url.trimEnd('/'))
    }
}
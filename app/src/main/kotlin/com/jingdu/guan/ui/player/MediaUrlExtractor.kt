package com.jingdu.guan.ui.player

/**
 * 从 RSS 条目的正文 HTML 里找视频地址。
 *
 * 订阅源有两种给法：
 * 1. 标准 enclosure —— 但 RssArticleRow 没存，得回库读 description
 * 2. 正文里直接写 video / iframe —— 自制源常见
 *
 * 找到第一个能用的就返回；都找不到就给 null，让页面提示「这条没有视频」。
 */
object MediaUrlExtractor {

    private val VIDEO_TAG = Regex("<video[^>]*src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val SOURCE_TAG = Regex("<source[^>]*src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val IFRAME_TAG = Regex("<iframe[^>]*src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val DIRECT = Regex("(https?://[^\\s\"'<>]+\\.(?:mp4|m3u8|m4a|mp3|webm)(?:\\?[^\\s\"'<>]*)?)", RegexOption.IGNORE_CASE)

    /** 直链才返回；iframe 之类的是网页，得另开 WebView，不在这里处理 */
    fun extract(html: String?): String? {
        if (html.isNullOrBlank()) return null
        VIDEO_TAG.find(html)?.groupValues?.getOrNull(1)?.let { if (isDirect(it)) return it }
        SOURCE_TAG.find(html)?.groupValues?.getOrNull(1)?.let { if (isDirect(it)) return it }
        DIRECT.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** 页面地址（不是直链）—— 交给外部浏览器/WebView */
    fun extractPage(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return IFRAME_TAG.find(html)?.groupValues?.getOrNull(1)
    }

    private fun isDirect(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".m4a") ||
            u.contains(".mp3") || u.contains(".webm")
    }
}

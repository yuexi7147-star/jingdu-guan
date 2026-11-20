package com.jingdu.guan.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 源市场的列表抓取。
 *
 * 源仓库（yckceo）的列表页是服务端渲染的 HTML，一页 100 条，直接正则解析 —— 比逐个拉单源快得多。
 * 站点改版会让正则失效，所以解析失败要能明确报出来，而不是给一个空列表。
 */
class RssSourceMarket(private val http: OkHttpClient) {

    data class Entry(
        val id: String,
        val name: String,
        val url: String,
    )

    suspend fun fetchPage(page: Int = 1): Result<List<Entry>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (page <= 1) "https://www.yckceo.com/yuedu/rss/"
            else "https://www.yckceo.com/yuedu/rss/index/p/" + page
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()
            val html = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("列表页返回 HTTP " + resp.code)
                resp.body?.string() ?: error("列表页为空")
            }
            parse(html)
        }
    }

    /**
     * 从 HTML 里抠出 (id, 名称, 地址)。
     *
     * 结构是 <a href="/yuedu/rss/content/id/193">源仓库(官方纯净) http://yckceo.vip</a>，
     * 名称和地址挤在同一段文字里，中间可能是空格或多个空格。
     */
    private fun parse(html: String): List<Entry> {
        val re = Regex(
            "<a[^>]*href=\"/yuedu/rss/content/id/(\\d+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE,
        )
        val out = LinkedHashMap<String, Entry>()
        for (m in re.findAll(html)) {
            val id = m.groupValues[1]
            val raw = m.groupValues[2]
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (raw.isEmpty()) continue
            val urlIdx = raw.indexOfFirst { raw.startsWith("http", it) }
            val name = if (urlIdx > 0) raw.substring(0, urlIdx).trim() else raw
            val url = if (urlIdx > 0) raw.substring(urlIdx).trim().substringBefore(' ') else ""
            if (name.isEmpty()) continue
            out[id] = Entry(id, name, url)
        }
        return out.values.toList()
    }

    /** 抓单个源的完整 JSON（用于真正导入） */
    suspend fun fetchSourceJson(id: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.yckceo.com/yuedu/rss/json/id/" + id + ".json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("源详情返回 HTTP " + resp.code)
                resp.body?.string() ?: error("源详情为空")
            }
        }
    }
}

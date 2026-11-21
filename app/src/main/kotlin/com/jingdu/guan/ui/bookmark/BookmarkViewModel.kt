package com.jingdu.guan.ui.bookmark

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jingdu.guan.data.db.dao.BookmarkDao
import com.jingdu.guan.data.db.entity.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页：书签集合。
 *
 * 书签只做三件事：存网址、显示、点开。
 * 不做有效性检测（用户随手存一个站，过俩月关了他自己知道），
 * 也不做分组排序的复杂结构 —— 存几十个的时候按时间倒序就够用。
 */
class BookmarkViewModel(private val dao: BookmarkDao) : ViewModel() {

    val items: StateFlow<List<BookmarkEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() { _message.value = null }

    /**
     * 加一个书签。
     *
     * 标题先按域名填，随后去抓真实标题 —— 抓失败就留着域名，不为一个标题卡住用户。
     */
    fun add(context: Context, rawUrl: String) {
        var url = rawUrl.trim()
        if (url.isBlank()) return
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://" + url
        }
        val fallback = runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)

        viewModelScope.launch {
            dao.upsert(
                BookmarkEntity(url = url, title = fallback, createdAt = System.currentTimeMillis())
            )
            _message.value = "已添加"

            // 抓真实标题与图标，失败就算了
            val meta = withContext(Dispatchers.IO) { runCatching { fetchMeta(url) }.getOrNull() }
            if (meta != null) {
                dao.updateMeta(url, meta.first, meta.second)
            }
        }
    }

    fun remove(url: String) {
        viewModelScope.launch {
            dao.delete(url)
            _message.value = "已删除"
        }
    }

    fun rename(url: String, title: String) {
        viewModelScope.launch { dao.updateMeta(url, title.take(60), "") }
    }

    /** 抓标题与 favicon；两个都拿不到就返回 null */
    private fun fetchMeta(url: String): Pair<String, String>? {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        }
        val html = try {
            conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
        } catch (e: Exception) {
            conn.disconnect()
            return null
        } finally {
            conn.disconnect()
        }

        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(60).orEmpty()
        val icon = Regex("<link[^>]+rel=[\"'](?:icon|shortcut icon)[\"'] [^>]*>", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { tag ->
                Regex("href=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.getOrNull(1)
            }?.let { href ->
                if (href.startsWith("http", true)) href
                else runCatching { java.net.URI(url).resolve(href).toString() }.getOrNull()
            }.orEmpty()

        if (title.isBlank() && icon.isBlank()) return null
        return title to icon
    }
}

package com.jingdu.guan.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI

/**
 * 批量导入前的三级筛选：结构 -> 可达 -> 内容。
 *
 * 越往后越慢，所以前一级不过就不做下一级 —— 本地结构校验几十毫秒能筛掉两三成，
 * 网络那一级才是大头。**「检测通过」不等于「以后一直能用」**：RSS 站点会改版、会关、会限流，
 * 所以导入之后仍需定期复查。
 */
class SourceHealthCheck(private val http: OkHttpClient) {

    /** 一个源的体检结果 */
    data class Result(
        val entry: RssSourceMarket.Entry,
        val status: Status,
        /** 面向用户的一句话，比如「域名解析失败」「已解析出 23 条」 */
        val detail: String,
        /** 内容类型：video / audio / unknown */
        val kind: String = "unknown",
        /** 源 JSON 原文，通过时才有 */
        val json: String? = null,
    )

    enum class Status {
        /** 可订阅 */
        OK,
        /** 确定无效 */
        DEAD,
        /** 疑似无效，可能临时故障 */
        SUSPECT,
        /** 不是视频/音频源 */
        NOT_MEDIA,
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun check(entry: RssSourceMarket.Entry, market: RssSourceMarket): Result =
        withContext(Dispatchers.IO) {
            // ---- 第二级：可达性（没网就别浪费时间，直接 Unknown 走怀疑） ----
            val host = runCatching { URI(entry.url.ifBlank { "https://" + entry.url }).host }.getOrNull()
            if (!host.isNullOrBlank()) {
                val resolved = runCatching { InetAddress.getByName(host) }.isSuccess
                if (!resolved) return@withContext Result(entry, Status.DEAD, "域名解析失败")
            }

            // ---- 取源 JSON（这也是结构校验的输入） ----
            val raw = market.fetchSourceJson(entry.id).getOrElse { e ->
                return@withContext Result(entry, Status.SUSPECT, "取源失败（" + (e.message ?: "").take(20) + "）")
            }

            // ---- 第一级：结构 ----
            val obj = parseSourceObject(raw)
                ?: return@withContext Result(entry, Status.DEAD, "源 JSON 结构不对")

            val url = obj["sourceUrl"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            if (url.isBlank()) return@withContext Result(entry, Status.DEAD, "源里没有地址")

            // ---- 第三级：能不能真出内容 ----
            val probe = probeFeed(obj)
            when (probe.items) {
                0 -> return@withContext Result(entry, Status.DEAD, "没有条目（" + probe.detail + "）", probe.kind, raw)
                -1 -> return@withContext Result(entry, Status.SUSPECT, "取内容失败（" + probe.detail + "）", probe.kind, raw)
            }
            if (probe.kind == "unknown") {
                return@withContext Result(entry, Status.NOT_MEDIA, "非视频/音频源（" + probe.items + " 条图文）", "unknown", raw)
            }
            Result(entry, Status.OK, "已解析出 " + probe.items + " 条", probe.kind, raw)
        }

    private fun parseSourceObject(raw: String): JsonObject? = runCatching {
        val el = jsonParser.parseToJsonElement(raw)
        when (el) {
            is JsonArray -> el.firstOrNull()?.jsonObject
            is JsonObject -> el
            else -> null
        }
    }.getOrNull()

    private data class Probe(val items: Int, val kind: String, val detail: String)

    /**
     * 拉一次 RSS，看条目数与媒体类型。
     * items = -1 表示拉取失败；kind = unknown 表示没有视频/音频 enclosure。
     */
    private fun probeFeed(obj: JsonObject): Probe {
        fun field(k: String) = obj[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val base = field("sourceUrl").orEmpty()
        val path = field("ruleArticles").orEmpty()
        if (path.isBlank()) return Probe(-1, "unknown", "没有文章列表规则")

        val feedUrl = if (path.startsWith("http")) path else base.trimEnd('/') + "/" + path.trimStart('/')
        val req = Request.Builder().url(feedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        val body = runCatching { http.newCall(req).execute() }.getOrNull()?.use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        } ?: return Probe(-1, "unknown", "请求失败")

        val itemCount = Regex("<item[ >]", RegexOption.IGNORE_CASE).findAll(body).count()
            .let { if (it > 0) it else Regex("<entry[ >]", RegexOption.IGNORE_CASE).findAll(body).count() }
        if (itemCount == 0) return Probe(0, "unknown", "不是有效的 RSS")

        // 媒体类型：看 enclosure 的 type
        val hasVideo = Regex("type=\"video/", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val hasAudio = Regex("type=\"audio/", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val kind = when {
            hasVideo -> "video"
            hasAudio -> "audio"
            else -> "unknown"
        }
        return Probe(itemCount, kind, "ok")
    }
}

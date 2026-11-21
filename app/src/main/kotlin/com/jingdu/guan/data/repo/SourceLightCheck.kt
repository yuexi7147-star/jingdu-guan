package com.jingdu.guan.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI

/**
 * 订阅源探活：只判断「这个站还活着吗」。
 *
 * 四档判定（照鲸读的 SourceLightCheck，那套在几百个源上跑过）：
 * - [Verdict.Alive]      页面能打开 → 有效
 * - [Verdict.Dead]       **只有 HTTP 404/410** → 确定失效，可以删。
 *                        域名解析失败不归这一档：那可能是污染（需要代理），判死会白删
 * - [Verdict.NeedProxy]  401/403/超时/解析失败 → **可能被墙或要代理**，不能直接删
 * - [Verdict.Unknown]    地址本身有问题 → 什么都不做
 *
 * 「需要代理」单独一档是这套的关键：这类站不是坏了，是这台网络到不了。
 * 一起删掉的话，用户换个网络环境就再也找不回来。
 */
class SourceLightCheck {

    sealed interface Verdict {
        /** 确定失效 */
        data class Dead(val reason: String) : Verdict
        /** 连不上/被拒：可能需要代理，也可能是临时抽风 */
        data class NeedProxy(val reason: String) : Verdict
        data object Alive : Verdict
        /** 地址本身取不到主机：什么都不做 */
        data object Unknown : Verdict
    }

    suspend fun check(url: String): Verdict = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return@withContext Verdict.Unknown

        val host = runCatching { URI(trimmed).host }.getOrNull()
            ?: runCatching { URI("https://" + trimmed).host }.getOrNull()
            ?: return@withContext Verdict.Unknown
        if (host.isBlank()) return@withContext Verdict.Unknown

        // 域名解析不出来：**不能算确定失效**。
        // DNS 污染和域名过期在技术上分不出来 —— 都表现为解析失败，
        // 而污染恰恰是最常见的「需要代理」，判死就把用户的源白删了（内置的 MissAV 就这么没的）。
        // 所以这里归到 NeedProxy：没代理时一个都不删，有代理再谈。
        if (!runCatching { InetAddress.getByName(host) }.isSuccess) {
            return@withContext Verdict.NeedProxy("域名解析失败（可能被污染）")
        }

        val full = if (trimmed.startsWith("http")) trimmed else "https://" + trimmed
        runCatching {
            val conn = (java.net.URL(full).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..399 -> Verdict.Alive
                    404, 410 -> Verdict.Dead("页面不存在（HTTP " + code + "）")
                    401, 403, 451 -> Verdict.NeedProxy("访问被拒（HTTP " + code + "）")
                    in 500..599 -> Verdict.NeedProxy("站点出错（HTTP " + code + "）")
                    else -> Verdict.NeedProxy("异常状态（HTTP " + code + "）")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e ->
            val msg = e.message ?: ""
            // 同样：UnknownHost 可能是污染，不是站死了
            if (msg.contains("UnknownHost", true)) Verdict.NeedProxy("域名解析失败（可能被污染）")
            else Verdict.NeedProxy("连不上（" + msg.take(24) + "）")
        }
    }
}

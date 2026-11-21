package com.jingdu.guan.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 当前有没有走代理（VPN）。
 *
 * 这个判断决定「打不开」该算谁的账：
 * - **挂了代理**还打不开 → 站点自己的问题，可能是真失效
 * - **没挂代理**打不开 → 很可能是被墙，删了就再也找不回来
 *
 * 没有它的话，两个情况在 HTTP 结果上长得一模一样，只能靠猜。
 */
object ProxyState {

    /**
     * 现在是否有一条 VPN/代理通道在用。
     *
     * 用 allNetworks 而不是 activeNetwork：代理 App 开的 VPN 有时不是"活动网络"，
     * 但它在转发流量 —— 只查 activeNetwork 会漏报。
     */
    fun isProxyActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.allNetworks.any { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(false)
}

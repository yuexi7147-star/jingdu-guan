package com.jingdu.guan.data.repo

import android.content.Context

/**
 * 连续失败计数：一次连不上不算死，连续几次才算。
 *
 * 为什么需要：手机网络抖一下很常见（地铁、切 Wi-Fi、电梯）。
 * 一次超时就把源删了，用户会莫名其妙少一堆源 —— 这是「检测越用越少」的根源。
 *
 * 存文件而不是数据库表：只是几个计数，为它加一张表、写一次迁移不划算。
 */
class SuspiciousCounter(context: Context) {

    private val prefs = context.getSharedPreferences("rss_suspect", Context.MODE_PRIVATE)

    /** 记一次失败，返回累计次数 */
    fun bump(id: String): Int {
        val n = prefs.getInt(id, 0) + 1
        prefs.edit().putInt(id, n).apply()
        return n
    }

    /** 这次通了，清零 */
    fun clear(id: String) {
        if (prefs.contains(id)) prefs.edit().remove(id).apply()
    }

    fun clearAll() { prefs.edit().clear().apply() }

    companion object {
        /** 连续这么多次连不上才判死 */
        const val MAX_SUSPICIOUS = 3
    }
}

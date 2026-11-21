package com.jingdu.guan.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 交给别的应用打开：外置播放器（VLC、MX、系统播放器都行），或者浏览器。
 *
 * 为什么要这个出口：内置播放器只认 mp4/m3u8 直链；
 * 有些站的地址是特殊协议、加密流、需要专用解码器 —— 这些交给用户装好的播放器更靠谱。
 */
object MediaOpenExternal {

    /** 返回是否成功拉起选择器（没有能处理的应用时返回 false） */
    fun open(context: Context, url: String, title: String = ""): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mimeOf(url))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, title.ifBlank { "用其他应用打开" }).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)

    /** 按扩展名给 MIME：给错了播放器会被筛掉，选择器里一个应用都没有 */
    private fun mimeOf(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> "application/x-mpegURL"
            u.contains(".mpd") -> "application/dash+xml"
            u.contains(".mp4") -> "video/mp4"
            u.contains(".webm") -> "video/webm"
            u.contains(".mkv") -> "video/x-matroska"
            u.contains(".m4a") -> "audio/mp4"
            u.contains(".mp3") -> "audio/mpeg"
            else -> "video/*"
        }
    }
}

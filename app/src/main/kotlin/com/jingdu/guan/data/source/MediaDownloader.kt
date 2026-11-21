package com.jingdu.guan.data.source

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.net.URI

/**
 * 嗅探到的地址交给系统下载管理器。
 *
 * 用它而不是自己写下载：通知栏进度、断点、失败重试、暂停都由系统管，用户在「下载」里能直接看到文件。
 *
 * 必带 Referer —— 这些站有防盗链，不带就是 403，下载下来是几十字节的错误页。
 */
object MediaDownloader {

    /** 开始下载，返回是否排上队 */
    fun enqueue(context: Context, url: String, referer: String?, title: String): Boolean {
        return runCatching {
            val fileName = buildFileName(url, title)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("豆见下载")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            if (!referer.isNullOrBlank()) req.addRequestHeader("Referer", referer)
            req.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            true
        }.getOrDefault(false)
    }

    /**
     * 文件名：标题 + 原扩展名。
     *
     * 标题里常有非法字符（/ : ? *），替换掉；扩展名从地址里认，认不出就按 .mp4。
     */
    private fun buildFileName(url: String, title: String): String {
        val safeTitle = title.ifBlank { "video" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)

        val ext = runCatching {
            val path = URI(url).path ?: ""
            when {
                path.endsWith(".m3u8", true) -> ".m3u8"
                path.endsWith(".mp4", true) -> ".mp4"
                path.endsWith(".m4a", true) -> ".m4a"
                path.endsWith(".mp3", true) -> ".mp3"
                path.endsWith(".webm", true) -> ".webm"
                else -> ".mp4"
            }
        }.getOrDefault(".mp4")

        // m3u8 是播放列表，不是视频文件；下载下来意义不大，提示用 .m3u8 后缀保留原样
        return safeTitle + ext
    }
}

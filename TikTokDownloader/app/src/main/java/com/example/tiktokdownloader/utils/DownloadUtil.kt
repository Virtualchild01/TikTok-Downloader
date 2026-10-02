package com.example.tiktokdownloader.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

object DownloadUtil {

    /**
     * Enqueues a video download via Android system DownloadManager.
     * Automatically saves to the public Downloads directory and shows in system notifications.
     */
    fun downloadVideo(
        context: Context,
        videoUrl: String,
        videoId: String?,
        title: String?
    ): Long {
        val sanitizedTitle = (videoId ?: System.currentTimeMillis().toString())
            .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val fileName = "TikTok_${sanitizedTitle}.mp4"

        val request = DownloadManager.Request(Uri.parse(videoUrl))
            .setTitle(title?.take(50) ?: "TikTok Video (No Watermark)")
            .setDescription("Downloading TikTok video without watermark...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Returns the file path in Downloads directory for a given video ID.
     */
    fun getDownloadedFile(videoId: String?): File {
        val sanitizedTitle = (videoId ?: System.currentTimeMillis().toString())
            .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, "TikTok_${sanitizedTitle}.mp4")
    }
}

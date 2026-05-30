package com.boris55555.listener

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri

fun downloadAudio(context: Context, name: String, url: String) {
    val sanitizedName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val request = DownloadManager.Request(url.toUri())
        .setTitle(name)
        .setDescription("Ladataan ääniraitaa...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        
        // Save to public Downloads/Listener folder
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Listener/$sanitizedName.m4a")

        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)
    Toast.makeText(context, "Lataus aloitettu", Toast.LENGTH_SHORT).show()
}

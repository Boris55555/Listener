package com.boris55555.listener

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != -1L) {
                val workRequest = OneTimeWorkRequestBuilder<ConversionWorker>()
                    .setInputData(Data.Builder().putLong("download_id", downloadId).build())
                    .build()
                WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }
}

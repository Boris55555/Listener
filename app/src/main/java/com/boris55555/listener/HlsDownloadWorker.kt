package com.boris55555.listener

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class HlsDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val name = inputData.getString("name") ?: "download"
        val sanitizedName = DownloadManagerHelper.sanitizeFilename(name)
        
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (!publicDir.exists()) publicDir.mkdirs()
        
        val outputFilePath = File(publicDir, "$sanitizedName.mp3").absolutePath
        
        DownloadManagerHelper.markConverting(applicationContext, sanitizedName, converting = true)
        Log.d("HlsDownloadWorker", "Starting LBRY HLS download & conversion: $url")

        try {
            // Robust initialization check - verify if already initialized or retry
            var initSuccessful = false
            for (attempt in 1..3) {
                try {
                    Log.d("HlsDownloadWorker", "Initialization attempt $attempt")
                    // We check if it's already initialized by calling a safe method or just re-initing
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    
                    // Small delay to let native libs settle
                    delay(200)
                    initSuccessful = true
                    Log.d("HlsDownloadWorker", "YoutubeDL/FFmpeg initialized successfully")
                    break
                } catch (e: Exception) {
                    Log.w("HlsDownloadWorker", "Init attempt $attempt failed: ${e.message}")
                    if (attempt < 3) delay(1000)
                }
            }

            if (!initSuccessful) {
                Log.e("HlsDownloadWorker", "Could not initialize YoutubeDL instance for HLS.")
                DownloadManagerHelper.markConverting(applicationContext, sanitizedName, converting = false)
                return@withContext Result.failure()
            }

            // Double check initialization state via reflection if needed, but YoutubeDL library 
            // doesn't expose a public 'isInitialized' flag easily without calling execute.

            val request = YoutubeDLRequest(url)
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
            request.addOption("-o", outputFilePath)
            request.addOption("--add-header", "Referer:https://odysee.com/")
            request.addOption("--add-header", "Origin:https://odysee.com")

            val response = YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                val progressData = workDataOf("progress" to progress.toInt())
                runBlocking { setProgress(progressData) }
                if (progress.toInt() % 20 == 0) {
                    Log.d("HlsDownloadWorker", "Progress: $progress%, ETA: $eta s")
                }
            }
            
            DownloadManagerHelper.markConverting(applicationContext, sanitizedName, converting = false)
            
            if (response.exitCode == 0) {
                Log.d("HlsDownloadWorker", "HLS Download successful.")
                applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
                Result.success()
            } else {
                Log.e("HlsDownloadWorker", "HLS Download failed with exit code ${response.exitCode}. Output: ${response.out}")
                applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("HlsDownloadWorker", "Error during HLS execution", e)
            DownloadManagerHelper.markConverting(applicationContext, sanitizedName, converting = false)
            applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
            Result.failure()
        }
    }
}

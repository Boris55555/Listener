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

class YoutubeDLWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val name = inputData.getString("name") ?: "download"
        val sanitizedName = DownloadManagerHelper.sanitizeFilename(name)
        
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (!publicDir.exists()) publicDir.mkdirs()
        
        val outputFilePath = File(publicDir, "$sanitizedName.mp3").absolutePath
        
        Log.d("YoutubeDLWorker", "Starting yt-dlp download: $url")

        try {
            // Mandatory SABR backoff wait for YouTube to avoid throttling
            if (url.contains("youtube.com") || url.contains("youtu.be")) {
                Log.d("YoutubeDLWorker", "Applying 3s SABR backoff wait")
                delay(3000)
            }

            // Robust initialization check
            var initSuccessful = false
            for (attempt in 1..3) {
                try {
                    Log.d("YoutubeDLWorker", "Initialization attempt $attempt")
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    initSuccessful = true
                    Log.d("YoutubeDLWorker", "YoutubeDL/FFmpeg initialized successfully")
                    break
                } catch (e: Exception) {
                    Log.w("YoutubeDLWorker", "Init attempt $attempt failed: ${e.message}")
                    if (attempt < 3) delay(500)
                }
            }

            if (!initSuccessful) {
                Log.e("YoutubeDLWorker", "Could not initialize YoutubeDL instance.")
                return@withContext Result.failure()
            }

            val request = YoutubeDLRequest(url)
            
            // Speed and stability optimizations
            // 1. Force audio-only format to avoid downloading video data
            request.addOption("-f", "bestaudio/best")
            
            // 2. Extract and convert to mp3
            request.addOption("-x") 
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
            request.addOption("-o", outputFilePath)
            
            // 3. SponsorBlock
            request.addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction,intro,outro,preview,music_offtopic,filler")
            
            // 4. Maximum speed optimizations
            request.addOption("-N", "8") // Parallel fragments
            request.addOption("--buffer-size", "1M") // Larger buffer for faster I/O
            request.addOption("--hls-prefer-native") // Native HLS is often faster
            request.addOption("--no-mtime")
            
            // 5. Headers for stability
            request.addOption("--user-agent", ListenerApp.USER_AGENT)
            
            if (url.contains("youtube.com") || url.contains("youtu.be")) {
                // Additional options for YouTube if needed
            } else if (url.contains("odysee.com") || url.contains("lbry")) {
                request.addOption("--add-header", "Referer:https://odysee.com/")
                request.addOption("--add-header", "Origin:https://odysee.com")
            }

            Log.d("YoutubeDLWorker", "Executing yt-dlp command for $outputFilePath")
            
            val response = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
                // Progress updates to WorkManager
                val progressData = workDataOf("progress" to progress.toInt())
                runBlocking { setProgress(progressData) }
                if (progress.toInt() % 10 == 0) {
                    Log.d("YoutubeDLWorker", "Progress: $progress%, ETA: $etaInSeconds s")
                }
            }

            Log.d("YoutubeDLWorker", "yt-dlp execution finished. Exit code: ${response.exitCode}")
            Log.d("YoutubeDLWorker", "yt-dlp output: ${response.out}")

            if (response.exitCode == 0) {
                Log.d("YoutubeDLWorker", "yt-dlp successful.")
                applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
                Result.success()
            } else {
                Log.e("YoutubeDLWorker", "yt-dlp failed with exit code ${response.exitCode}. Error: ${response.out}")
                applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("YoutubeDLWorker", "Error during yt-dlp execution", e)
            applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
            Result.failure()
        }
    }
}

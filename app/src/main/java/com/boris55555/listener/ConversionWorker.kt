package com.boris55555.listener

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getLong("download_id", -1L)
        if (downloadId == -1L) return@withContext Result.failure()

        val dm = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = try { dm.query(query) } catch (e: Exception) { 
            Log.e("ConversionWorker", "Query failed", e)
            null 
        } ?: return@withContext Result.failure()

        if (!cursor.moveToFirst()) {
            cursor.close()
            return@withContext Result.failure()
        }

        val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        
        if (localUriIdx == -1 || statusIdx == -1) {
            cursor.close()
            return@withContext Result.failure()
        }

        val localUriString = cursor.getString(localUriIdx)
        val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val title = if (titleIdx != -1) cursor.getString(titleIdx) ?: "" else ""
        val status = cursor.getInt(statusIdx)
        cursor.close()

        if (status != DownloadManager.STATUS_SUCCESSFUL || localUriString == null) {
            return@withContext Result.failure()
        }

        val uri = localUriString.toUri()
        val inputFile = if (uri.scheme == "file") {
            File(uri.path ?: "")
        } else {
            val publicDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Listener")
            val sanitizedTitle = DownloadManagerHelper.sanitizeFilename(title)
            val matchingFile = publicDir.listFiles()?.find { 
                it.nameWithoutExtension == sanitizedTitle && (it.extension == "mp4" || it.extension == "mkv" || it.extension == "webm" || it.extension == "m4a" || it.extension == "aac")
            }
            matchingFile ?: File(uri.path ?: "")
        }

        if (!inputFile.exists()) {
            Log.e("ConversionWorker", "Input file does not exist: ${inputFile.absolutePath}")
            return@withContext Result.failure()
        }

        val ext = inputFile.extension.lowercase()
        val isVideo = ext in listOf("mp4", "mkv", "webm", "avi", "mov", "flv")
        val isAudio = ext in listOf("m4a", "mp3", "aac", "opus", "ogg", "wav", "flac", "m4b")

        if (isAudio) {
            Log.d("ConversionWorker", "File is already an audio file, no conversion needed: ${inputFile.name}")
            return@withContext Result.success()
        }

        if (!isVideo) {
            Log.d("ConversionWorker", "File type not recognized for conversion: ${inputFile.name}")
            return@withContext Result.success()
        }

        val outputFilePath = inputFile.absolutePath.substringBeforeLast(".") + ".mp3"
        val outputFile = File(outputFilePath)
        val sanitizedTitle = DownloadManagerHelper.sanitizeFilename(title)

        Log.d("ConversionWorker", "Starting conversion: ${inputFile.name} -> ${outputFile.name}")
        
        DownloadManagerHelper.markConverting(applicationContext, sanitizedTitle, converting = true)

        try {
            // Base command for MP3 conversion
            // -y (overwrite), -i (input), -vn (no video), -acodec libmp3lame, -q:a 2 (high quality VBR)
            var command = "-y -i \"${inputFile.absolutePath}\" -vn -acodec libmp3lame -q:a 2"
            
            // Check for SponsorBlock segments in metadata
            val metadata = StorageManager.loadDownloadMetadata(applicationContext, sanitizedTitle)
            if (metadata != null && (metadata.source == "YOUTUBE" || metadata.url.contains("youtube.com") || metadata.url.contains("youtu.be"))) {
                val videoId = extractYoutubeId(metadata.url)
                if (videoId != null) {
                    try {
                        val segments = SponsorBlockManager.fetchSegments(videoId)
                        if (segments.isNotEmpty()) {
                            Log.d("ConversionWorker", "Found ${segments.size} SponsorBlock segments to skip for $videoId")
                        }
                    } catch (e: Exception) {
                        Log.w("ConversionWorker", "Could not fetch SponsorBlock segments: ${e.message}")
                    }
                }
            }

            command += " \"$outputFilePath\""

            val session = FFmpegKit.execute(command)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                Log.d("ConversionWorker", "Conversion successful. Deleting original file.")
                inputFile.delete()
                applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
                Result.success()
            } else {
                Log.e("ConversionWorker", "Conversion failed with return code ${session.returnCode}. Error: ${session.failStackTrace}")
                applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("ConversionWorker", "Error during conversion", e)
            Result.failure()
        } finally {
            DownloadManagerHelper.markConverting(applicationContext, sanitizedTitle, converting = false)
            applicationContext.sendBroadcast(Intent("com.boris55555.listener.CONVERSION_REFRESH"))
        }
    }

    private fun extractYoutubeId(url: String): String? {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            else -> null
        }
    }
}

package com.boris55555.listener

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

object DownloadManagerHelper {
    private const val PREFS_NAME = "listener_prefs"
    private const val KEY_DOWNLOAD_PATH = "download_path_uri"

    fun isCurrentlyDownloading(dm: DownloadManager, sanitizedName: String): Boolean {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_PENDING
        )
        val cursor = dm.query(query) ?: return false
        var found = false
        while (cursor.moveToNext()) {
            val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            if (titleColumn != -1) {
                val title = cursor.getString(titleColumn)
                if (title != null && sanitizeFilename(title) == sanitizedName) {
                    found = true
                    break
                }
            }
        }
        cursor.close()
        return found
    }

    fun getActiveDownloadId(dm: DownloadManager, name: String): Long? {
        val sanitizedName = sanitizeFilename(name)
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_PENDING
        )
        val cursor = dm.query(query) ?: return null
        var id: Long? = null
        while (cursor.moveToNext()) {
            val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            if (titleColumn != -1 && idColumn != -1) {
                val title = cursor.getString(titleColumn)
                if (title != null && sanitizeFilename(title) == sanitizedName) {
                    id = cursor.getLong(idColumn)
                    break
                }
            }
        }
        cursor.close()
        return id
    }

    private fun getMetadataForFile(fileName: String, metadata: Map<String, SearchResult>): SearchResult? {
        val trimmed = fileName.trim()
        metadata[trimmed]?.let { return it }
        // Try with underscores replaced by spaces
        metadata[trimmed.replace("_", " ")]?.let { return it }
        
        // Try stripping " -1", " -2", etc.
        val regex = Regex("(.*) -\\d+$")
        val match = regex.matchEntire(trimmed)
        if (match != null) {
            val baseName = match.groupValues[1].trim()
            metadata[baseName]?.let { return it }
            metadata[baseName.replace("_", " ")]?.let { return it }
        }
        return null
    }

    fun isFileFullyDownloaded(context: Context, name: String): Boolean {
        val sanitizedName = sanitizeFilename(name)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        if (isCurrentlyDownloading(dm, sanitizedName)) return false

        val extensions = listOf("m4a", "mp3", "mp4")

        val customUri = getDownloadPath(context)
        if (customUri != null) {
            val doc = DocumentFile.fromTreeUri(context, customUri)
            if (doc != null) {
                val files = doc.listFiles()
                if (files.any { f -> 
                    val fname = f.name ?: ""
                    extensions.any { ext ->
                        val base = fname.removeSuffix(".$ext")
                        if (base == fname) false // extension didn't match
                        else base == sanitizedName || (base.startsWith("$sanitizedName -") && base.substringAfterLast(" -").all { it.isDigit() })
                    }
                }) return true
            }
        }
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (publicDir.exists()) {
            val files = publicDir.listFiles()
            if (files != null && files.any { f -> 
                val fname = f.nameWithoutExtension
                val ext = f.extension
                extensions.contains(ext) && (fname == sanitizedName || (fname.startsWith("$sanitizedName -") && fname.substringAfterLast(" -").all { it.isDigit() }))
            }) return true
        }
        return false
    }

    fun getDownloadedAndActiveFiles(context: Context, metadata: Map<String, SearchResult>): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val extensions = listOf(".m4a", ".mp3", ".mp4", "")

        // 1. Finished files from custom folder
        val customUri = getDownloadPath(context)
        if (customUri != null) {
            val doc = DocumentFile.fromTreeUri(context, customUri)
            doc?.listFiles()?.forEach { file ->
                val name = file.name ?: ""
                val matchedExt = extensions.find { it.isNotEmpty() && name.endsWith(it) }
                val fileName = if (matchedExt != null) name.removeSuffix(matchedExt) else name
                
                if (!isCurrentlyDownloading(dm, sanitizeFilename(fileName))) {
                    val meta = getMetadataForFile(fileName, metadata)
                    results.add(SearchResult(
                        name = fileName,
                        url = file.uri.toString(),
                        isVideo = true,
                        isDownloaded = true,
                        uploaderName = meta?.uploaderName ?: "Unknown",
                        uploaderUrl = meta?.uploaderUrl,
                        isRss = meta?.isRss ?: false,
                        description = meta?.description ?: "",
                        duration = meta?.duration ?: -1L,
                        source = meta?.source ?: "YOUTUBE",
                        totalSize = StorageManager.formatFileSize(file.length()),
                        pubDate = meta?.pubDate ?: 0L,
                        textualDate = meta?.textualDate,
                        downloadDate = file.lastModified(),
                        lbryId = meta?.lbryId,
                        lbryName = meta?.lbryName,
                        mediaType = meta?.mediaType
                    ))
                }
            }
        }

        // 2. Finished files from public folder
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (publicDir.exists()) {
            publicDir.listFiles()?.forEach { file ->
                val name = file.name
                val matchedExt = extensions.find { it.isNotEmpty() && name.endsWith(it) }
                val fileName = if (matchedExt != null) name.removeSuffix(matchedExt) else name
                
                if (results.none { it.name == fileName }) {
                    if (!isCurrentlyDownloading(dm, sanitizeFilename(fileName))) {
                        val meta = getMetadataForFile(fileName, metadata)
                        results.add(SearchResult(
                            name = fileName,
                            url = Uri.fromFile(file).toString(),
                            isVideo = true,
                            isDownloaded = true,
                            uploaderName = meta?.uploaderName ?: "Unknown",
                            uploaderUrl = meta?.uploaderUrl,
                            isRss = meta?.isRss ?: false,
                            description = meta?.description ?: "",
                            duration = meta?.duration ?: -1L,
                            source = meta?.source ?: "YOUTUBE",
                            totalSize = StorageManager.formatFileSize(file.length()),
                            pubDate = meta?.pubDate ?: 0L,
                            textualDate = meta?.textualDate,
                            downloadDate = file.lastModified(),
                            lbryId = meta?.lbryId,
                            lbryName = meta?.lbryName,
                            mediaType = meta?.mediaType
                        ))
                    }
                }
            }
        }

        // 3. Active downloads from system
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_PENDING
        )
        val cursor = dm.query(query)
        if (cursor != null) {
            while (cursor.moveToNext()) {
                val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val downloadedColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                if (titleColumn != -1 && idColumn != -1) {
                    val title = cursor.getString(titleColumn) ?: "Unknown"
                    if (results.none { it.name == title }) {
                        val id = cursor.getLong(idColumn)
                        val downloaded = if (downloadedColumn != -1) cursor.getLong(downloadedColumn) else 0L
                        val total = if (totalColumn != -1) cursor.getLong(totalColumn) else 0L
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        val sanitizedName = sanitizeFilename(title)
                        val meta = getMetadataForFile(sanitizedName, metadata)

                        results.add(SearchResult(
                            name = title,
                            url = meta?.url ?: "",
                            isVideo = true,
                            isDownloaded = false,
                            isDownloading = true,
                            downloadId = id,
                            downloadProgress = progress,
                            uploaderName = meta?.uploaderName ?: "Unknown",
                            uploaderUrl = meta?.uploaderUrl,
                            isRss = meta?.isRss ?: false,
                            description = meta?.description ?: "",
                            duration = meta?.duration ?: -1L,
                            source = meta?.source ?: "YOUTUBE",
                            totalSize = if (total > 0) StorageManager.formatFileSize(total) else null,
                            pubDate = meta?.pubDate ?: System.currentTimeMillis(), // Show at top if new
                            textualDate = meta?.textualDate,
                            lbryId = meta?.lbryId,
                            lbryName = meta?.lbryName,
                            mediaType = meta?.mediaType
                        ))
                    }
                }
            }
            cursor.close()
        }

        return results
    }

    fun enqueueDownload(context: Context, result: SearchResult, audioUrl: String, allowMobile: Boolean): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val sanitizedName = sanitizeFilename(result.name)
        
        val isLbry = result.source == "LBRY" || result.lbryId != null
        val mediaType = result.mediaType?.lowercase() ?: ""
        
        val (ext, mime) = when {
            audioUrl.lowercase().contains(".mp3") -> "mp3" to "audio/mpeg"
            audioUrl.lowercase().contains(".mp4") -> "mp4" to "video/mp4"
            audioUrl.lowercase().contains(".m4a") -> "m4a" to "audio/mp4"
            audioUrl.lowercase().contains(".m3u8") -> "mp4" to "video/mp4" // Treat HLS as mp4 for filename, though it might fail
            isLbry && (mediaType.contains("mp3") || mediaType.contains("audio")) -> "mp3" to "audio/mpeg"
            isLbry && mediaType.contains("video") -> "mp4" to "video/mp4"
            isLbry && result.isVideo -> "mp4" to "video/mp4"
            isLbry -> "mp3" to "audio/mpeg"
            else -> "m4a" to "audio/mp4"
        }

        val request = DownloadManager.Request(Uri.parse(audioUrl))
            .setTitle(result.name)
            .setDescription("Downloading ${if (isLbry) "LBRY" else "YouTube"} audio...")
            .setMimeType(mime)
            .addRequestHeader("User-Agent", ListenerApp.USER_AGENT)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(allowMobile)
            .setAllowedOverRoaming(allowMobile)
        
        if (isLbry) {
            request.addRequestHeader("Referer", "https://odysee.com/")
            request.addRequestHeader("Origin", "https://odysee.com")
            request.addRequestHeader("Accept", "*/*")
        }
        
        val fullFileName = "$sanitizedName.$ext"
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Listener/$fullFileName")
        
        android.util.Log.d("DownloadHelper", "Enqueuing download: $fullFileName (MIME: $mime) from URL: $audioUrl")
        return dm.enqueue(request)
    }

    fun deleteFile(context: Context, name: String) {
        val sanitizedName = sanitizeFilename(name)
        val customUri = getDownloadPath(context)
        if (customUri != null) {
            val doc = DocumentFile.fromTreeUri(context, customUri)
            doc?.findFile("$sanitizedName.m4a")?.delete()
            doc?.findFile("$sanitizedName.mp3")?.delete()
            doc?.findFile("$sanitizedName.mp4")?.delete()
        }
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        File(publicDir, "$sanitizedName.m4a").takeIf { it.exists() }?.delete()
        File(publicDir, "$sanitizedName.mp3").takeIf { it.exists() }?.delete()
        File(publicDir, "$sanitizedName.mp4").takeIf { it.exists() }?.delete()
    }

    fun getLocalUri(context: Context, name: String): Uri? {
        val sanitizedName = sanitizeFilename(name)
        val customUri = getDownloadPath(context)
        val extensions = listOf(".m4a", ".mp3", ".mp4", "")
        
        if (customUri != null) {
            val doc = DocumentFile.fromTreeUri(context, customUri)
            if (doc != null) {
                val files = doc.listFiles()
                val file = files.find { f -> 
                    val fname = f.name ?: ""
                    extensions.any { ext -> 
                        val base = if (ext.isNotEmpty()) fname.removeSuffix(ext) else fname
                        base == sanitizedName || (base.startsWith("$sanitizedName -") && base.substringAfterLast(" -").all { it.isDigit() })
                    }
                }
                if (file != null) return file.uri
            }
        }
        
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (publicDir.exists()) {
            val files = publicDir.listFiles()
            val file = files?.find { f ->
                val fname = f.name
                extensions.any { ext ->
                    val base = if (ext.isNotEmpty()) fname.removeSuffix(ext) else fname
                    base == sanitizedName || (base.startsWith("$sanitizedName -") && base.substringAfterLast(" -").all { it.isDigit() })
                }
            }
            if (file != null) return Uri.fromFile(file)
        }
        return null
    }

    fun setDownloadPath(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { e.printStackTrace() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DOWNLOAD_PATH, uri.toString()).apply()
    }

    fun getDownloadPath(context: Context): Uri? {
        val uriString = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DOWNLOAD_PATH, null)
        return uriString?.let { Uri.parse(it) }
    }

    fun getDownloadPathName(context: Context): String {
        val uri = getDownloadPath(context) ?: return "Downloads/Listener (Default)"
        return uri.path?.split("/")?.lastOrNull()?.replace("primary:", "") ?: "Selected folder"
    }

    fun sanitizeFilename(name: String): String {
        val sanitized = name.trim()
            .replace(Regex("[^a-zA-Z0-9.-]"), "_") // Whitelist only alphanumeric, dots and hyphens
            .replace(Regex("_+"), "_") // Consolidate underscores
            .trim('_', '.')
        
        val base = if (sanitized.isEmpty()) "download_${System.currentTimeMillis()}" else sanitized
        return if (base.length > 50) base.take(50).trim('_', '.') else base
    }
}

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

    fun isFileFullyDownloaded(context: Context, name: String): Boolean {
        val sanitizedName = sanitizeFilename(name)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        if (isCurrentlyDownloading(dm, sanitizedName)) return false

        val customUri = getDownloadPath(context)
        if (customUri != null) {
            val doc = DocumentFile.fromTreeUri(context, customUri)
            if (doc?.findFile("$sanitizedName.m4a") != null) return true
        }
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (File(publicDir, "$sanitizedName.m4a").exists()) return true
        return false
    }

    fun getDownloadedAndActiveFiles(context: Context, metadata: Map<String, SearchResult>): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // 1. Finished files from custom folder
        val customUri = getDownloadPath(context)
        if (customUri != null) {
            val doc = DocumentFile.fromTreeUri(context, customUri)
            doc?.listFiles()?.forEach { file ->
                if (file.name?.endsWith(".m4a") == true) {
                    val fileName = file.name?.removeSuffix(".m4a") ?: "Unknown"
                    if (!isCurrentlyDownloading(dm, sanitizeFilename(fileName))) {
                        val meta = metadata[fileName]
                        results.add(SearchResult(
                            name = fileName,
                            url = file.uri.toString(),
                            isVideo = true,
                            isDownloaded = true,
                            uploaderName = meta?.uploaderName ?: "Unknown",
                            isRss = meta?.isRss ?: false,
                            description = meta?.description ?: "",
                            duration = meta?.duration ?: -1L,
                            source = if (meta?.isRss == true) "RSS" else "YOUTUBE",
                            totalSize = StorageManager.formatFileSize(file.length()),
                            pubDate = file.lastModified()
                        ))
                    }
                }
            }
        }

        // 2. Finished files from public folder
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener")
        if (publicDir.exists()) {
            publicDir.listFiles { f -> f.name.endsWith(".m4a") }?.forEach { file ->
                if (results.none { it.name == file.nameWithoutExtension }) {
                    val fileName = file.nameWithoutExtension
                    if (!isCurrentlyDownloading(dm, sanitizeFilename(fileName))) {
                        val meta = metadata[fileName]
                        results.add(SearchResult(
                            name = fileName,
                            url = Uri.fromFile(file).toString(),
                            isVideo = true,
                            isDownloaded = true,
                            uploaderName = meta?.uploaderName ?: "Unknown",
                            isRss = meta?.isRss ?: false,
                            description = meta?.description ?: "",
                            duration = meta?.duration ?: -1L,
                            source = if (meta?.isRss == true) "RSS" else "YOUTUBE",
                            totalSize = StorageManager.formatFileSize(file.length()),
                            pubDate = file.lastModified()
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
                        val meta = metadata[sanitizedName]

                        results.add(SearchResult(
                            name = title,
                            url = meta?.url ?: "",
                            isVideo = true,
                            isDownloaded = false,
                            isDownloading = true,
                            downloadId = id,
                            downloadProgress = progress,
                            uploaderName = meta?.uploaderName ?: "Unknown",
                            isRss = meta?.isRss ?: false,
                            description = meta?.description ?: "",
                            duration = meta?.duration ?: -1L,
                            source = if (meta?.isRss == true) "RSS" else "YOUTUBE",
                            totalSize = if (total > 0) StorageManager.formatFileSize(total) else null,
                            pubDate = System.currentTimeMillis() // Show at top
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
        val request = DownloadManager.Request(Uri.parse(audioUrl))
            .setTitle(result.name)
            .setDescription("Downloading audio...")
            .addRequestHeader("User-Agent", ListenerApp.USER_AGENT)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(allowMobile)
            .setAllowedOverRoaming(allowMobile)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Listener/$sanitizedName.m4a")
        return dm.enqueue(request)
    }

    fun deleteFile(context: Context, name: String) {
        val sanitizedName = sanitizeFilename(name)
        val customUri = getDownloadPath(context)
        if (customUri != null) {
            DocumentFile.fromTreeUri(context, customUri)?.findFile("$sanitizedName.m4a")?.delete()
        }
        val publicFile = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener"), "$sanitizedName.m4a")
        if (publicFile.exists()) publicFile.delete()
    }

    fun getLocalUri(context: Context, name: String): Uri? {
        val sanitizedName = sanitizeFilename(name)
        val customUri = getDownloadPath(context)
        if (customUri != null) {
            val file = DocumentFile.fromTreeUri(context, customUri)?.findFile("$sanitizedName.m4a")
            if (file != null) return file.uri
        }
        val publicFile = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Listener"), "$sanitizedName.m4a")
        if (publicFile.exists()) return Uri.fromFile(publicFile)
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

    fun sanitizeFilename(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

package com.boris55555.listener

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object StorageManager {
    private const val SUBSCRIPTION_FILE = "subscriptions.json"
    private const val DOWNLOAD_METADATA_FILE = "download_metadata.json"
    private const val CHANNEL_CACHE_FILE = "channel_cache.json"
    private const val POSITIONS_FILE = "playback_positions.json"
    private const val LAST_PLAYED_KEY = "last_played_url"

    fun savePlaybackPosition(context: Context, name: String, positionMs: Long) {
        try {
            val file = File(context.filesDir, POSITIONS_FILE)
            val json = if (file.exists()) {
                try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            
            json.put(name, positionMs)
            file.writeText(json.toString())
            
            // Also save name as last played
            context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_PLAYED_KEY, name)
                .apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getLastPlayedName(context: Context): String? {
        return context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .getString(LAST_PLAYED_KEY, null)
    }

    fun loadPlaybackPosition(context: Context, url: String): Long {
        val file = File(context.filesDir, POSITIONS_FILE)
        if (!file.exists()) return 0L
        return try {
            val json = JSONObject(file.readText())
            json.optLong(url, 0L)
        } catch (e: Exception) { 0L }
    }

    fun loadSubscriptions(context: Context): List<Subscription> {
        val file = File(context.filesDir, SUBSCRIPTION_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            content.lines().filter { it.contains("|") }.map {
                val parts = it.split("|")
                val type = if (parts.size > 2) parts[2] else "YOUTUBE"
                val lastUpdated = if (parts.size > 3) parts[3].toLongOrNull() ?: 0L else 0L
                val latestItemUrl = if (parts.size > 4) parts[4] else null
                val latestItemPubDate = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
                Subscription(parts[0], parts[1], type, lastUpdated, latestItemUrl, latestItemPubDate)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSubscriptions(context: Context, subs: List<Subscription>) {
        val file = File(context.filesDir, SUBSCRIPTION_FILE)
        // Sort by the actual content date, fallback to update date
        val sorted = subs.sortedWith(compareByDescending<Subscription> { it.latestItemPubDate }.thenByDescending { it.lastUpdated })
        val content = sorted.joinToString("\n") { 
            "${it.name}|${it.url}|${it.type}|${it.lastUpdated}|${it.latestItemUrl ?: ""}|${it.latestItemPubDate}"
        }
        file.writeText(content)
    }

    fun exportSubscriptionsJson(context: Context): String {
        val subs = loadSubscriptions(context)
        val array = JSONArray()
        subs.forEach { sub ->
            val obj = JSONObject()
            obj.put("name", sub.name)
            obj.put("url", sub.url)
            obj.put("type", sub.type)
            array.put(obj)
        }
        return array.toString(2)
    }

    fun importSubscriptionsJson(jsonString: String): List<Subscription> {
        val results = mutableListOf<Subscription>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                results.add(Subscription(
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    type = obj.optString("type", "YOUTUBE")
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return results
    }

    fun saveDownloadMetadata(context: Context, fileName: String, result: SearchResult) {
        try {
            val file = File(context.filesDir, DOWNLOAD_METADATA_FILE)
            val json = if (file.exists()) {
                try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            
            val item = JSONObject()
            item.put("uploader", result.uploaderName ?: "Unknown")
            item.put("isRss", result.isRss)
            item.put("desc", result.description ?: "")
            item.put("duration", result.duration)
            item.put("url", result.url)
            item.put("pubDate", result.pubDate)
            item.put("textualDate", result.textualDate ?: "")
            json.put(fileName, item)
            
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadAllDownloadMetadata(context: Context): Map<String, SearchResult> {
        val file = File(context.filesDir, DOWNLOAD_METADATA_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, SearchResult>()
            json.keys().forEach { fileName ->
                val item = json.getJSONObject(fileName)
                map[fileName] = SearchResult(
                    name = fileName,
                    url = item.optString("url", ""),
                    isVideo = true,
                    uploaderName = item.optString("uploader", "Unknown"),
                    isRss = item.optBoolean("isRss", false),
                    description = item.optString("desc", ""),
                    duration = item.optLong("duration", -1L),
                    pubDate = item.optLong("pubDate", 0L),
                    textualDate = item.optString("textualDate").takeIf { it.isNotEmpty() }
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun deleteDownloadMetadata(context: Context, fileName: String) {
        try {
            val file = File(context.filesDir, DOWNLOAD_METADATA_FILE)
            if (!file.exists()) return
            val json = try { JSONObject(file.readText()) } catch (e: Exception) { return }
            json.remove(fileName)
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveChannelCache(context: Context, cache: Map<String, List<SearchResult>>) {
        try {
            val file = File(context.filesDir, CHANNEL_CACHE_FILE)
            val json = JSONObject()
            cache.forEach { (url, results) ->
                val array = JSONArray()
                results.forEach { res ->
                    val item = JSONObject()
                    item.put("name", res.name)
                    item.put("url", res.url)
                    item.put("isVideo", res.isVideo)
                    item.put("uploader", res.uploaderName)
                    item.put("duration", res.duration)
                    item.put("desc", res.description)
                    item.put("isRss", res.isRss)
                    item.put("isLive", res.isLive)
                    item.put("source", res.source)
                    item.put("pubDate", res.pubDate)
                    item.put("textualDate", res.textualDate ?: "")
                    array.put(item)
                }
                json.put(url, array)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadChannelCache(context: Context): Map<String, List<SearchResult>> {
        val file = File(context.filesDir, CHANNEL_CACHE_FILE)
        if (!file.exists()) return emptyMap()
        val cache = mutableMapOf<String, List<SearchResult>>()
        try {
            val json = JSONObject(file.readText())
            json.keys().forEach { url ->
                val array = json.getJSONArray(url)
                val list = mutableListOf<SearchResult>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    list.add(SearchResult(
                        name = item.getString("name"),
                        url = item.getString("url"),
                        isVideo = item.getBoolean("isVideo"),
                        uploaderName = item.optString("uploader", "Unknown"),
                        duration = item.optLong("duration", -1L),
                        description = item.optString("desc", ""),
                        isRss = item.optBoolean("isRss", false),
                        isLive = item.optBoolean("isLive", false),
                        source = item.optString("source", "YOUTUBE"),
                        pubDate = item.optLong("pubDate", 0L),
                        textualDate = item.optString("textualDate").takeIf { it.isNotEmpty() }
                    ))
                }
                cache[url] = list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cache
    }

    fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb > 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.1f KB", kb)
    }
}

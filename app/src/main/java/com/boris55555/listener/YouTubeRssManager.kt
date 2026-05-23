package com.boris55555.listener

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object YouTubeRssManager {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getChannelId(url: String): String? = withContext(Dispatchers.IO) {
        if (url.contains("/channel/UC")) {
            return@withContext url.substringAfter("/channel/").substringBefore("/").substringBefore("?")
        }
        
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: ""
                
                // Look for channelId in meta tags
                val doc = Jsoup.parse(body)
                val channelId = doc.select("meta[itemprop=channelId]").attr("content")
                if (channelId.isNotEmpty()) return@withContext channelId
                
                // Alternative: search in the page source
                val regex = Regex("\"channelId\":\"(UC[a-zA-Z0-9_-]+)\"")
                val match = regex.find(body)
                return@withContext match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchLatestVideos(channelId: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        try {
            val request = Request.Builder()
                .url(rssUrl)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: ""
                
                val doc = Jsoup.parse(body, "", org.jsoup.parser.Parser.xmlParser())
                val channelName = doc.select("title").first()?.text() ?: ""
                
                val channelId = doc.select("yt|channelId").text()
                val channelUrl = if (channelId.isNotEmpty()) "https://www.youtube.com/channel/$channelId" else ""
                
                doc.select("entry").map { entry ->
                    val videoId = entry.select("yt|videoId").text()
                    val title = entry.select("title").text()
                    val published = entry.select("published").text()
                    val pubDate = RssParser.parseDate(published)
                    val description = entry.select("media|description").text()
                    
                    // Format textual date for UI
                    val textualDate = try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        sdf.format(java.util.Date(pubDate))
                    } catch (e: Exception) { null }

                    SearchResult(
                        name = title,
                        url = "https://www.youtube.com/watch?v=$videoId",
                        isVideo = true,
                        uploaderName = channelName,
                        uploaderUrl = channelUrl.takeIf { it.isNotEmpty() },
                        description = description,
                        source = "YOUTUBE",
                        pubDate = pubDate,
                        textualDate = textualDate
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

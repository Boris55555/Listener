package com.boris55555.listener

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit

object RssParser {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun searchPodcasts(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?media=podcast&term=$encodedQuery&limit=20"
            val request = Request.Builder().url(url).header("User-Agent", ListenerApp.USER_AGENT).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val results = json.getJSONArray("results")
                val list = mutableListOf<SearchResult>()
                for (i in 0 until results.length()) {
                    val obj = results.getJSONObject(i)
                    list.add(SearchResult(
                        name = cleanText(obj.getString("trackName")),
                        url = obj.getString("feedUrl"),
                        isVideo = false,
                        uploaderName = cleanText(obj.optString("artistName")),
                        description = cleanText(obj.optString("primaryGenreName")),
                        isRss = true,
                        source = "RSS"
                    ))
                }
                list
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchRssMetadata(url: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", ListenerApp.USER_AGENT).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: ""
                val doc = Jsoup.parse(body, "", org.jsoup.parser.Parser.xmlParser())
                
                val title = doc.select("channel > title").first()?.text()
                         ?: doc.select("title").first()?.text()
                         ?: "Unknown Podcast"
                
                if (body.contains("<item", true) || body.contains("<entry", true)) {
                    Pair(cleanText(title), null)
                } else {
                    null
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace()
            null 
        }
    }

    suspend fun fetchRssItems(url: String, isFileDownloaded: (String) -> Boolean): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", ListenerApp.USER_AGENT).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: ""
                parseRssItems(body, isFileDownloaded)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun parseRssItems(body: String, isFileDownloaded: (String) -> Boolean): List<SearchResult> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(body))
            
            val episodes = mutableListOf<SearchResult>()
            var eventType = parser.eventType
            var channelTitle = ""
            var inChannel = false
            var inItem = false
            var currentTitle = ""
            var currentUrl = ""
            var currentDescription = ""
            var currentDuration = -1L
            var currentPubDate = 0L
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name?.lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "channel") inChannel = true
                        if (name == "item" || name == "entry") {
                            inItem = true
                            inChannel = false
                        }
                        if (inChannel && name == "title") channelTitle = try { parser.nextText() } catch (e: Exception) { "" }
                        if (inItem) {
                            when (name) {
                                "title" -> currentTitle = try { parser.nextText() } catch (e: Exception) { "" }
                                "enclosure" -> currentUrl = parser.getAttributeValue(null, "url") ?: ""
                                "link" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!href.isNullOrEmpty()) currentUrl = href
                                    else if (currentUrl.isEmpty()) currentUrl = try { parser.nextText() } catch (e: Exception) { "" }
                                }
                                "pubdate", "published", "updated" -> currentPubDate = parseDate(try { parser.nextText() } catch (e: Exception) { "" })
                                "description", "summary", "subtitle", "content" -> {
                                    val text = try { parser.nextText() } catch (e: Exception) { "" }
                                    if (currentDescription.length < text.length) {
                                        currentDescription = text
                                    }
                                }
                                "duration" -> {
                                    currentDuration = parseDuration(try { parser.nextText() } catch (e: Exception) { "" })
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "item" || name == "entry") {
                            if (currentTitle.isNotEmpty() && currentUrl.isNotEmpty()) {
                                episodes.add(SearchResult(
                                    name = cleanText(currentTitle),
                                    url = currentUrl,
                                    isVideo = true,
                                    uploaderName = cleanText(channelTitle),
                                    description = cleanText(currentDescription),
                                    duration = currentDuration,
                                    isDownloaded = isFileDownloaded(currentTitle),
                                    isRss = true,
                                    source = "RSS",
                                    pubDate = currentPubDate
                                ))
                            }
                            currentTitle = ""
                            currentUrl = ""
                            currentDescription = ""
                            currentDuration = -1L
                            currentPubDate = 0L
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
            episodes.sortedByDescending { it.pubDate }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun cleanText(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("<![CDATA[") && cleaned.endsWith("]]>")) {
            cleaned = cleaned.substring(9, cleaned.length - 3)
        }
        return Jsoup.parse(cleaned).text().trim()
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val trimmed = dateStr.trim()
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, Locale.US)
                return sdf.parse(trimmed)?.time ?: 0L
            } catch (e: Exception) {}
        }
        return 0L
    }

    private fun parseDuration(durationStr: String): Long {
        if (durationStr.isBlank()) return -1L
        return try {
            if (durationStr.contains(":")) {
                val parts = durationStr.split(":").map { it.trim().toLong() }
                when (parts.size) {
                    2 -> parts[0] * 60 + parts[1] // mm:ss
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2] // hh:mm:ss
                    else -> -1L
                }
            } else {
                durationStr.toLong()
            }
        } catch (e: Exception) { -1L }
    }
}

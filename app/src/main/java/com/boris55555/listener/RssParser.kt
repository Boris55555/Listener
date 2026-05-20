package com.boris55555.listener

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
        .build()
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Kompakt) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"

    fun searchPodcasts(query: String): List<SearchResult> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?media=podcast&term=$encodedQuery&limit=20"
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val results = json.getJSONArray("results")
            val list = mutableListOf<SearchResult>()
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                list.add(SearchResult(
                    name = obj.getString("trackName"),
                    url = obj.getString("feedUrl"),
                    isVideo = false,
                    uploaderName = obj.optString("artistName"),
                    description = obj.optString("primaryGenreName"),
                    isRss = true,
                    source = "RSS"
                ))
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchRssMetadata(url: String): Pair<String, String?>? {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val doc = Jsoup.parse(response.body?.string() ?: "", "", org.jsoup.parser.Parser.xmlParser())
            val title = doc.select("channel > title").first()?.text() ?: doc.select("title").first()?.text()
            if (title != null) Pair(title, null) else null
        } catch (e: Exception) { null }
    }

    suspend fun fetchRssItems(url: String, isFileDownloaded: (String) -> Boolean): List<SearchResult> {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            parseRssItems(body, isFileDownloaded)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseRssItems(body: String, isFileDownloaded: (String) -> Boolean): List<SearchResult> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(body))
        
        val episodes = mutableListOf<Triple<SearchResult, Long, String?>>()
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
                    if (name == "item") {
                        inItem = true
                        inChannel = false
                    }
                    if (inChannel && name == "title") channelTitle = parser.nextText()
                    if (inItem) {
                        when (name) {
                            "title" -> currentTitle = parser.nextText()
                            "enclosure" -> currentUrl = parser.getAttributeValue(null, "url") ?: ""
                            "pubdate" -> currentPubDate = parseDate(parser.nextText())
                            "description", "summary", "subtitle", "encoded" -> {
                                val text = try { parser.nextText() } catch (e: Exception) { "" }
                                if (currentDescription.length < text.length) {
                                    currentDescription = cleanHtml(text)
                                }
                            }
                            "duration" -> {
                                currentDuration = parseDuration(try { parser.nextText() } catch (e: Exception) { "" })
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "item") {
                        if (currentTitle.isNotEmpty() && currentUrl.isNotEmpty()) {
                            val result = SearchResult(
                                name = currentTitle,
                                url = currentUrl,
                                isVideo = true,
                                uploaderName = channelTitle,
                                description = currentDescription,
                                duration = currentDuration,
                                isDownloaded = isFileDownloaded(currentTitle),
                                isRss = true,
                                source = "RSS",
                                pubDate = currentPubDate
                            )
                            episodes.add(Triple(result, currentPubDate, currentUrl))
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
        return episodes.sortedByDescending { it.second }.map { it.first }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanHtml(html: String): String {
        return try { Jsoup.parse(html).text() } catch (e: Exception) { html }
    }

    private fun parseDuration(durationStr: String): Long {
        if (durationStr.isBlank()) return -1L
        return try {
            val parts = durationStr.split(":").map { it.trim().toLong() }
            when (parts.size) {
                1 -> parts[0] // seconds
                2 -> parts[0] * 60 + parts[1] // mm:ss
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2] // hh:mm:ss
                else -> -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }
}

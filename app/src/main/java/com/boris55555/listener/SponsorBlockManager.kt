package com.boris55555.listener

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object SponsorBlockManager {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://sponsor.ajay.app/api/skipSegments"

    data class Segment(val start: Long, val end: Long, val category: String)

    private fun sha256Prefix(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(videoId.toByteArray())
        return hash.take(2).joinToString("") { "%02x".format(it) }
    }

    suspend fun fetchSegments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        val prefix = sha256Prefix(videoId)
        val categories = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler")
        val categoriesJson = JSONArray(categories).toString()
        val url = "$API_BASE/$prefix?categories=$categoriesJson"

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext emptyList()
                if (!response.isSuccessful) return@withContext emptyList()
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONArray(body)
                val segments = mutableListOf<Segment>()
                
                for (i in 0 until json.length()) {
                    val entry = json.getJSONObject(i)
                    if (entry.getString("videoID") == videoId) {
                        val segs = entry.getJSONArray("segments")
                        for (j in 0 until segs.length()) {
                            val seg = segs.getJSONObject(j)
                            val times = seg.getJSONArray("segment")
                            segments.add(Segment(
                                start = (times.getDouble(0) * 1000).toLong(),
                                end = (times.getDouble(1) * 1000).toLong(),
                                category = seg.getString("category")
                            ))
                        }
                    }
                }
                segments.sortedBy { it.start }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

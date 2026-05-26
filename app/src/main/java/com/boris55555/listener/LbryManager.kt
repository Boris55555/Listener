package com.boris55555.listener

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LbryManager {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val PROXY_API = "https://api.na-backend.odysee.com/api/v1/proxy"
    private const val LIGHTHOUSE_API = "https://lighthouse.lbry.com"
    private const val LIGHTHOUSE_FALLBACK = "https://lighthouse.odysee.com"

    suspend fun searchLbry(query: String, contentFilter: ContentFilter): List<SearchResult> = withContext(Dispatchers.IO) {
        // Try Lighthouse first (it's better for full-text search)
        var results = searchWithLighthouse(query, contentFilter, LIGHTHOUSE_API)
        
        if (results.isEmpty()) {
            results = searchWithLighthouse(query, contentFilter, LIGHTHOUSE_FALLBACK)
        }

        // If Lighthouse still empty, try direct claim_search via Proxy API
        if (results.isEmpty()) {
            results = searchWithClaimSearch(query, contentFilter)
        }

        results
    }

    private suspend fun searchWithLighthouse(query: String, contentFilter: ContentFilter, apiBase: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val claimType = when (contentFilter) {
                ContentFilter.CHANNELS -> "&claim_type=channel"
                ContentFilter.TITLES -> "&claim_type=stream"
                else -> ""
            }
            
            // Use resolve=true to get full details in one request
            val url = "$apiBase/search?s=$encodedQuery&size=20&from=0&nsfw=false&resolve=true$claimType"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .header("Referer", "https://odysee.com/")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            
            val body = response.body?.string() ?: return@withContext emptyList()
            val array = JSONArray(body)
            val results = mutableListOf<SearchResult>()
            
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("name").trim()
                val claimId = (item.optString("claimId") ?: item.optString("claim_id")).trim()
                
                if (name.isEmpty() || claimId.isEmpty()) continue
                
                val title = item.optString("title").takeIf { it.isNotEmpty() }?.trim() ?: name
                val uploader = item.optString("channel")?.trim() ?: item.optString("uploader")?.trim()
                val uploaderUrl = item.optString("channel_url").takeIf { it.isNotEmpty() }?.trim()
                val duration = item.optLong("duration", -1L)
                val description = item.optString("description").trim().takeIf { it.isNotEmpty() }
                val pubDate = (item.optLong("release_time").takeIf { it > 0 } ?: item.optLong("timestamp")) * 1000
                val mediaType = item.optString("media_type").trim().takeIf { it.isNotEmpty() }
                
                // Better type detection
                val valueType = item.optString("value_type").lowercase()
                val isChannel = valueType == "channel" || name.startsWith("@")
                
                val itemUrl = if (isChannel) "lbry://$name#$claimId" else "https://odysee.com/$/download/$name/$claimId"

                results.add(SearchResult(
                    name = title,
                    url = itemUrl,
                    isVideo = !isChannel,
                    uploaderName = uploader,
                    uploaderUrl = uploaderUrl,
                    duration = duration,
                    description = description,
                    source = "LBRY",
                    pubDate = pubDate,
                    mediaType = mediaType,
                    lbryId = claimId,
                    lbryName = name
                ))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun searchWithClaimSearch(query: String, contentFilter: ContentFilter): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
            json.put("jsonrpc", "2.0")
            json.put("method", "claim_search")
            val params = JSONObject()
            
            params.put("text", query)
            params.put("page", 1)
            params.put("page_size", 20)
            params.put("no_totals", true)
            
            // Only search for streams (video/audio) and channels
            val claimTypes = mutableListOf<String>()
            if (contentFilter != ContentFilter.CHANNELS) claimTypes.add("stream")
            if (contentFilter != ContentFilter.TITLES) claimTypes.add("channel")
            params.put("claim_type", JSONArray(claimTypes))
            
            json.put("params", params)
            json.put("id", System.currentTimeMillis())

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(PROXY_API)
                .post(body)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .header("Referer", "https://odysee.com/")
                .header("Origin", "https://odysee.com")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val items = JSONObject(responseBody).getJSONObject("result").getJSONArray("items")
            
            val results = mutableListOf<SearchResult>()
            for (i in 0 until items.length()) {
                val claim = items.getJSONObject(i)
                val value = claim.optJSONObject("value")
                val name = claim.getString("name").trim()
                val claimId = claim.getString("claim_id").trim()
                val title = value?.optString("title")?.trim() ?: name
                
                val valueType = claim.optString("value_type").lowercase()
                val isChannel = valueType == "channel"
                
                val signingChannel = claim.optJSONObject("signing_channel")
                val uploader = signingChannel?.optJSONObject("value")?.optString("title")?.trim() 
                            ?: signingChannel?.optString("name")?.trim()
                val uploaderUrl = if (signingChannel != null) {
                    val cName = signingChannel.getString("name").trim()
                    val cId = signingChannel.getString("claim_id").trim()
                    "lbry://$cName#$cId"
                } else null
                
                val pubDate = (value?.optLong("release_time") ?: claim.optLong("timestamp")) * 1000
                val mediaType = value?.optString("media_type")?.trim()?.takeIf { it.isNotEmpty() }
                
                results.add(SearchResult(
                    name = title,
                    url = if (isChannel) "lbry://$name#$claimId" else "https://odysee.com/$/download/$name/$claimId",
                    isVideo = !isChannel,
                    uploaderName = uploader,
                    uploaderUrl = uploaderUrl,
                    duration = value?.optJSONObject("video")?.optLong("duration") ?: value?.optJSONObject("audio")?.optLong("duration") ?: -1L,
                    description = value?.optString("description"),
                    source = "LBRY",
                    pubDate = pubDate,
                    mediaType = mediaType,
                    lbryId = claimId,
                    lbryName = name
                ))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchChannelInitial(url: String, subscriptionName: String, page: Int = 1): SearchResultContainer = withContext(Dispatchers.IO) {
        try {
            val channelId = if (url.startsWith("lbry://")) {
                url.split("#").last()
            } else {
                url
            }

            val json = JSONObject()
            json.put("jsonrpc", "2.0")
            json.put("method", "claim_search")
            val params = JSONObject()
            params.put("channel_id", channelId)
            params.put("order_by", JSONArray(listOf("release_time")))
            params.put("page", page)
            params.put("page_size", 30)
            json.put("params", params)
            json.put("id", System.currentTimeMillis())

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(PROXY_API)
                .post(body)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .header("Referer", "https://odysee.com/")
                .header("Origin", "https://odysee.com")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext SearchResultContainer(emptyList(), null, null)

            val responseBody = response.body?.string() ?: return@withContext SearchResultContainer(emptyList(), null, null)
            val resultObj = JSONObject(responseBody).getJSONObject("result")
            val items = resultObj.getJSONArray("items")
            val totalPages = resultObj.optInt("total_pages", page)
            
            val channelUrl = if (url.startsWith("lbry://")) url else "lbry://$url"
            
            val results = mutableListOf<SearchResult>()
            for (i in 0 until items.length()) {
                val claim = items.getJSONObject(i)
                val value = claim.optJSONObject("value")
                val name = claim.getString("name").trim()
                val claimId = claim.getString("claim_id").trim()
                val title = value?.optString("title")?.trim() ?: name
                val pubDate = (value?.optLong("release_time") ?: claim.optLong("timestamp")) * 1000
                val mediaType = value?.optString("media_type")?.trim()?.takeIf { it.isNotEmpty() }
                
                results.add(SearchResult(
                    name = title,
                    url = "https://odysee.com/$/download/$name/$claimId",
                    isVideo = true,
                    uploaderName = subscriptionName,
                    uploaderUrl = channelUrl,
                    duration = value?.optJSONObject("video")?.optLong("duration") ?: value?.optJSONObject("audio")?.optLong("duration") ?: -1L,
                    description = value?.optString("description"),
                    source = "LBRY",
                    pubDate = pubDate,
                    mediaType = mediaType,
                    lbryId = claimId,
                    lbryName = name
                ))
            }
            // Use a custom Page implementation for LBRY
            val nextPage = if (page < totalPages) org.schabi.newpipe.extractor.Page(url, "$page") else null
            SearchResultContainer(results, nextPage, null)
        } catch (e: Exception) {
            e.printStackTrace()
            SearchResultContainer(emptyList(), null, null)
        }
    }

    suspend fun getStreamUrl(url: String): String? = getStreamUrl(url, null, null)

    suspend fun getStreamUrl(url: String, lbryId: String?, lbryName: String?): String = withContext(Dispatchers.IO) {
        val uri = if (lbryId != null && lbryName != null) {
            "lbry://$lbryName#$lbryId"
        } else if (url.startsWith("https://odysee.com/")) {
            val parts = url.split("/")
            val id = parts.lastOrNull()
            val name = if (parts.size >= 2) parts[parts.size - 2] else null
            if (name != null && id != null) "lbry://$name#$id" else url
        } else {
            url
        }

        try {
            val json = JSONObject()
            json.put("jsonrpc", "2.0")
            json.put("method", "get")
            val params = JSONObject()
            params.put("uri", uri)
            params.put("save_file", false)
            json.put("params", params)
            json.put("id", System.currentTimeMillis())

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(PROXY_API)
                .post(body)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .header("Referer", "https://odysee.com/")
                .header("Origin", "https://odysee.com")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                return@withContext processGetResult(response)
            } else if (response.code == 429) {
                android.util.Log.w("LbryManager", "429 Too Many Requests from Proxy API - Trying direct CDN fallback")
                if (lbryId != null) {
                    val directUrl = "https://player.odycdn.com/v6/streams/$lbryId/master.mp4"
                    return@withContext directUrl
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback to construction if API fails or blocks
        if (lbryId != null) {
            return@withContext "https://player.odycdn.com/v6/streams/$lbryId/master.mp4"
        }
        
        val finalId = lbryId ?: url.substringAfterLast("/", "").substringAfterLast("#", "").takeIf { it.length > 20 }
        if (finalId != null) "https://player.odycdn.com/v6/streams/$finalId/master.mp4" else url
    }

    private fun processGetResult(response: okhttp3.Response): String {
        try {
            val responseBody = response.body?.string() ?: ""
            val jsonResponse = JSONObject(responseBody)
            val result = jsonResponse.optJSONObject("result")
            val streamingUrl = result?.optString("streaming_url")
            
            if (!streamingUrl.isNullOrEmpty()) {
                // Follow redirects to get the actual CDN URL
                // This is more reliable for ExoPlayer and DownloadManager
                try {
                    val headRequest = Request.Builder()
                        .url(streamingUrl)
                        .head()
                        .header("User-Agent", ListenerApp.USER_AGENT)
                        .header("Referer", "https://odysee.com/")
                        .header("Origin", "https://odysee.com")
                        .build()
                    
                    val headResponse = httpClient.newCall(headRequest).execute()
                    if (headResponse.isSuccessful) {
                        return headResponse.request.url.toString()
                    } else if (headResponse.code == 429) {
                        android.util.Log.e("LbryManager", "429 Too Many Requests from CDN: $streamingUrl")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return streamingUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return response.request.url.toString()
    }

    suspend fun resolveUris(uris: List<String>): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
            json.put("jsonrpc", "2.0")
            json.put("method", "resolve")
            val params = JSONObject()
            params.put("urls", JSONArray(uris))
            json.put("params", params)
            json.put("id", System.currentTimeMillis())

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(PROXY_API)
                .post(body)
                .header("User-Agent", ListenerApp.USER_AGENT)
                .header("Referer", "https://odysee.com/")
                .header("Origin", "https://odysee.com")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val resultObject = JSONObject(responseBody).getJSONObject("result")
            
            val results = mutableListOf<SearchResult>()
            uris.forEach { uri ->
                val item = resultObject.optJSONObject(uri) ?: return@forEach
                val value = item.optJSONObject("value")
                val name = item.getString("name")
                val claimId = item.getString("claim_id")
                val title = value?.optString("title") ?: name
                
                val valueType = item.optString("value_type").lowercase()
                val isChannel = valueType == "channel"
                
                val signingChannel = item.optJSONObject("signing_channel")
                val uploader = signingChannel?.optJSONObject("value")?.optString("title") 
                            ?: signingChannel?.optString("name")
                val uploaderUrl = if (signingChannel != null) {
                    val cName = signingChannel.getString("name")
                    val cId = signingChannel.getString("claim_id")
                    "lbry://$cName#$cId"
                } else null
                
                val pubDate = (value?.optLong("release_time") ?: item.optLong("timestamp")) * 1000
                val mediaType = value?.optString("media_type")
                
                results.add(SearchResult(
                    name = title,
                    url = if (isChannel) "lbry://$name#$claimId" else "https://odysee.com/$/download/$name/$claimId",
                    isVideo = !isChannel,
                    uploaderName = uploader,
                    uploaderUrl = uploaderUrl,
                    duration = value?.optJSONObject("video")?.optLong("duration") ?: value?.optJSONObject("audio")?.optLong("duration") ?: -1L,
                    description = value?.optString("description"),
                    source = "LBRY",
                    pubDate = pubDate,
                    mediaType = mediaType,
                    lbryId = claimId,
                    lbryName = name
                ))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}

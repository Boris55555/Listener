package com.boris55555.listener

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.ContentCountry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ListenerApp : Application() {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize NewPipe with US English to maximize extraction compatibility
        NewPipe.init(SimpleDownloader(), Localization.DEFAULT, ContentCountry.DEFAULT)
        
        // Initialize YoutubeDL components
        try {
            // First initialize the instance - essential before update or execute
            val ytdl = YoutubeDL.getInstance()
            ytdl.init(this)
            FFmpeg.getInstance().init(this)
            
            // Try to update yt-dlp in background to handle latest YouTube changes
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Update binary - this is important for YouTube changes
                    val result = ytdl.updateYoutubeDL(this@ListenerApp)
                    Log.d("ListenerApp", "yt-dlp update status: $result")
                } catch (e: Exception) {
                    Log.w("ListenerApp", "yt-dlp update failed: ${e.message}")
                }
            }
            
            Log.d("ListenerApp", "YoutubeDL/FFmpeg initialized successfully")
        } catch (e: Exception) {
            Log.e("ListenerApp", "YoutubeDL initialization failed: ${e.message}")
        }
    }
}

class SimpleDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cookieStore = HashMap<String, String>()

    private fun getCookies(url: String): String {
        val domain = url.toHttpUrlOrNull()?.host ?: ""
        val cookies = mutableListOf<String>()
        
        // Basic YouTube cookies that help bypass some blocks
        if (domain.contains("youtube.com") || domain.contains("youtu.be")) {
            cookies.add("PREF=f2=8000000")
            cookies.add("CONSENT=PENDING+999")
            cookies.add("SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAnRyIAEaBgiA_LyaBg")
        }
        
        cookieStore.forEach { (key, value) ->
            if (url.contains(key)) {
                cookies.add(value)
            }
        }
        return cookies.distinct().joinToString("; ")
    }

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = dataToSend?.toRequestBody(null)

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .header("User-Agent", ListenerApp.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Connection", "keep-alive")
            .header("Cache-Control", "max-age=0")

        val cookies = getCookies(url)
        if (cookies.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookies)
        }

        // Apply headers from NewPipeExtractor, following NewPipe's DownloaderImpl exactly
        headers.forEach { (headerName, headerValueList) ->
            requestBuilder.removeHeader(headerName)
            headerValueList.forEach { headerValue ->
                requestBuilder.addHeader(headerName, headerValue)
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()
        
        // Handle cookies from response
        response.headers("Set-Cookie").forEach { cookie ->
            val parts = cookie.split(";")
            if (parts.isNotEmpty()) {
                val keyValue = parts[0].split("=")
                if (keyValue.size == 2) {
                    url.toHttpUrlOrNull()?.host?.let { host ->
                        cookieStore[host] = parts[0]
                    }
                }
            }
        }

        if (response.code == 429) {
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBodyToReturn,
            latestUrl
        )
    }
}

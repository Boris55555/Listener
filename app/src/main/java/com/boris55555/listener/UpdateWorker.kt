package com.boris55555.listener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import okhttp3.OkHttpClient
import okhttp3.Request

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
        val allowMobileData = prefs.getBoolean("allow_mobile_data", false)
        val showNotifications = prefs.getBoolean("show_notifications", false)
        
        if (!allowMobileData) {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || 
                         capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            if (!isWifi) {
                return@withContext Result.retry()
            }
        }

        try {
            val subs = StorageManager.loadSubscriptions(applicationContext)
            if (subs.isEmpty()) return@withContext Result.success()

            var newContentFound = false
            val newTitles = mutableListOf<String>()

            val updatedSubs = subs.map { sub: Subscription ->
                try {
                    val (latestUrl, latestTitle) = if (sub.type == "RSS") {
                        try {
                            val request = Request.Builder().url(sub.url).header("User-Agent", USER_AGENT).build()
                            val response = httpClient.newCall(request).execute()
                            val body = response.body?.string() ?: ""
                            val doc = Jsoup.parse(body, "", Parser.xmlParser())
                            val item = doc.select("item").first()
                            val url = item?.select("enclosure")?.attr("url")
                            val title = item?.select("title")?.text()
                            Pair(url, title)
                        } catch (e: Exception) { Pair(null, null) }
                    } else {
                        val service = ServiceList.YouTube
                        val channelInfo = ChannelInfo.getInfo(service, sub.url)
                        val videosTab = channelInfo.tabs.find { 
                            it.contentFilters.getOrNull(0)?.contains("video", ignoreCase = true) == true 
                        } ?: channelInfo.tabs.firstOrNull()
                        if (videosTab != null) {
                            val tabInfo = ChannelTabInfo.getInfo(service, videosTab)
                            val item = tabInfo.relatedItems.firstOrNull()
                            Pair(item?.url, item?.name)
                        } else Pair(null, null)
                    }

                    if (latestUrl != null && latestUrl != sub.latestItemUrl) {
                        newContentFound = true
                        latestTitle?.let { newTitles.add("${sub.name}: $it") }
                        sub.copy(latestItemUrl = latestUrl, lastUpdated = System.currentTimeMillis())
                    } else {
                        sub
                    }
                } catch (e: Exception) {
                    sub
                }
            }
            
            if (newContentFound) {
                StorageManager.saveSubscriptions(applicationContext, updatedSubs)
                if (showNotifications && newTitles.isNotEmpty()) {
                    sendNotification(newTitles)
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendNotification(titles: List<String>) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "new_episodes"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "New Episodes", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val contentText = if (titles.size == 1) titles[0] else "${titles.size} new items available"
        val bigText = titles.joinToString("\n")

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("New Content Available")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}

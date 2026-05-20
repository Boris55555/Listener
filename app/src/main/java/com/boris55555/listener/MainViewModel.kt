package com.boris55555.listener

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainViewModel : ViewModel() {
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions

    private val _downloadedFiles = MutableStateFlow<List<SearchResult>>(emptyList())
    val downloadedFiles: StateFlow<List<SearchResult>> = _downloadedFiles

    private val _lastPlayedFile = MutableStateFlow<SearchResult?>(null)
    val lastPlayedFile: StateFlow<SearchResult?> = _lastPlayedFile

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private val _currentPlayback = MutableStateFlow<PlaybackInfo?>(null)
    val currentPlayback: StateFlow<PlaybackInfo?> = _currentPlayback

    private val _currentSubscription = MutableStateFlow<Subscription?>(null)
    val currentSubscription: StateFlow<Subscription?> = _currentSubscription

    private val _refreshSetting = MutableStateFlow(RefreshSetting.MANUAL)
    val refreshSetting: StateFlow<RefreshSetting> = _refreshSetting

    private val _showYoutubeLive = MutableStateFlow(false)
    val showYoutubeLive: StateFlow<Boolean> = _showYoutubeLive

    private val _allowMobileData = MutableStateFlow(false)
    val allowMobileData: StateFlow<Boolean> = _allowMobileData

    private val _showNotifications = MutableStateFlow(false)
    val showNotifications: StateFlow<Boolean> = _showNotifications

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration

    // Filter states
    var contentFilter by mutableStateOf(ContentFilter.ALL)
    var sourceFilter by mutableStateOf(SourceFilter.ALL)

    private var currentNextPage: Page? = null
    private var currentListLinkHandler: ListLinkHandler? = null
    private var currentSearchQuery: String? = null
    
    private val channelCache = mutableMapOf<String, List<SearchResult>>()
    private val rssOriginalItems = mutableMapOf<String, List<Triple<SearchResult, Long, String?>>>()
    
    private var isPollingDownloads = false

    fun initSubscriptions(context: Context) {
        viewModelScope.launch {
            val subs = withContext(Dispatchers.IO) {
                StorageManager.loadSubscriptions(context)
            }
            _subscriptions.value = subs.sortedByDescending { it.lastUpdated }
            
            withContext(Dispatchers.IO) {
                channelCache.putAll(StorageManager.loadChannelCache(context))
            }

            val prefs = context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            val settingName = prefs.getString("refresh_setting", RefreshSetting.MANUAL.name)
            val setting = try { RefreshSetting.valueOf(settingName!!) } catch (e: Exception) { RefreshSetting.MANUAL }
            _refreshSetting.value = setting
            
            val showYtLive = prefs.getBoolean("show_youtube_live", false)
            _showYoutubeLive.value = showYtLive
            
            val mobileData = prefs.getBoolean("allow_mobile_data", false)
            _allowMobileData.value = mobileData

            val notifications = prefs.getBoolean("show_notifications", false)
            _showNotifications.value = notifications
            
            refreshDownloadedFiles(context)
            
            if (setting != RefreshSetting.MANUAL) {
                updateAllSubscriptions(context)
            }
        }
    }

    fun setRefreshSetting(context: Context, setting: RefreshSetting) {
        _refreshSetting.value = setting
        context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("refresh_setting", setting.name)
            .apply()
        
        scheduleRefresh(context, setting)
    }

    fun setShowYoutubeLive(context: Context, show: Boolean) {
        _showYoutubeLive.value = show
        context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("show_youtube_live", show)
            .apply()
        
        channelCache.entries.removeIf { it.value.any { res -> !res.isRss } }
        StorageManager.saveChannelCache(context, channelCache)
    }

    fun setAllowMobileData(context: Context, allow: Boolean) {
        _allowMobileData.value = allow
        context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("allow_mobile_data", allow)
            .apply()
    }

    fun setShowNotifications(context: Context, show: Boolean) {
        _showNotifications.value = show
        context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("show_notifications", show)
            .apply()
    }

    fun isNetworkOperationAllowed(context: Context): Boolean {
        return NetworkHelper.isNetworkOperationAllowed(context, _allowMobileData.value)
    }

    private fun scheduleRefresh(context: Context, setting: RefreshSetting) {
        val workManager = WorkManager.getInstance(context)
        if (setting.hours <= 0) {
            workManager.cancelUniqueWork("subscription_update")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateWorker>(setting.hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "subscription_update",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun setDownloadPath(context: Context, uri: Uri) {
        DownloadManagerHelper.setDownloadPath(context, uri)
        refreshDownloadedFiles(context)
    }

    fun getDownloadPath(context: Context): Uri? = DownloadManagerHelper.getDownloadPath(context)
    fun getDownloadPathName(context: Context): String = DownloadManagerHelper.getDownloadPathName(context)

    fun refreshDownloadedFiles(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val metadata = StorageManager.loadAllDownloadMetadata(context)
            val results = DownloadManagerHelper.getDownloadedAndActiveFiles(context, metadata)
            
            val sortedResults = results.sortedByDescending { it.pubDate }
            _downloadedFiles.value = sortedResults
            
            // Find last played file from results by NAME
            val lastName = StorageManager.getLastPlayedName(context)
            if (lastName != null) {
                _lastPlayedFile.value = sortedResults.find { it.name == lastName }
            }
            
            // If there are active downloads, ensure polling is running to update UI
            if (results.any { it.isDownloading }) {
                withContext(Dispatchers.Main) {
                    startPollingDownloads(context)
                }
            }
        }
    }

    fun follow(context: Context, name: String, url: String, type: String = "YOUTUBE") {
        val current = _subscriptions.value.toMutableList()
        if (current.none { it.url == url }) {
            current.add(Subscription(name, url, type, System.currentTimeMillis()))
            StorageManager.saveSubscriptions(context, current)
            _subscriptions.value = current.sortedByDescending { it.lastUpdated }
            refreshFollowStatus()
            android.widget.Toast.makeText(context, "Subscribed: $name", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun unfollow(context: Context, url: String) {
        val current = _subscriptions.value.toMutableList()
        val sub = current.find { it.url == url }
        current.removeAll { it.url == url }
        StorageManager.saveSubscriptions(context, current)
        _subscriptions.value = current.sortedByDescending { it.lastUpdated }
        refreshFollowStatus()
        sub?.let {
            android.widget.Toast.makeText(context, "Unsubscribed: ${it.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshFollowStatus() {
        val followedUrls = _subscriptions.value.map { it.url }.toSet()
        _searchResults.value = _searchResults.value.map { 
            it.copy(isFollowed = followedUrls.contains(it.url))
        }
    }

    fun prepareSearch() {
        _currentSubscription.value = null
        _searchResults.value = emptyList()
        currentSearchQuery = null
        currentListLinkHandler = null
        currentNextPage = null
        _hasMore.value = false
    }

    fun search(context: Context, query: String) {
        if (!isNetworkOperationAllowed(context)) {
            android.widget.Toast.makeText(context, "Mobile data not allowed. Use Wi-Fi.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val trimmedQuery = query.trim()
        if (trimmedQuery.startsWith("http://") || trimmedQuery.startsWith("https://")) {
            showRssPreview(context, trimmedQuery)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _hasMore.value = false
            currentNextPage = null
            currentSearchQuery = trimmedQuery
            _currentSubscription.value = null
            
            try {
                val container = YouTubeManager.searchYouTube(trimmedQuery, contentFilter)
                val podcastResults = if (sourceFilter == SourceFilter.ALL || sourceFilter == SourceFilter.PODCASTS) {
                    RssParser.searchPodcasts(trimmedQuery)
                } else emptyList()

                currentNextPage = container.nextPage
                _hasMore.value = container.nextPage != null

                val results = (container.results + podcastResults).map { res ->
                    syncResultStatus(context, res)
                }
                _searchResults.value = results
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    private fun syncResultStatus(context: Context, res: SearchResult): SearchResult {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fullyDownloaded = DownloadManagerHelper.isFileFullyDownloaded(context, res.name)
        val activeId = DownloadManagerHelper.getActiveDownloadId(dm, res.name)
        
        return res.copy(
            isDownloaded = fullyDownloaded,
            isDownloading = activeId != null,
            downloadId = activeId,
            isFollowed = _subscriptions.value.any { it.url == res.url }
        )
    }

    private fun showRssPreview(context: Context, url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val metadata = RssParser.fetchRssMetadata(url)
            if (metadata != null) {
                _searchResults.value = listOf(SearchResult(
                    name = metadata.first,
                    url = url,
                    isVideo = false,
                    isRss = true,
                    source = "RSS",
                    isFollowed = _subscriptions.value.any { it.url == url }
                ))
            } else {
                android.widget.Toast.makeText(context, "URL is not a valid podcast feed", android.widget.Toast.LENGTH_SHORT).show()
            }
            _isLoading.value = false
        }
    }

    fun loadChannelVideos(context: Context, subscription: Subscription) {
        _searchResults.value = emptyList()
        _currentSubscription.value = subscription
        currentSearchQuery = null

        val cached = channelCache[subscription.url]
        if (cached != null) {
            _searchResults.value = cached.map { syncResultStatus(context, it) }
            _hasMore.value = if (subscription.type == "RSS") (rssOriginalItems[subscription.url]?.size ?: 0) > cached.size else currentNextPage != null
            refreshDownloadStatus(context)
            return
        }

        if (!isNetworkOperationAllowed(context)) {
            android.widget.Toast.makeText(context, "Mobile data not allowed. Use Wi-Fi.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (subscription.type == "RSS") {
            loadRssEpisodes(context, subscription.url)
        } else {
            loadYoutubeChannelInitial(context, subscription)
        }
    }

    private fun loadYoutubeChannelInitial(context: Context, subscription: Subscription) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val results = YouTubeManager.fetchChannelItems(subscription.url, _showYoutubeLive.value, subscription.name)
                val display = results.take(10).map { syncResultStatus(context, it) }
                _searchResults.value = display
                channelCache[subscription.url] = results // Cache full list for later mapping
                StorageManager.saveChannelCache(context, channelCache)
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    fun updateAllSubscriptions(context: Context) {
        if (!isNetworkOperationAllowed(context)) return
        viewModelScope.launch {
            _isLoading.value = true
            val currentSubs = _subscriptions.value
            withContext(Dispatchers.IO) {
                currentSubs.forEach { sub ->
                    try {
                        val results = if (sub.type == "RSS") {
                            RssParser.fetchRssItems(sub.url) { name -> DownloadManagerHelper.isFileFullyDownloaded(context, name) }.take(10)
                        } else {
                            YouTubeManager.fetchChannelItems(sub.url, _showYoutubeLive.value, sub.name).take(10)
                        }
                        if (results.isNotEmpty()) {
                            channelCache[sub.url] = results
                            val latestUrl = results.first().url
                            if (latestUrl != sub.latestItemUrl) {
                                val current = _subscriptions.value.toMutableList()
                                val index = current.indexOfFirst { it.url == sub.url }
                                if (index != -1) {
                                    current[index] = current[index].copy(latestItemUrl = latestUrl, lastUpdated = System.currentTimeMillis())
                                    _subscriptions.value = current
                                }
                            }
                        }
                    } catch (e: Exception) { }
                }
                StorageManager.saveChannelCache(context, channelCache)
                StorageManager.saveSubscriptions(context, _subscriptions.value)
            }
            _isLoading.value = false
        }
    }

    fun loadMore(context: Context) {
        if (!isNetworkOperationAllowed(context)) {
            android.widget.Toast.makeText(context, "Mobile data not allowed. Use Wi-Fi.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val sub = _currentSubscription.value ?: return
        val currentResults = _searchResults.value
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (sub.type == "RSS") {
                    val fullList = rssOriginalItems[sub.url] ?: emptyList()
                    val nextTen = fullList.drop(currentResults.size).take(10).map { syncResultStatus(context, it.first) }
                    if (nextTen.isNotEmpty()) {
                        val newList = currentResults + nextTen
                        _searchResults.value = newList
                        channelCache[sub.url] = newList
                        _hasMore.value = fullList.size > newList.size
                    } else { _hasMore.value = false }
                } else {
                    val page = currentNextPage ?: return@launch
                    val moreInfo = ChannelTabInfo.getMoreItems(ServiceList.YouTube, currentListLinkHandler!!, page)
                    currentNextPage = moreInfo.nextPage
                    _hasMore.value = moreInfo.nextPage != null
                    val moreResults = moreInfo.items.map { item ->
                        val name = item.name ?: "Unknown"
                        val pubDate = (item as? StreamInfoItem)?.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
                        val res = SearchResult(name = name, url = item.url ?: "", isVideo = item is StreamInfoItem, uploaderName = (item as? StreamInfoItem)?.uploaderName ?: sub.name, duration = (item as? StreamInfoItem)?.duration ?: -1L, pubDate = pubDate)
                        syncResultStatus(context, res)
                    }
                    val newList = (currentResults + moreResults).sortedByDescending { it.pubDate }
                    _searchResults.value = newList
                    channelCache[sub.url] = newList
                }
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    private fun loadRssEpisodes(context: Context, url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val results = RssParser.fetchRssItems(url) { name -> DownloadManagerHelper.isFileFullyDownloaded(context, name) }
                rssOriginalItems[url] = results.map { Triple(it, it.pubDate, it.url) }
                val displayList = results.take(10).map { syncResultStatus(context, it) }
                _searchResults.value = displayList
                channelCache[url] = results
                StorageManager.saveChannelCache(context, channelCache)
                _hasMore.value = results.size > 10
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    fun refreshDownloadStatus(context: Context) {
        _searchResults.value = _searchResults.value.map { syncResultStatus(context, it) }
        _downloadedFiles.value = _downloadedFiles.value.map { syncResultStatus(context, it) }
    }

    fun startDownload(context: Context, result: SearchResult) {
        if (!isNetworkOperationAllowed(context)) {
            android.widget.Toast.makeText(context, "Mobile data not allowed. Use Wi-Fi.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val audioUrl = getAudioUrl(context, result)
            if (audioUrl != null) {
                val sanitizedName = DownloadManagerHelper.sanitizeFilename(result.name)
                StorageManager.saveDownloadMetadata(context, sanitizedName, result.uploaderName, result.isRss)
                
                val downloadId = DownloadManagerHelper.enqueueDownload(context, result, audioUrl, _allowMobileData.value)
                
                _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true, downloadId = downloadId) else it }
                refreshDownloadedFiles(context)
                startPollingDownloads(context)
                android.widget.Toast.makeText(context, "Download started", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, "Failed to get download link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPollingDownloads(context: Context) {
        if (isPollingDownloads) return
        isPollingDownloads = true
        viewModelScope.launch {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isPollingDownloads) {
                var activeDownloads = 0
                val currentResults = _searchResults.value
                val newResults = currentResults.map { result ->
                    if (result.isDownloading && result.downloadId != null) {
                        activeDownloads++
                        val query = DownloadManager.Query().setFilterById(result.downloadId)
                        val cursor = try { downloadManager.query(query) } catch (e: Exception) { null }
                        if (cursor != null && cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            
                            if (statusIndex != -1 && downloadedIndex != -1 && totalIndex != -1) {
                                val status = cursor.getInt(statusIndex)
                                val downloaded = cursor.getLong(downloadedIndex)
                                val total = cursor.getLong(totalIndex)
                                cursor.close()
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    result.copy(isDownloading = false, isDownloaded = true, downloadProgress = 100)
                                } else if (status == DownloadManager.STATUS_FAILED) {
                                    result.copy(isDownloading = false, downloadProgress = -1)
                                } else {
                                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                                    val sizeText = if (total > 0) StorageManager.formatFileSize(total) else null
                                    result.copy(downloadProgress = progress, totalSize = sizeText)
                                }
                            } else { cursor.close(); result }
                        } else { cursor?.close(); result }
                    } else result
                }
                _searchResults.value = newResults
                refreshDownloadedFiles(context)
                if (activeDownloads == 0) { isPollingDownloads = false }
                delay(1000)
            }
        }
    }

    fun deleteFile(context: Context, name: String) {
        DownloadManagerHelper.deleteFile(context, name)
        StorageManager.deleteDownloadMetadata(context, DownloadManagerHelper.sanitizeFilename(name))
        refreshDownloadedFiles(context)
    }

    fun cancelDownload(context: Context, downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.remove(downloadId)
        _searchResults.value = _searchResults.value.map { if (it.downloadId == downloadId) it.copy(isDownloading = false, downloadProgress = -1) else it }
        refreshDownloadedFiles(context)
    }

    fun getLocalUri(context: Context, name: String): Uri? = DownloadManagerHelper.getLocalUri(context, name)

    suspend fun getAudioUrl(context: Context, result: SearchResult): String? {
        if (!isNetworkOperationAllowed(context)) return null
        if (result.isRss) return result.url
        return withContext(Dispatchers.IO) {
            try {
                val streamInfo = YouTubeManager.getStreamInfo(result.url)
                val audioStreams = streamInfo.audioStreams ?: return@withContext null
                
                // 1. Prioritize ORIGINAL audio tracks
                val originalStreams = audioStreams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
                
                // 2. If no ORIGINAL, try to find English tracks
                val englishStreams = audioStreams.filter { it.audioLocale?.language == "en" }
                
                // 3. Candidates: Prefer Original, then English, then anything NOT dubbed
                val candidates = when {
                    originalStreams.isNotEmpty() -> originalStreams
                    englishStreams.isNotEmpty() -> englishStreams
                    else -> audioStreams.filter { it.audioTrackType != AudioTrackType.DUBBED }
                }.ifEmpty { audioStreams }
                
                // 4. Within candidates, prioritize M4A format
                val m4aStreams = candidates.filter { it.format?.suffix == "m4a" }
                val targetBitrate = 160000 // 160 kbps balance
                
                val bestStream = if (m4aStreams.isNotEmpty()) {
                    m4aStreams.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                } else {
                    candidates.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                }
                
                bestStream?.content
            } catch (e: Exception) { e.printStackTrace() ; null }
        }
    }

    fun updatePlaybackInfo(result: SearchResult, isPlaying: Boolean, durationMs: Long = -1) {
        val durationText = if (durationMs > 0) formatDuration(durationMs)
        else if (result.duration > 0) formatDuration(result.duration * 1000)
        else null
        _currentPlayback.value = PlaybackInfo(result.name, result.uploaderName, durationText, isPlaying, result)
        
        if (result.isDownloaded) {
            _lastPlayedFile.value = result
        }
    }

    fun stopPlayback() { _currentPlayback.value = null }
    fun setPlaying(isPlaying: Boolean) { _currentPlayback.value = _currentPlayback.value?.copy(isPlaying = isPlaying) }
    
    fun updatePlaybackState(position: Long, duration: Long) {
        _playbackPosition.value = position
        _playbackDuration.value = duration
    }

    fun saveCurrentPosition(context: Context, explicitPosition: Long? = null) {
        val current = _currentPlayback.value?.originalResult ?: return
        val pos = explicitPosition ?: _playbackPosition.value
        if (pos > 0) {
            StorageManager.savePlaybackPosition(context, current.name, pos)
        }
    }

    fun getSavedPosition(context: Context, name: String): Long {
        return StorageManager.loadPlaybackPosition(context, name)
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
    }

    fun updateDuration(durationMs: Long) { 
        _playbackDuration.value = durationMs
        _currentPlayback.value = _currentPlayback.value?.copy(durationText = formatDuration(durationMs)) 
    }

    fun restorePlaybackInfo(title: String, uploader: String?, url: String, isPlaying: Boolean, duration: Long) {
        val result = SearchResult(name = title, url = url, uploaderName = uploader, isVideo = true)
        _currentPlayback.value = PlaybackInfo(title, uploader, formatDuration(duration), isPlaying, result)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

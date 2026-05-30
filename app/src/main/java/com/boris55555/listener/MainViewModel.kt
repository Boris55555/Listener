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

    private val _loadingUrl = MutableStateFlow<String?>(null)
    val loadingUrl: StateFlow<String?> = _loadingUrl

    private val _preparingDownloadUrl = MutableStateFlow<String?>(null)
    val preparingDownloadUrl: StateFlow<String?> = _preparingDownloadUrl

    private val _backoffRemaining = MutableStateFlow<Map<String, Float>>(emptyMap())
    val backoffRemaining: StateFlow<Map<String, Float>> = _backoffRemaining

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

    private val _isYoutubeEnabled = MutableStateFlow(false)
    val isYoutubeEnabled: StateFlow<Boolean> = _isYoutubeEnabled

    private val _isLbryEnabled = MutableStateFlow(false)
    val isLbryEnabled: StateFlow<Boolean> = _isLbryEnabled

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration

    private val _sponsorSegments = MutableStateFlow<List<SponsorBlockManager.Segment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorBlockManager.Segment>> = _sponsorSegments

    private var lastSkippedSegment: SponsorBlockManager.Segment? = null

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
            // Strict sorting by publication date first, then name to keep it stable
            _subscriptions.value = subs.sortedWith(compareByDescending<Subscription> { it.latestItemPubDate }.thenBy { it.name })
            
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

            val ytEnabled = prefs.getBoolean("youtube_enabled", false)
            _isYoutubeEnabled.value = ytEnabled

            val lbryEnabled = prefs.getBoolean("lbry_enabled", false)
            _isLbryEnabled.value = lbryEnabled
            
            refreshDownloadedFiles(context)
            
            // Migrate YouTube subscriptions to include channelId for RSS
            migrateYoutubeSubscriptions(context)

            if (setting != RefreshSetting.MANUAL) {
                updateAllSubscriptions(context)
            }
        }
    }

    private fun migrateYoutubeSubscriptions(context: Context) {
        viewModelScope.launch {
            val current = _subscriptions.value
            val needsMigration = current.filter { it.type == "YOUTUBE" && it.youtubeChannelId == null }
            if (needsMigration.isNotEmpty()) {
                val updated = current.toMutableList()
                needsMigration.forEach { sub ->
                    val id = YouTubeRssManager.getChannelId(sub.url)
                    if (id != null) {
                        val idx = updated.indexOfFirst { it.url == sub.url }
                        if (idx != -1) {
                            updated[idx] = updated[idx].copy(youtubeChannelId = id)
                        }
                    }
                }
                StorageManager.saveSubscriptions(context, updated)
                _subscriptions.value = updated.sortedWith(compareByDescending<Subscription> { it.latestItemPubDate }.thenBy { it.name })
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

    fun setYoutubeEnabled(context: Context, enabled: Boolean) {
        _isYoutubeEnabled.value = enabled
        context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("youtube_enabled", enabled)
            .apply()
        
        if (!enabled && sourceFilter == SourceFilter.YOUTUBE) {
            sourceFilter = SourceFilter.ALL
        }
    }

    fun setLbryEnabled(context: Context, enabled: Boolean) {
        _isLbryEnabled.value = enabled
        context.getSharedPreferences("listener_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("lbry_enabled", enabled)
            .apply()
        
        if (!enabled && sourceFilter == SourceFilter.LBRY) {
            sourceFilter = SourceFilter.ALL
        }
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
        viewModelScope.launch {
            refreshDownloadedFilesInternal(context)
        }
    }

    private suspend fun refreshDownloadedFilesInternal(context: Context) = withContext(Dispatchers.IO) {
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

    fun follow(context: Context, name: String, url: String, type: String = "YOUTUBE") {
        viewModelScope.launch {
            val current = _subscriptions.value.toMutableList()
            if (current.none { it.url == url }) {
                var channelId: String? = null
                if (type == "YOUTUBE") {
                    channelId = YouTubeRssManager.getChannelId(url)
                }
                current.add(Subscription(name, url, type, System.currentTimeMillis(), youtubeChannelId = channelId))
                val sorted = current.sortedWith(compareByDescending<Subscription> { it.latestItemPubDate }.thenBy { it.name })
                StorageManager.saveSubscriptions(context, sorted)
                _subscriptions.value = sorted
                refreshFollowStatus()
                android.widget.Toast.makeText(context, "Subscribed: $name", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun unfollow(context: Context, url: String) {
        val current = _subscriptions.value.toMutableList()
        val sub = current.find { it.url == url }
        current.removeAll { it.url == url }
        val sorted = current.sortedWith(compareByDescending<Subscription> { it.latestItemPubDate }.thenBy { it.name })
        StorageManager.saveSubscriptions(context, sorted)
        _subscriptions.value = sorted
        refreshFollowStatus()
        sub?.let {
            android.widget.Toast.makeText(context, "Unsubscribed: ${it.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun exportSubscriptions(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = StorageManager.exportSubscriptionsJson(context)
                context.contentResolver.openOutputStream(uri)?.use { 
                    it.write(json.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Subscriptions exported", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importSubscriptions(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { 
                    it.readBytes().decodeToString()
                }
                if (json != null) {
                    val imported = StorageManager.importSubscriptionsJson(json)
                    val current = _subscriptions.value.toMutableList()
                    var addedCount = 0
                    imported.forEach { sub ->
                        if (current.none { it.url == sub.url }) {
                            current.add(sub)
                            addedCount++
                        }
                    }
                    if (addedCount > 0) {
                        StorageManager.saveSubscriptions(context, current)
                        _subscriptions.value = current.sortedByDescending { it.lastUpdated }
                        refreshFollowStatus()
                    }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Imported $addedCount new subscriptions", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Import failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
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
        // Improved URL detection for RSS feeds
        val isUrl = trimmedQuery.startsWith("http://") || 
                    trimmedQuery.startsWith("https://") || 
                    (trimmedQuery.contains("/") && trimmedQuery.contains(".") && !trimmedQuery.contains(" "))
        
        if (isUrl) {
            val finalUrl = if (!trimmedQuery.startsWith("http")) "https://$trimmedQuery" else trimmedQuery
            showRssPreview(context, finalUrl)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _hasMore.value = false
            currentNextPage = null
            currentSearchQuery = trimmedQuery
            _currentSubscription.value = null
            
            try {
                val youtubeResults = if (_isYoutubeEnabled.value && (sourceFilter == SourceFilter.ALL || sourceFilter == SourceFilter.YOUTUBE)) {
                    val container = YouTubeManager.searchYouTube(trimmedQuery, contentFilter)
                    currentNextPage = container.nextPage
                    _hasMore.value = container.nextPage != null
                    container.results
                } else emptyList()

                val podcastResults = if (sourceFilter == SourceFilter.ALL || sourceFilter == SourceFilter.PODCASTS) {
                    RssParser.searchPodcasts(trimmedQuery)
                } else emptyList()

                val lbryResults = if (_isLbryEnabled.value && (sourceFilter == SourceFilter.ALL || sourceFilter == SourceFilter.LBRY)) {
                    LbryManager.searchLbry(trimmedQuery, contentFilter)
                } else emptyList()

                val results = (youtubeResults + podcastResults + lbryResults).map { res ->
                    syncResultStatus(context, res)
                }
                _searchResults.value = results
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    private suspend fun syncResultStatus(context: Context, res: SearchResult): SearchResult = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fullyDownloaded = DownloadManagerHelper.isFileFullyDownloaded(context, res.name)
        val activeId = DownloadManagerHelper.getActiveDownloadId(dm, res.name)
        
        res.copy(
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
        currentNextPage = null
        currentListLinkHandler = null

        viewModelScope.launch {
            _isLoading.value = true
            val cached = channelCache[subscription.url]
            if (cached != null && cached.isNotEmpty()) {
                val display = cached.take(5)
                val synced = withContext(Dispatchers.IO) {
                    display.map { syncResultStatus(context, it) }
                }
                _searchResults.value = synced
                _hasMore.value = cached.size > 5 || subscription.type == "YOUTUBE" || subscription.type == "LBRY"
                
                // If it's YouTube/LBRY, we need to fetch the channel info anyway to get pagination headers
                if (subscription.type == "YOUTUBE" || subscription.type == "LBRY") {
                    viewModelScope.launch {
                        try {
                            val container = if (subscription.type == "YOUTUBE") {
                                YouTubeManager.fetchChannelInitial(subscription.url, _showYoutubeLive.value, subscription.name)
                            } else {
                                LbryManager.fetchChannelInitial(subscription.url, subscription.name)
                            }
                            currentListLinkHandler = container.linkHandler
                            currentNextPage = container.nextPage
                            
                            // Merge background fetch with current results to ensure cache is fresh
                            val merged = (cached + container.results).distinctBy { it.url }.sortedByDescending { it.pubDate }
                            channelCache[subscription.url] = merged
                            
                            _hasMore.value = merged.size > 5 || container.nextPage != null
                        } catch (e: Exception) {}
                    }
                }
                
                _isLoading.value = false
                return@launch
            }

            if (!isNetworkOperationAllowed(context)) {
                android.widget.Toast.makeText(context, "Mobile data not allowed. Use Wi-Fi.", android.widget.Toast.LENGTH_SHORT).show()
                _isLoading.value = false
                return@launch
            }

            if (subscription.type == "RSS") {
                loadRssEpisodes(context, subscription.url)
            } else if (subscription.type == "LBRY") {
                loadLbryChannelInitial(context, subscription)
            } else {
                loadYoutubeChannelInitial(context, subscription)
            }
        }
    }

    private fun loadLbryChannelInitial(context: Context, subscription: Subscription) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val container = LbryManager.fetchChannelInitial(subscription.url, subscription.name)
                val results = container.results
                currentNextPage = container.nextPage
                val display = results.take(5).map { syncResultStatus(context, it) }
                _searchResults.value = display
                channelCache[subscription.url] = results 
                _hasMore.value = results.size > 5 || container.nextPage != null
                StorageManager.saveChannelCache(context, channelCache)
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    private fun loadYoutubeChannelInitial(context: Context, subscription: Subscription) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Try RSS first as it's much faster
                val channelId = subscription.youtubeChannelId ?: YouTubeRssManager.getChannelId(subscription.url)
                if (channelId != null) {
                    val rssResults = YouTubeRssManager.fetchLatestVideos(channelId)
                    if (rssResults.isNotEmpty()) {
                        val display = rssResults.take(5).map { syncResultStatus(context, it) }
                        _searchResults.value = display
                        channelCache[subscription.url] = rssResults
                        _hasMore.value = rssResults.size > 5
                        // Also try to get next page from NewPipe in background to support "Load More"
                        viewModelScope.launch {
                            val container = YouTubeManager.fetchChannelInitial(subscription.url, _showYoutubeLive.value, subscription.name)
                            currentListLinkHandler = container.linkHandler
                            currentNextPage = container.nextPage
                            _hasMore.value = rssResults.size > 5 || container.nextPage != null
                        }
                        _isLoading.value = false
                        return@launch
                    }
                }

                // Fallback to NewPipeExtractor
                val container = YouTubeManager.fetchChannelInitial(subscription.url, _showYoutubeLive.value, subscription.name)
                currentListLinkHandler = container.linkHandler
                currentNextPage = container.nextPage
                _hasMore.value = container.nextPage != null
                
                val results = container.results
                val display = results.take(5).map { syncResultStatus(context, it) }
                _searchResults.value = display
                channelCache[subscription.url] = results 
                _hasMore.value = results.size > 5 || container.nextPage != null
                StorageManager.saveChannelCache(context, channelCache)
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    fun updateAllSubscriptions(context: Context) {
        if (!isNetworkOperationAllowed(context)) return
        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Refresh downloaded files and cleanup metadata for missing files
            withContext(Dispatchers.IO) {
                val metadata = StorageManager.loadAllDownloadMetadata(context)
                metadata.keys.forEach { fileName ->
                    if (!DownloadManagerHelper.isFileFullyDownloaded(context, fileName)) {
                        StorageManager.deleteDownloadMetadata(context, fileName)
                    }
                }
            }
            refreshDownloadedFilesInternal(context)
            
            // 2. Update status tags in existing results
            _searchResults.value = _searchResults.value.map { syncResultStatus(context, it) }
            
            // 3. Update all subscriptions for new content
            val currentSubsList = _subscriptions.value.toMutableList()
            withContext(Dispatchers.IO) {
                currentSubsList.forEachIndexed { index, sub ->
                    try {
                        val results = when (sub.type) {
                            "RSS" -> RssParser.fetchRssItems(sub.url) { name -> DownloadManagerHelper.isFileFullyDownloaded(context, name) }
                            "LBRY" -> LbryManager.fetchChannelInitial(sub.url, sub.name).results.take(10)
                            else -> YouTubeManager.fetchChannelInitial(sub.url, _showYoutubeLive.value, sub.name).results.take(10)
                        }
                        if (results.isNotEmpty()) {
                            channelCache[sub.url] = results
                            val latestItem = results.first()
                            val latestUrl = latestItem.url
                            if (latestUrl != sub.latestItemUrl) {
                                currentSubsList[index] = sub.copy(
                                    latestItemUrl = latestUrl, 
                                    lastUpdated = System.currentTimeMillis(),
                                    latestItemPubDate = latestItem.pubDate
                                )
                            }
                        }
                    } catch (e: Exception) { }
                }
                val sorted = currentSubsList.sortedWith(compareByDescending<Subscription> { it.latestItemPubDate }.thenBy { it.name })
                _subscriptions.value = sorted
                StorageManager.saveChannelCache(context, channelCache)
                StorageManager.saveSubscriptions(context, sorted)
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
        
        viewModelScope.launch {
            if (_isLoading.value) return@launch
            _isLoading.value = true
            
            try {
                val currentList = _searchResults.value
                val cachedFull = channelCache[sub.url] ?: emptyList()
                
                // 1. If we have more items already in the cache, show them first
                if (cachedFull.size > currentList.size) {
                    val nextBatch = cachedFull.drop(currentList.size).take(5).map { syncResultStatus(context, it) }
                    val newList = currentList + nextBatch
                    _searchResults.value = newList
                    // Button remains if there's more in cache OR more on network (for YouTube/LBRY)
                    _hasMore.value = newList.size < cachedFull.size || (sub.type != "RSS")
                    _isLoading.value = false
                    return@launch
                }

                // 2. Cache exhausted, try to fetch new page from network (YouTube/LBRY)
                if (sub.type == "RSS") {
                    _hasMore.value = false
                    _isLoading.value = false
                    return@launch
                }

                // If pagination data is missing, try to initialize it
                if (currentNextPage == null) {
                    val container = if (sub.type == "LBRY") {
                        LbryManager.fetchChannelInitial(sub.url, sub.name, 1)
                    } else {
                        YouTubeManager.fetchChannelInitial(sub.url, _showYoutubeLive.value, sub.name)
                    }
                    currentListLinkHandler = container.linkHandler
                    currentNextPage = container.nextPage
                    
                    // Update cache with results from page 1 (might have more than we had)
                    val merged = (cachedFull + container.results).distinctBy { it.url }.sortedByDescending { it.pubDate }
                    channelCache[sub.url] = merged
                    
                    // If we found more items on page 1 than we were showing, show them instead of fetching page 2 yet
                    if (merged.size > currentList.size) {
                        val nextBatch = merged.drop(currentList.size).take(5).map { syncResultStatus(context, it) }
                        val newList = currentList + nextBatch
                        _searchResults.value = newList
                        _hasMore.value = newList.size < merged.size || currentNextPage != null
                        _isLoading.value = false
                        return@launch
                    }
                }

                // Now actually fetch next page from network
                val page = currentNextPage
                if (page == null) {
                    _hasMore.value = false
                    _isLoading.value = false
                    return@launch
                }

                val moreResults = if (sub.type == "LBRY") {
                    val nextPageNum = (page.id.toIntOrNull() ?: 1) + 1
                    val container = LbryManager.fetchChannelInitial(sub.url, sub.name, nextPageNum)
                    currentNextPage = container.nextPage
                    container.results
                } else {
                    if (currentListLinkHandler == null) {
                        _hasMore.value = false
                        _isLoading.value = false
                        return@launch
                    }
                    val moreInfo = ChannelTabInfo.getMoreItems(ServiceList.YouTube, currentListLinkHandler!!, page)
                    currentNextPage = moreInfo.nextPage
                    moreInfo.items.filterIsInstance<StreamInfoItem>().map { item ->
                        val name = item.name ?: "Unknown"
                        val pubDate = item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
                        SearchResult(
                            name = name, 
                            url = item.url ?: "", 
                            isVideo = true, 
                            uploaderName = item.uploaderName ?: sub.name, 
                            duration = item.duration, 
                            pubDate = pubDate,
                            textualDate = item.textualUploadDate
                        )
                    }
                }

                if (moreResults.isEmpty()) {
                    _hasMore.value = false
                } else {
                    val updatedCache = (cachedFull + moreResults).distinctBy { it.url }.sortedByDescending { it.pubDate }
                    channelCache[sub.url] = updatedCache
                    
                    val nextBatch = moreResults.take(5).map { syncResultStatus(context, it) }
                    val newList = currentList + nextBatch
                    _searchResults.value = newList
                    _hasMore.value = true // We just got a new page, likely more exists
                }
            } catch (e: Exception) { 
                e.printStackTrace() 
                _hasMore.value = false
            } finally { 
                _isLoading.value = false 
            }
        }
    }

    private fun loadRssEpisodes(context: Context, url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val results = RssParser.fetchRssItems(url) { name -> DownloadManagerHelper.isFileFullyDownloaded(context, name) }
                rssOriginalItems[url] = results.map { Triple<SearchResult, Long, String?>(it, it.pubDate, it.url) }
                val displayList = results.take(5).map { syncResultStatus(context, it) }
                _searchResults.value = displayList
                channelCache[url] = results
                StorageManager.saveChannelCache(context, channelCache)
                _hasMore.value = results.size > 5
            } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false }
        }
    }

    fun refreshDownloadStatus(context: Context) {
        viewModelScope.launch {
            _searchResults.value = _searchResults.value.map { syncResultStatus(context, it) }
            _downloadedFiles.value = _downloadedFiles.value.map { syncResultStatus(context, it) }
        }
    }

    fun startDownload(context: Context, result: SearchResult) {
        if (!isNetworkOperationAllowed(context)) {
            android.widget.Toast.makeText(context, "Mobile data not allowed. Use Wi-Fi.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        _loadingUrl.value = null // Safety: ensure Listen button doesn't show loading
        _preparingDownloadUrl.value = result.url
        viewModelScope.launch {
            val sanitizedName = DownloadManagerHelper.sanitizeFilename(result.name)
            StorageManager.saveDownloadMetadata(context, sanitizedName, result)

            val isYt = result.source == "YOUTUBE" || (result.url.contains("youtube.com") || result.url.contains("youtu.be"))
            val isLbry = result.source == "LBRY" || result.url.contains("odysee.com") || result.url.contains("lbry.tv")

            if (isLbry) {
                // Resolve the best available stream URL
                val streamUrl = getAudioUrl(context, result)
                
                if (streamUrl != null) {
                    if (streamUrl.contains(".m3u8") || streamUrl.contains("/master.m3u8")) {
                        // For HLS streams, DownloadManager is useless (0.5kb issue).
                        // We MUST use YoutubeDLWorker which handles HLS correctly.
                        val data = workDataOf(
                            "url" to streamUrl,
                            "name" to result.name
                        )
                        val request = OneTimeWorkRequestBuilder<YoutubeDLWorker>()
                            .setInputData(data)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build()
                        WorkManager.getInstance(context).enqueueUniqueWork(
                            "ytdl_$sanitizedName",
                            ExistingWorkPolicy.REPLACE,
                            request
                        )
                        _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true) else it }
                        android.widget.Toast.makeText(context, "LBRY HLS Download started", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        // Direct video/audio file. Use DownloadManager + ConversionWorker model.
                        val downloadId = DownloadManagerHelper.enqueueDownload(context, result, streamUrl, _allowMobileData.value)
                        _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true, downloadId = downloadId) else it }
                        android.widget.Toast.makeText(context, "LBRY Download started", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.widget.Toast.makeText(context, "Failed to resolve LBRY link", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                _preparingDownloadUrl.value = null
                refreshDownloadedFiles(context)
                startPollingDownloads(context)
                return@launch
            }

            if (isYt) {
                // Use yt-dlp for YouTube for best reliability and SponsorBlock removal
                val audioUrl = getAudioUrl(context, result)
                
                if (audioUrl != null && audioUrl.contains("googlevideo.com")) {
                    val downloadId = DownloadManagerHelper.enqueueDownload(context, result, audioUrl, _allowMobileData.value)
                    _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true, downloadId = downloadId) else it }
                    android.widget.Toast.makeText(context, "YouTube Download started", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val data = workDataOf(
                        "url" to result.url,
                        "name" to result.name
                    )
                    val request = OneTimeWorkRequestBuilder<YoutubeDLWorker>()
                        .setInputData(data)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "ytdl_$sanitizedName",
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                    _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true) else it }
                    android.widget.Toast.makeText(context, "YouTube Download started (yt-dlp)", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                _preparingDownloadUrl.value = null
                refreshDownloadedFiles(context)
                startPollingDownloads(context)
                return@launch
            }

            val audioUrl = getAudioUrl(context, result)
            if (audioUrl != null) {
                if (audioUrl.contains(".m3u8")) {
                    // Route HLS to FFmpeg worker (could also use yt-dlp here, but let's keep it for now or migrate later)
                    val data = workDataOf(
                        "url" to audioUrl,
                        "name" to result.name
                    )
                    val request = OneTimeWorkRequestBuilder<HlsDownloadWorker>()
                        .setInputData(data)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "hls_$sanitizedName",
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                    _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true) else it }
                    android.widget.Toast.makeText(context, "HLS Download started", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val downloadId = DownloadManagerHelper.enqueueDownload(context, result, audioUrl, _allowMobileData.value)
                    _searchResults.value = _searchResults.value.map { if (it.url == result.url) it.copy(isDownloading = true, downloadId = downloadId) else it }
                    android.widget.Toast.makeText(context, "Download started", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                _preparingDownloadUrl.value = null
                refreshDownloadedFiles(context)
                startPollingDownloads(context)
            } else {
                _preparingDownloadUrl.value = null
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
                var activeWork = 0
                val currentResults = _searchResults.value
                val newResults = currentResults.map { result ->
                    if (result.isDownloading) {
                        activeWork++
                        if (result.downloadId != null) {
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
                                        // Trigger conversion once download is complete
                                        val data = workDataOf("download_id" to result.downloadId)
                                        val convRequest = OneTimeWorkRequestBuilder<ConversionWorker>()
                                            .setInputData(data)
                                            .build()
                                        WorkManager.getInstance(context).enqueue(convRequest)

                                        // Mark as not downloading to stop polling this item
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
                        } else {
                            // Check WorkManager progress for yt-dlp tasks
                            val workManager = WorkManager.getInstance(context)
                            val sanitized = DownloadManagerHelper.sanitizeFilename(result.name)
                            val workInfos = try { workManager.getWorkInfosForUniqueWork("ytdl_$sanitized").get() } catch (e: Exception) { emptyList() }
                            val workInfo = workInfos.firstOrNull()
                            
                            if (workInfo != null) {
                                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                                    result.copy(isDownloading = false, isDownloaded = true, downloadProgress = 100)
                                } else if (workInfo.state == WorkInfo.State.FAILED) {
                                    result.copy(isDownloading = false, downloadProgress = -1)
                                } else {
                                    val progress = workInfo.progress.getInt("progress", 0)
                                    result.copy(downloadProgress = progress)
                                }
                            } else {
                                // Fallback: Check if it's still converting or downloading via other WorkManager tasks
                                val isStillWorking = !DownloadManagerHelper.isFileFullyDownloaded(context, result.name)
                                if (!isStillWorking) {
                                    result.copy(isDownloading = false, isDownloaded = true, downloadProgress = 100)
                                } else {
                                    result
                                }
                            }
                        }
                    } else result
                }
                _searchResults.value = newResults
                refreshDownloadedFiles(context)
                if (activeWork == 0) { isPollingDownloads = false }
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
        
        // Also cancel any WorkManager tasks with this ID (if it's a YoutubeDL/HLS task)
        // Note: For WorkManager tasks, we usually use the unique name we assigned during enqueue
        // But for generic cleanup, we'll ensure search results update immediately.
        
        _searchResults.value = _searchResults.value.map { if (it.downloadId == downloadId) it.copy(isDownloading = false, downloadProgress = -1) else it }
        refreshDownloadedFiles(context)
    }

    fun cancelWorkManagerDownload(context: Context, name: String) {
        val workManager = WorkManager.getInstance(context)
        val sanitized = DownloadManagerHelper.sanitizeFilename(name)
        
        // Cancel both possible unique work names
        workManager.cancelUniqueWork("ytdl_$sanitized")
        workManager.cancelUniqueWork("hls_$sanitized")
        
        _searchResults.value = _searchResults.value.map { if (it.name == name) it.copy(isDownloading = false, downloadProgress = -1) else it }
        
        // Mark conversion as finished/stopped in prefs to clear UI
        DownloadManagerHelper.markConverting(context, sanitized, false)
        refreshDownloadedFiles(context)
    }

    fun getLocalUri(context: Context, name: String): Uri? = DownloadManagerHelper.getLocalUri(context, name)

    suspend fun getAudioUrl(context: Context, result: SearchResult): String? {
        if (!isNetworkOperationAllowed(context)) return null
        if (result.isRss) return result.url
        if (result.source == "LBRY") {
            val url = LbryManager.getStreamUrl(result.url, result.lbryId, result.lbryName)
            return url
        }
        
        val isYoutube = result.source == "YOUTUBE" || result.url.contains("youtube.com") || result.url.contains("youtu.be")
        
        // Use a retry mechanism for YouTube as extraction can be flaky
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                if (isYoutube && attempt == 1) {
                    // Pre-emptive backoff as requested by user to simulate FreeTube's SABR backoff
                    // This helps avoid immediate 429s and throttling. Increased to 5s for reliability.
                    val backoffSeconds = 5.0f
                    val steps = 50
                    for (i in 1..steps) { 
                        val remaining = backoffSeconds - (i * 0.1f)
                        _backoffRemaining.value = _backoffRemaining.value + (result.url to remaining)
                        delay(100)
                    }
                    _backoffRemaining.value = _backoffRemaining.value - result.url
                } else if (attempt > 1) {
                    android.util.Log.d("MainViewModel", "Retrying audio URL fetch, attempt $attempt")
                    delay(1500L * attempt) // Exponential backoff between retries
                }
                
                val url = withContext(Dispatchers.IO) {
                    // Primary method: NewPipeExtractor
                    try {
                        val streamInfo = YouTubeManager.getStreamInfo(result.url)
                        val audioStreams = streamInfo.audioStreams ?: emptyList()
                        val videoStreams = streamInfo.videoStreams ?: emptyList()
                        
                        // Combine audio and video streams (ExoPlayer can play video as audio)
                        val audioPool = audioStreams.filterNotNull()
                        val videoPool = videoStreams.filterNotNull()
                        
                        if (audioPool.isEmpty() && videoPool.isEmpty()) throw Exception("No playable streams found via NewPipe")
                        
                        // Select best audio stream if available, fallback to video
                        val bestStream = if (audioPool.isNotEmpty()) {
                            val original = audioPool.filter { it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL }.ifEmpty { audioPool }
                            val opus = original.filter { it.format?.suffix == "webm" || it.format?.name?.contains("opus", true) == true }
                            val m4a = original.filter { it.format?.suffix == "m4a" }
                            
                            val targetBitrate = 160000 
                            when {
                                opus.isNotEmpty() -> opus.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                                m4a.isNotEmpty() -> m4a.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                                else -> original.minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                            }
                        } else {
                            // Fallback to video stream
                            videoPool.minByOrNull { 
                                // Prefer lower resolutions for audio-only to save bandwidth
                                when (it.resolution) {
                                    "144p" -> 1
                                    "240p" -> 2
                                    "360p" -> 3
                                    else -> 10
                                }
                            }
                        }
                        
                        bestStream?.content ?: throw Exception("No suitable stream content found")
                    } catch (e: Exception) {
                        android.util.Log.w("MainViewModel", "NewPipe extraction failed, trying yt-dlp fallback: ${e.message}")
                        
                        // Fallback method: yt-dlp
                        try {
                            val ytdl = com.yausername.youtubedl_android.YoutubeDL.getInstance()
                            
                            // More robust initialization with logging
                            try {
                                val initResult = ytdl.init(context.applicationContext)
                                android.util.Log.d("MainViewModel", "yt-dlp init result: $initResult")
                            } catch (initEx: Exception) {
                                if (initEx.message?.contains("already initialized", true) == true) {
                                    // OK
                                } else {
                                    android.util.Log.e("MainViewModel", "yt-dlp init failed: ${initEx.message}")
                                }
                            }
                            
                            val request = com.yausername.youtubedl_android.YoutubeDLRequest(result.url)
                            // Use mobile-friendly options for yt-dlp fallback
                            // -f bestaudio/best tries to get audio only, fallback to video if needed
                            request.addOption("-f", "bestaudio/best")
                            request.addOption("-g") // Get URL only
                            request.addOption("--no-playlist")
                            request.addOption("--user-agent", ListenerApp.USER_AGENT)
                            // Add basic headers to yt-dlp too
                            request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
                            
                            val response = ytdl.execute(request)
                            if (response.exitCode == 0) {
                                // Take the first URL from output (yt-dlp -g can return multiple if requested)
                                val extractedUrl = response.out.trim().lines().firstOrNull { it.startsWith("http") }
                                android.util.Log.d("MainViewModel", "yt-dlp successfully extracted URL")
                                extractedUrl
                            } else {
                                android.util.Log.e("MainViewModel", "yt-dlp returned exit code ${response.exitCode}. Output: ${response.out}")
                                null
                            }
                        } catch (ex: Exception) {
                            android.util.Log.e("MainViewModel", "yt-dlp execution failed: ${ex.message}")
                            null
                        }
                    }
                }
                
                if (url != null) return url
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("MainViewModel", "Attempt $attempt failed to fetch audio URL: ${e.message}")
                if (e is org.schabi.newpipe.extractor.exceptions.ReCaptchaException) {
                    // Hit a 429, mandatory long backoff
                    val waitTime = 5
                    for (i in waitTime downTo 1) {
                        _backoffRemaining.value = _backoffRemaining.value + (result.url to i.toFloat())
                        delay(1000)
                    }
                    _backoffRemaining.value = _backoffRemaining.value - result.url
                }
            }
        }
        
        lastError?.printStackTrace()
        return null
    }

    fun updatePlaybackInfo(result: SearchResult, isPlaying: Boolean, durationMs: Long = -1) {
        val durationText = if (durationMs > 0) formatDuration(durationMs)
        else if (result.duration > 0) formatDuration(result.duration * 1000)
        else null
        _currentPlayback.value = PlaybackInfo(result.name, result.uploaderName, durationText, isPlaying, result)
        
        if (result.isDownloaded) {
            _lastPlayedFile.value = result
        }

        // Fetch SponsorBlock segments for YouTube
        if (result.source == "YOUTUBE" || (result.url.contains("youtube.com") || result.url.contains("youtu.be"))) {
            val videoId = extractYoutubeId(result.url)
            if (videoId != null) {
                viewModelScope.launch {
                    _sponsorSegments.value = SponsorBlockManager.fetchSegments(videoId)
                    lastSkippedSegment = null
                }
            } else {
                _sponsorSegments.value = emptyList()
            }
        } else {
            _sponsorSegments.value = emptyList()
        }
    }

    private fun extractYoutubeId(url: String): String? {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            else -> null
        }
    }

    fun stopPlayback() { _currentPlayback.value = null }
    fun setPlaying(isPlaying: Boolean) { _currentPlayback.value = _currentPlayback.value?.copy(isPlaying = isPlaying) }
    
    fun updatePlaybackState(position: Long, duration: Long) {
        _playbackPosition.value = position
        _playbackDuration.value = duration
    }

    fun checkSponsorSkip(position: Long): Long? {
        val segments = _sponsorSegments.value
        if (segments.isEmpty()) return null

        for (segment in segments) {
            // If current position is inside a segment
            if (position in segment.start until segment.end) {
                // Avoid infinite skip loops if user manually seeks into a segment
                if (lastSkippedSegment == segment) return null
                
                lastSkippedSegment = segment
                return segment.end
            }
        }
        return null
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

    suspend fun fetchFullDescription(result: SearchResult): String? {
        if (result.isRss) return result.description
        return YouTubeManager.fetchFullDescription(result.url)
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
    }

    fun updateDuration(durationMs: Long) { 
        _playbackDuration.value = durationMs
        _currentPlayback.value = _currentPlayback.value?.copy(durationText = formatDuration(durationMs)) 
        // If we were loading, stop now as it's ready
        _loadingUrl.value = null
    }

    fun setLoadingUrl(url: String?) {
        _loadingUrl.value = url
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

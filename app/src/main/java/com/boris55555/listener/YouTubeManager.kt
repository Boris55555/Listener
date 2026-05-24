package com.boris55555.listener

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeManager {
    suspend fun searchYouTube(query: String, contentFilter: ContentFilter): SearchResultContainer = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val filterList = when (contentFilter) {
            ContentFilter.CHANNELS -> listOf("channels")
            ContentFilter.TITLES -> listOf("videos")
            else -> emptyList()
        }
        
        val searchQH = service.searchQHFactory.fromQuery(query, filterList, "")
        val searchInfo = SearchInfo.getInfo(service, searchQH)
        
        val results = searchInfo.relatedItems.map { item ->
            val streamItem = item as? StreamInfoItem
            val pubDate = streamItem?.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
            SearchResult(
                name = item.name ?: "Unknown",
                url = item.url ?: "",
                isVideo = item is StreamInfoItem,
                uploaderName = streamItem?.uploaderName,
                uploaderUrl = streamItem?.uploaderUrl,
                duration = streamItem?.duration ?: -1L,
                description = streamItem?.shortDescription,
                source = "YOUTUBE",
                pubDate = pubDate,
                textualDate = streamItem?.textualUploadDate
            )
        }
        SearchResultContainer(results, searchInfo.nextPage, null)
    }

    suspend fun fetchChannelInitial(url: String, showLive: Boolean, subscriptionName: String): SearchResultContainer = withContext(Dispatchers.IO) {
        try {
            val service = ServiceList.YouTube
            val channelInfo = ChannelInfo.getInfo(service, url)
            
            val tabs = channelInfo.tabs
            if (tabs.isEmpty()) {
                return@withContext SearchResultContainer(emptyList(), null, null)
            }

            val allItems = mutableListOf<SearchResult>()
            var lastNextPage: Page? = null
            var firstTabHandler: ListLinkHandler? = null
            
            // NewPipe fix: YouTube sometimes returns different tab structures or empty first pages.
            // We iterate through all relevant tabs (Videos, Shorts, Streams) and also fallback 
            // to "Home" if nothing else is found.
            val tabsToFetch = tabs.filter { tab ->
                val tabUrl = tab.url ?: ""
                val url_ = tabUrl.lowercase()
                url_.endsWith("/videos") || 
                url_.endsWith("/shorts") || 
                url_.contains("/streams") ||
                tab.contentFilters.any { 
                    it.contains("video", true) || 
                    it.contains("short", true) || 
                    (showLive && it.contains("stream", true)) 
                }
            }.ifEmpty { listOf(tabs.first()) }

            tabsToFetch.forEach { tab ->
                try {
                    if (firstTabHandler == null) firstTabHandler = tab
                    val tabInfo = ChannelTabInfo.getInfo(service, tab)
                    
                    val relatedItems = if (tabInfo.relatedItems.isEmpty() && tabInfo.nextPage != null) {
                        // NewPipe logic: If items are empty but there's a nextPage, fetch it.
                        ChannelTabInfo.getMoreItems(service, tab, tabInfo.nextPage).items
                    } else {
                        tabInfo.relatedItems
                    }

                    if (lastNextPage == null) lastNextPage = tabInfo.nextPage
                    
                    val tabUrl = tab.url ?: ""
                    val isLiveTab = tabUrl.lowercase().contains("streams")
                    val now = System.currentTimeMillis()
                    
                    relatedItems.forEach { item ->
                        val streamItem = item as? StreamInfoItem ?: return@forEach
                        val name = item.name ?: "Unknown"
                        val pubDate = streamItem.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
                        
                        if (isLiveTab && pubDate > now + 3600000) return@forEach 
                        
                        allItems.add(SearchResult(
                            name = name,
                            url = item.url ?: "",
                            isVideo = true,
                            uploaderName = streamItem.uploaderName ?: subscriptionName,
                            uploaderUrl = url,
                            duration = streamItem.duration,
                            description = streamItem.shortDescription,
                            pubDate = pubDate,
                            isLive = isLiveTab,
                            textualDate = streamItem.textualUploadDate
                        ))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            // Remove duplicates (same URL might appear in different tabs)
            val uniqueItems = allItems.distinctBy { it.url }.sortedByDescending { it.pubDate }
            
            SearchResultContainer(uniqueItems, lastNextPage, firstTabHandler)
        } catch (e: Exception) {
            e.printStackTrace()
            SearchResultContainer(emptyList(), null, null)
        }
    }

    suspend fun getStreamInfo(url: String): StreamInfo = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        StreamInfo.getInfo(service, url)
    }

    suspend fun fetchFullDescription(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val info = getStreamInfo(url)
            info.description.content
        } catch (e: Exception) { null }
    }
}

data class SearchResultContainer(
    val results: List<SearchResult>,
    val nextPage: Page?,
    val linkHandler: ListLinkHandler?
)

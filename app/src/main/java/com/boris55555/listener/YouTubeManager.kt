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
            
            // Refined tab detection for latest YouTube layout
            val tabsToFetch = tabs.filter { tab ->
                val tabUrl = tab.url ?: ""
                val url_ = tabUrl.lowercase()
                url_.endsWith("/videos") || 
                url_.endsWith("/shorts") || 
                url_.contains("/streams") ||
                tab.contentFilters.any { it.contains("video", true) || it.contains("short", true) || (showLive && it.contains("stream", true)) }
            }.ifEmpty { listOf(tabs.first()) }

            tabsToFetch.forEach { tab ->
                try {
                    if (firstTabHandler == null) firstTabHandler = tab
                    val tabInfo = ChannelTabInfo.getInfo(service, tab)
                    if (lastNextPage == null) lastNextPage = tabInfo.nextPage
                    
                    val tabUrl = tab.url ?: ""
                    val isLiveTab = tabUrl.lowercase().contains("streams")
                    val now = System.currentTimeMillis()
                    
                    tabInfo.relatedItems.forEach { item ->
                        val streamItem = item as? StreamInfoItem
                        val name = item.name ?: "Unknown"
                        val pubDate = streamItem?.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
                        
                        if (isLiveTab && pubDate > now + 3600000) return@forEach 
                        
                        allItems.add(SearchResult(
                            name = name,
                            url = item.url ?: "",
                            isVideo = item is StreamInfoItem,
                            uploaderName = streamItem?.uploaderName ?: subscriptionName,
                            uploaderUrl = url,
                            duration = streamItem?.duration ?: -1L,
                            description = streamItem?.shortDescription,
                            pubDate = pubDate,
                            isLive = isLiveTab,
                            textualDate = streamItem?.textualUploadDate
                        ))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            SearchResultContainer(allItems.sortedByDescending { it.pubDate }, lastNextPage, firstTabHandler)
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

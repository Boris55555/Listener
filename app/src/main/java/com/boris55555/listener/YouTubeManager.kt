package com.boris55555.listener

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
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
            SearchResult(
                name = item.name ?: "Unknown",
                url = item.url ?: "",
                isVideo = item is StreamInfoItem,
                uploaderName = (item as? StreamInfoItem)?.uploaderName,
                duration = (item as? StreamInfoItem)?.duration ?: -1L,
                description = (item as? StreamInfoItem)?.shortDescription,
                source = "YOUTUBE"
            )
        }
        SearchResultContainer(results, searchInfo.nextPage)
    }

    suspend fun fetchChannelItems(url: String, showLive: Boolean, subscriptionName: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val service = ServiceList.YouTube
            val channelInfo = ChannelInfo.getInfo(service, url)
            
            val tabsToFetch = mutableListOf<String>()
            tabsToFetch.add("videos")
            if (showLive) {
                tabsToFetch.add("livestreams")
            }

            val allItems = mutableListOf<SearchResult>()
            
            channelInfo.tabs.filter { tab ->
                tabsToFetch.any { filter -> tab.contentFilters.getOrNull(0)?.equals(filter, ignoreCase = true) == true }
            }.forEach { tab ->
                val tabInfo = ChannelTabInfo.getInfo(service, tab)
                val isLiveTab = tab.contentFilters.getOrNull(0)?.equals("livestreams", ignoreCase = true) == true
                val now = System.currentTimeMillis()
                tabInfo.relatedItems.forEach { item ->
                    val name = item.name ?: "Unknown"
                    val pubDate = (item as? StreamInfoItem)?.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
                    
                    if (isLiveTab && pubDate > now) return@forEach
                    
                    allItems.add(SearchResult(
                        name = name,
                        url = item.url ?: "",
                        isVideo = item is StreamInfoItem,
                        uploaderName = (item as? StreamInfoItem)?.uploaderName ?: subscriptionName,
                        duration = (item as? StreamInfoItem)?.duration ?: -1L,
                        description = (item as? StreamInfoItem)?.shortDescription,
                        pubDate = pubDate,
                        isLive = isLiveTab
                    ))
                }
            }
            allItems.sortedByDescending { it.pubDate }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getStreamInfo(url: String) = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        StreamInfo.getInfo(service, url)
    }
}

data class SearchResultContainer(
    val results: List<SearchResult>,
    val nextPage: org.schabi.newpipe.extractor.Page?
)

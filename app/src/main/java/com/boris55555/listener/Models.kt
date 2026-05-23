package com.boris55555.listener

enum class ContentFilter { ALL, CHANNELS, TITLES }
enum class SourceFilter { ALL, YOUTUBE, PODCASTS, LBRY }

enum class RefreshSetting(val label: String, val hours: Int) {
    H1("1 hour", 1),
    H2("2 hours", 2),
    H3("3 hours", 3),
    H5("5 hours", 5),
    H8("8 hours", 8),
    H12("12 hours", 12),
    H24("24 hours", 24),
    OPEN_ONLY("Only when app opens", 0),
    MANUAL("Manually only", -1)
}

data class SearchResult(
    val name: String, 
    val url: String, 
    val isVideo: Boolean,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val duration: Long = -1,
    val description: String? = null,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = -1,
    val totalSize: String? = null,
    val downloadId: Long? = null,
    val isFollowed: Boolean = false,
    val isRss: Boolean = false,
    val isLive: Boolean = false,
    val source: String = "YOUTUBE",
    val pubDate: Long = 0,
    val textualDate: String? = null,
    val downloadDate: Long = 0,
    val mediaType: String? = null,
    val lbryId: String? = null,
    val lbryName: String? = null,
    val isConverting: Boolean = false
)

data class Subscription(
    val name: String, 
    val url: String, 
    val type: String = "YOUTUBE",
    val lastUpdated: Long = 0,
    val latestItemUrl: String? = null,
    val latestItemPubDate: Long = 0,
    val youtubeChannelId: String? = null
)

data class PlaybackInfo(
    val title: String,
    val subtitle: String?,
    val durationText: String?,
    val isPlaying: Boolean = false,
    val originalResult: SearchResult? = null
)

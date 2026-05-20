package com.boris55555.listener

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel,
    exoPlayer: Player?
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val currentSubscription by viewModel.currentSubscription.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var infoResult by remember { mutableStateOf<SearchResult?>(null) }

    BackHandler(onBack = onBack)

    if (infoResult != null) {
        InfoDialog(result = infoResult!!, onDismiss = { infoResult = null })
    }

    // Register receiver to refresh status when download finishes
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                viewModel.refreshDownloadStatus(context!!)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).background(Color.White)) {
        if (currentSubscription == null) {
            // Search Mode
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.border(1.dp, Color.Black),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).border(2.dp, Color.Black),
                    placeholder = { Text("Find listening...") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        cursorColor = Color.Black,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterButton("ALL", viewModel.contentFilter == ContentFilter.ALL) { viewModel.contentFilter = ContentFilter.ALL }
                FilterButton("CHANNELS", viewModel.contentFilter == ContentFilter.CHANNELS) { viewModel.contentFilter = ContentFilter.CHANNELS }
                FilterButton("TITLES", viewModel.contentFilter == ContentFilter.TITLES) { viewModel.contentFilter = ContentFilter.TITLES }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterButton("ALL SOURCE", viewModel.sourceFilter == SourceFilter.ALL) { viewModel.sourceFilter = SourceFilter.ALL }
                FilterButton("YOUTUBE", viewModel.sourceFilter == SourceFilter.YOUTUBE) { viewModel.sourceFilter = SourceFilter.YOUTUBE }
                FilterButton("PODCASTS", viewModel.sourceFilter == SourceFilter.PODCASTS) { viewModel.sourceFilter = SourceFilter.PODCASTS }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.search(context, query) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RectangleShape
            ) {
                Text("SEARCH", style = MaterialTheme.typography.labelLarge)
            }
        } else {
            // Subscription View Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.border(1.dp, Color.Black),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Takaisin")
                }
                Text(
                    text = currentSubscription?.name ?: "",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline
                    ),
                    textAlign = TextAlign.Center,
                    color = Color.Black
                )
                // Spacer for symmetry
                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                color = Color.Black,
                trackColor = Color.White
            )
        }

        val listState = rememberLazyListState()
        val showUpArrow by remember { derivedStateOf { listState.canScrollBackward } }
        val showDownArrow by remember { derivedStateOf { listState.canScrollForward } }

        // Up Arrow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clickable(enabled = showUpArrow) {
                    scope.launch {
                        val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        listState.animateScrollBy(-viewportHeight.toFloat() * 0.8f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (showUpArrow) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.Black)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            items(results) { result ->
                ResultItem(
                    result = result,
                    onPlay = {
                        scope.launch {
                            val localUri = viewModel.getLocalUri(context, result.name)
                            val mediaId = result.name // Consistent mediaId
                            
                            if (localUri != null) {
                                val mediaItem = MediaItem.Builder()
                                    .setUri(localUri)
                                    .setMediaId(mediaId)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(result.name)
                                            .setArtist(result.uploaderName)
                                            .build()
                                    )
                                    .build()
                                if (exoPlayer != null) {
                                    val savedPos = viewModel.getSavedPosition(context, mediaId)
                                    exoPlayer.setMediaItem(mediaItem, savedPos)
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                    viewModel.updatePlaybackInfo(result, true)
                                    Toast.makeText(context, "Offline: ${result.name}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val audioUrl = viewModel.getAudioUrl(context, result)
                                if (audioUrl != null) {
                                    val mediaItem = MediaItem.Builder()
                                    .setUri(audioUrl)
                                    .setMediaId(mediaId)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(result.name)
                                            .setArtist(result.uploaderName)
                                            .build()
                                    )
                                    .build()
                                if (exoPlayer != null) {
                                        val savedPos = viewModel.getSavedPosition(context, mediaId)
                                        exoPlayer.setMediaItem(mediaItem, savedPos)
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                        viewModel.updatePlaybackInfo(result, true)
                                        Toast.makeText(context, "Playing: ${result.name}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Initializing player...", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Failed to get audio track", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onDownload = {
                        viewModel.startDownload(context, result)
                    },
                    onDelete = {
                        viewModel.deleteFile(context, result.name)
                    },
                    onFollow = {
                        if (result.isFollowed) {
                            viewModel.unfollow(context, result.url)
                        } else {
                            viewModel.follow(context, result.name, result.url, if (result.isRss) "RSS" else "YOUTUBE")
                        }
                    },
                    onClick = {
                        if (!result.isVideo) {
                            if (result.isRss) {
                                viewModel.loadChannelVideos(context, Subscription(result.name, result.url, "RSS"))
                            } else {
                                viewModel.loadChannelVideos(context, Subscription(result.name, result.url, "YOUTUBE"))
                            }
                        }
                    },
                    onInfoClick = {
                        infoResult = result
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Black)
            }
            
            if (hasMore && !isLoading) {
                item {
                    Button(
                        onClick = { viewModel.loadMore(context) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        border = BorderStroke(1.dp, Color.Black),
                        shape = RectangleShape
                    ) {
                        Text("MORE...")
                    }
                }
            }
        }

        // Down Arrow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clickable(enabled = showDownArrow) {
                    scope.launch {
                        val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        listState.animateScrollBy(viewportHeight.toFloat() * 0.8f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (showDownArrow) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.Black)
            }
        }
    }
}

@Composable
fun FilterButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clickable { onClick() }
            .border(1.dp, Color.Black),
        color = if (selected) Color.Black else Color.White,
        contentColor = if (selected) Color.White else Color.Black,
        shape = RectangleShape
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

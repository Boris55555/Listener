package com.boris55555.listener

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.launch

fun playFile(context: Context, result: SearchResult, viewModel: MainViewModel, exoPlayer: Player?) {
    val localUri = viewModel.getLocalUri(context, result.name)
    if (localUri != null) {
        val mediaItem = MediaItem.Builder()
            .setUri(localUri)
            .setMediaId(result.name) // Use name as stable ID
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(result.name)
                    .setArtist(result.uploaderName)
                    .build()
            )
            .build()
        
        if (exoPlayer != null) {
            val savedPos = viewModel.getSavedPosition(context, result.name)
            // Use the overload that takes start position
            exoPlayer.setMediaItem(mediaItem, savedPos)
            exoPlayer.prepare()
            exoPlayer.play()
            viewModel.updatePlaybackInfo(result, true)
        } else {
            Toast.makeText(context, "Initializing player...", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun DownloadedScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel,
    exoPlayer: Player?
) {
    val results by viewModel.downloadedFiles.collectAsState()
    val lastPlayed by viewModel.lastPlayedFile.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var infoResult by remember { mutableStateOf<SearchResult?>(null) }

    BackHandler(onBack = onBack)
    
    if (infoResult != null) {
        InfoDialog(result = infoResult!!, onDismiss = { infoResult = null })
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDownloadedFiles(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("DOWNLOADS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (results.isEmpty()) {
            Text("No downloaded files.", color = Color.Black)
        } else {
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
                // LAST PLAYED section
                lastPlayed?.let { lp ->
                    // Check if it's still in results (not deleted) by matching NAME
                    if (results.any { it.name == lp.name }) {
                        item {
                            Text("LAST PLAYED", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                            Spacer(modifier = Modifier.height(8.dp))
                            ResultItem(
                                result = lp,
                                onPlay = {
                                    playFile(context, lp, viewModel, exoPlayer)
                                },
                                onDelete = {
                                    viewModel.deleteFile(context, lp.name)
                                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                },
                                onDownload = {},
                                onFollow = {},
                                onClick = {},
                                onInfoClick = { infoResult = lp }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), thickness = 2.dp, color = Color.Black)
                        }
                    }
                }

                items(results.filter { it.name != lastPlayed?.name }) { result ->
                    ResultItem(
                        result = result,
                        onPlay = {
                            if (!result.isDownloaded) {
                                Toast.makeText(context, "Download still in progress", Toast.LENGTH_SHORT).show()
                                return@ResultItem
                            }
                            playFile(context, result, viewModel, exoPlayer)
                        },
                        onDownload = {}, // Already handled by showing progress if isDownloading
                        onDelete = {
                            if (result.isDownloading && result.downloadId != null) {
                                viewModel.cancelDownload(context, result.downloadId)
                                Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.deleteFile(context, result.name)
                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFollow = {},
                        onClick = {},
                        onInfoClick = {
                            infoResult = result
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Black)
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
}

package com.boris55555.listener

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

enum class Screen {
    SUBSCRIPTIONS, DOWNLOADED, SETTINGS, SEARCH
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, viewModel: MainViewModel, exoPlayer: Player?) {
    val context = LocalContext.current
    val subscriptions by viewModel.subscriptions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentPlayback by viewModel.currentPlayback.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.SUBSCRIPTIONS) }

    LaunchedEffect(Unit) {
        viewModel.initSubscriptions(context)
    }
    
    // Register receiver to refresh status when download or conversion finishes
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                viewModel.refreshDownloadStatus(context!!)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        filter.addAction("com.boris55555.listener.CONVERSION_REFRESH")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Handle back button to close player if paused
    BackHandler(enabled = currentPlayback != null && !currentPlayback!!.isPlaying) {
        viewModel.stopPlayback()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        bottomBar = {
            Column {
                currentPlayback?.let { playback ->
                    exoPlayer?.let { player ->
                        PlayerBar(
                            playbackInfo = playback,
                            exoPlayer = player,
                            viewModel = viewModel,
                            onClose = {
                                viewModel.saveCurrentPosition(context, player.currentPosition)
                                player.stop()
                                player.clearMediaItems() // Force notification and launcher widget to dismiss
                                viewModel.stopPlayback()
                            }
                        )
                    }
                }
                
                HorizontalDivider(thickness = 1.dp, color = Color.Black)
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentScreen == Screen.SUBSCRIPTIONS,
                        onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("SUBSCRIPTIONS", fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.Black,
                            unselectedIconColor = Color.Black,
                            unselectedTextColor = Color.Black,
                            indicatorColor = Color.Black
                        )
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.DOWNLOADED,
                        onClick = { currentScreen = Screen.DOWNLOADED },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        label = { Text("DOWNLOADS", fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.Black,
                            unselectedIconColor = Color.Black,
                            unselectedTextColor = Color.Black,
                            indicatorColor = Color.Black
                        )
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.SETTINGS,
                        onClick = { currentScreen = Screen.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("SETTINGS", fontWeight = FontWeight.Bold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.Black,
                            unselectedIconColor = Color.Black,
                            unselectedTextColor = Color.Black,
                            indicatorColor = Color.Black
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (currentScreen) {
                Screen.SUBSCRIPTIONS -> SubscriptionList(
                    modifier = Modifier,
                    subscriptions = subscriptions,
                    isLoading = isLoading,
                    onAddClick = { 
                        viewModel.prepareSearch()
                        currentScreen = Screen.SEARCH 
                    },
                    onUpdateClick = { viewModel.updateAllSubscriptions(context) },
                    onSubscriptionClick = { sub ->
                        viewModel.loadChannelVideos(context, sub)
                        currentScreen = Screen.SEARCH
                    },
                    onUnfollowClick = { url -> viewModel.unfollow(context, url) }
                )
                Screen.DOWNLOADED -> DownloadedScreen(
                    onBack = { currentScreen = Screen.SUBSCRIPTIONS },
                    viewModel = viewModel,
                    exoPlayer = exoPlayer,
                    onChannelClick = { sub ->
                        viewModel.loadChannelVideos(context, sub)
                        currentScreen = Screen.SEARCH
                    }
                )
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { currentScreen = Screen.SUBSCRIPTIONS },
                    viewModel = viewModel
                )
                Screen.SEARCH -> SearchScreen(
                    onBack = { currentScreen = Screen.SUBSCRIPTIONS },
                    viewModel = viewModel,
                    exoPlayer = exoPlayer
                )
            }
        }
    }
}

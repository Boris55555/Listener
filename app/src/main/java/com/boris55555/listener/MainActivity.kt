package com.boris55555.listener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.boris55555.listener.ui.theme.ListenerTheme

import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.ComponentName
import android.os.Build
import androidx.activity.viewModels
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val mediaController = controllerFuture?.get()
            controller = mediaController

            // Restore playback info if already playing in background
            if (mediaController?.currentMediaItem != null) {
                val metadata = mediaController.mediaMetadata
                val url = mediaController.currentMediaItem?.mediaId ?: ""
                val isPlaying = mediaController.isPlaying
                viewModel.restorePlaybackInfo(
                    title = metadata.title?.toString() ?: "Unknown",
                    uploader = metadata.artist?.toString(),
                    url = url,
                    isPlaying = isPlaying,
                    duration = mediaController.duration
                )
            }

            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.setPlaying(isPlaying)
                    if (!isPlaying) {
                        viewModel.saveCurrentPosition(this@MainActivity, mediaController.currentPosition)
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.widget.Toast.makeText(this@MainActivity, "Playback error: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    // Start position is now handled directly when setting the media item
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val duration = mediaController.duration
                        if (duration > 0) {
                            viewModel.updateDuration(duration)
                        }
                    }
                }
            })
        }, MoreExecutors.directExecutor())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ListenerTheme {
                MainScreen(
                    viewModel = viewModel,
                    exoPlayer = controller
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

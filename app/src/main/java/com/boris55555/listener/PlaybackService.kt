package com.boris55555.listener

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ListenerApp.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf(
                "Referer" to "https://odysee.com/",
                "Origin" to "https://odysee.com"
            ))
            
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // This is called when a media button is pressed while the player is stopped/backgrounded
                    // Return an empty future to indicate we don't support simple resumption yet
                    // or ideally load the last played item.
                    return super.onPlaybackResumption(mediaSession, controller)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

package com.boris55555.listener

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerBar(
    playbackInfo: PlaybackInfo,
    exoPlayer: Player,
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var currentPos by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var duration by remember { mutableLongStateOf(exoPlayer.duration.coerceAtLeast(0L)) }
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()

    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    LaunchedEffect(playbackInfo.isPlaying) {
        while (playbackInfo.isPlaying) {
            currentPos = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            viewModel.updatePlaybackState(currentPos, duration)
            viewModel.saveCurrentPosition(context)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(top = 2.dp)
    ) {
        HorizontalDivider(thickness = 2.dp, color = Color.Black)
        
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infoText = buildString {
                    if (playbackInfo.subtitle != null) {
                        append(playbackInfo.subtitle)
                        append(" - ")
                    }
                    append(playbackInfo.title)
                }
                
                Text(
                    text = infoText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
                Text(formatTime(currentPos), style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
                Slider(
                    value = if (duration > 0) (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { 
                        if (duration > 0) {
                            val newPos = (it * duration).toLong()
                            exoPlayer.seekTo(newPos)
                            currentPos = newPos
                        }
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    thumb = { Box(modifier = Modifier.width(2.dp).height(16.dp).background(Color.Black)) },
                    track = { Box(modifier = Modifier.fillMaxWidth().height(4.dp).border(1.dp, Color.Black).background(Color.White)) }
                )
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        val isSelected = playbackSpeed == speed
                        Surface(
                            modifier = Modifier
                                .clickable { viewModel.setPlaybackSpeed(speed) }
                                .border(1.dp, Color.Black, RectangleShape),
                            color = if (isSelected) Color.Black else Color.White,
                            contentColor = if (isSelected) Color.White else Color.Black,
                            shape = RectangleShape
                        ) {
                            Text(text = "${speed}x", modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val controlModifier = Modifier.size(width = 44.dp, height = 32.dp).border(1.dp, Color.Black)
                    
                    Box(modifier = controlModifier.clickable { exoPlayer.seekTo(exoPlayer.currentPosition - 10000) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FastRewind, contentDescription = "-10s", modifier = Modifier.size(20.dp), tint = Color.Black)
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    Box(
                        modifier = controlModifier
                            .background(if (playbackInfo.isPlaying) Color.White else Color.Black)
                            .clickable { if (playbackInfo.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playbackInfo.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(20.dp),
                            tint = if (playbackInfo.isPlaying) Color.Black else Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box(modifier = controlModifier.clickable { exoPlayer.seekTo(exoPlayer.currentPosition + 30000) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FastForward, contentDescription = "+30s", modifier = Modifier.size(20.dp), tint = Color.Black)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

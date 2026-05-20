package com.boris55555.listener

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultItem(
    result: SearchResult,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onFollow: () -> Unit,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (result.isVideo) onInfoClick() else onClick() }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val displayText = buildString {
                    if (!result.isVideo) append("CHANNEL: ")
                    append(result.name)
                    if (result.isDownloaded && result.totalSize != null) {
                        append(" (")
                        append(result.totalSize)
                        append(")")
                    }
                }
                Text(
                    text = displayText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Black
                )
                if (result.isDownloaded) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "OFFLINE",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (result.isVideo) {
                if (result.isDownloading) {
                    val progress = if (result.downloadProgress >= 0) "${result.downloadProgress}%" else "..."
                    Text(
                        text = "DOWNLOADING $progress" + (if (result.totalSize != null) " (${result.totalSize})" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                } else if (result.pubDate > 0) {
                    val label = if (result.isLive) "Streamed on: " else "Published: "
                    Text(
                        text = label + dateFormatter.format(Date(result.pubDate)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black // Changed from Gray for e-ink
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (result.isVideo) {
                // Only show Listen button if not downloading
                if (!result.isDownloading) {
                    OutlinedButton(
                        onClick = onPlay,
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                    ) {
                        Text("LISTEN")
                    }
                }

                if (result.isDownloaded || result.isDownloading) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                    ) {
                        Text(if (result.isDownloading) "CANCEL" else "DELETE")
                    }
                } else {
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        enabled = !result.isDownloading,
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                    ) {
                        Text("DOWNLOAD")
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onFollow,
                    modifier = Modifier.weight(1f),
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                ) {
                    Text(if (result.isFollowed) "UNSUBSCRIBE" else "SUBSCRIBE")
                }
            }
        }
    }
}

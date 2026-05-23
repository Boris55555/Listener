package com.boris55555.listener

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun InfoDialog(
    result: SearchResult,
    onDismiss: () -> Unit,
    viewModel: MainViewModel? = null,
    onChannelClick: ((String, String, String) -> Unit)? = null // name, url, source
) {
    val context = LocalContext.current
    var description by remember { mutableStateOf(result.description) }
    var isLoadingDescription by remember { mutableStateOf(false) }
    val subscriptions by viewModel?.subscriptions?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    
    // Check if the channel is already followed
    val isFollowed = result.uploaderUrl?.let { url -> subscriptions.any { it.url == url } } ?: result.isFollowed

    LaunchedEffect(result) {
        if (viewModel != null && !result.isRss && result.isVideo) {
            isLoadingDescription = true
            val full = viewModel.fetchFullDescription(result)
            if (full != null) description = full
            isLoadingDescription = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .border(2.dp, Color.Black),
            color = Color.White,
            shape = RectangleShape
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "FILE INFO",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                InfoRow("Name", result.name)
                
                InfoRow(
                    label = "Channel", 
                    value = result.uploaderName ?: "Unknown",
                    isLink = result.uploaderUrl != null,
                    onClick = {
                        if (result.uploaderUrl != null) {
                            onChannelClick?.invoke(result.uploaderName ?: "Unknown", result.uploaderUrl, result.source)
                        }
                    }
                )
                
                val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US)
                
                if (result.pubDate > 0 || !result.textualDate.isNullOrEmpty()) {
                    val dateText = if (result.pubDate > 0) {
                        sdf.format(java.util.Date(result.pubDate))
                    } else {
                        result.textualDate ?: "Unknown date"
                    }
                    InfoRow("Published", dateText)
                }

                if (result.isDownloaded && result.downloadDate > 0) {
                    InfoRow("Downloaded", sdf.format(java.util.Date(result.downloadDate)))
                }
                
                if (result.duration > 0) {
                    val minutes = result.duration / 60
                    val seconds = result.duration % 60
                    InfoRow("Duration", String.format(java.util.Locale.US, "%d:%02d", minutes, seconds))
                }
                
                val sourceText = when (result.source) {
                    "YOUTUBE" -> "YouTube"
                    "RSS" -> "Podcast (RSS)"
                    "LBRY" -> "LBRY"
                    else -> result.source
                }
                InfoRow("Source", sourceText)

                if (result.isDownloaded && result.totalSize != null) {
                    InfoRow("Size", result.totalSize)
                }

                if (!isFollowed && result.uploaderUrl != null && viewModel != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.follow(
                                context, 
                                result.uploaderName ?: "Unknown", 
                                result.uploaderUrl, 
                                result.source
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).border(1.dp, Color.Black),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                        shape = RectangleShape
                    ) {
                        Text("SUBSCRIBE TO CHANNEL", fontWeight = FontWeight.Bold)
                    }
                }

                if (isLoadingDescription) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp).padding(vertical = 8.dp))
                } else if (!description.isNullOrBlank()) {
                    val truncatedDescription = if (description!!.length > 2000) {
                        description!!.take(2000) + "..."
                    } else {
                        description!!
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Description", style = MaterialTheme.typography.titleMedium, color = Color.Black, fontWeight = FontWeight.Bold)
                    Text(
                        text = truncatedDescription,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, Color.Black),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RectangleShape
                ) {
                    Text("CLOSE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, isLink: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .then(if (isLink && onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = Color.Black, fontWeight = FontWeight.Bold)
        Text(
            text = value, 
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (isLink) TextDecoration.Underline else TextDecoration.None
            ), 
            color = Color.Black, 
            fontWeight = FontWeight.Bold
        )
    }
}

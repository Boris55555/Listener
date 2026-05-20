package com.boris55555.listener

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun InfoDialog(
    result: SearchResult,
    onDismiss: () -> Unit
) {
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
                Text(
                    text = "FILE INFO",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                InfoRow("Name", result.name)
                InfoRow("Channel", result.uploaderName ?: "Unknown")
                if (result.duration > 0) {
                    val minutes = result.duration / 60
                    val seconds = result.duration % 60
                    InfoRow("Duration", String.format(java.util.Locale.US, "%d:%02d", minutes, seconds))
                }
                InfoRow("Source", if (result.isRss) "Podcast (RSS)" else "YouTube")
                if (result.isDownloaded && result.totalSize != null) {
                    InfoRow("Size", result.totalSize)
                }

                if (!result.description.isNullOrBlank()) {
                    val truncatedDescription = if (result.description.length > 500) {
                        result.description.take(500) + "..."
                    } else {
                        result.description
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Description", style = MaterialTheme.typography.titleMedium, color = Color.Black, fontWeight = FontWeight.Bold)
                    Text(
                        text = truncatedDescription,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RectangleShape
                ) {
                    Text("CLOSE", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = Color.Black, fontWeight = FontWeight.Bold)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = Color.Black)
    }
}

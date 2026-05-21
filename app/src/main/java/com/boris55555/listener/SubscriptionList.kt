package com.boris55555.listener

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SubscriptionList(
    modifier: Modifier,
    subscriptions: List<Subscription>,
    isLoading: Boolean,
    onAddClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onSubscriptionClick: (Subscription) -> Unit,
    onUnfollowClick: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val showUpArrow by remember { derivedStateOf { listState.canScrollBackward } }
    val showDownArrow by remember { derivedStateOf { listState.canScrollForward } }
    
    var urlToUnfollow by remember { mutableStateOf<String?>(null) }
    var filterType by remember { mutableStateOf<String?>(null) } // null = ALL, "RSS" = Podcasts, "YOUTUBE" = YouTube

    val scope = rememberCoroutineScope()

    BackHandler(enabled = urlToUnfollow != null) {
        urlToUnfollow = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { urlToUnfollow = null }
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SUBSCRIPTIONS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                
                if (!isLoading) {
                    Button(
                        onClick = onUpdateClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("REFRESH", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filtering row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show only: ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(modifier = Modifier.width(4.dp))
                FilterSmallButton("ALL", filterType == null) { filterType = null }
                Spacer(modifier = Modifier.width(4.dp))
                FilterSmallButton("PODCASTS", filterType == "RSS") { filterType = "RSS" }
                Spacer(modifier = Modifier.width(4.dp))
                FilterSmallButton("YOUTUBE", filterType == "YOUTUBE") { filterType = "YOUTUBE" }
            }
            
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = Color.Black,
                    trackColor = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (subscriptions.isEmpty() && !isLoading) {
                Text("No subscriptions yet.", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            
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
                items(subscriptions.filter { filterType == null || it.type == filterType }) { sub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSubscriptionClick(sub) },
                                onLongClick = { urlToUnfollow = if (urlToUnfollow == sub.url) null else sub.url }
                            )
                            .border(1.dp, Color.Black)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = if (sub.type == "RSS") Icons.Default.Podcasts else Icons.Default.PlayCircle
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = sub.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        
                        if (urlToUnfollow == sub.url) {
                            IconButton(
                                onClick = { 
                                    onUnfollowClick(sub.url)
                                    urlToUnfollow = null
                                },
                                modifier = Modifier.size(32.dp).border(1.dp, Color.Black)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Unsubscribe", tint = Color.Black)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
        
        FloatingActionButton(
            onClick = onAddClick,
            modifier = Modifier.align(Alignment.BottomEnd).border(2.dp, Color.Black),
            containerColor = Color.Black,
            contentColor = Color.White,
            shape = RectangleShape
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
}

@Composable
fun FilterSmallButton(text: String, selected: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

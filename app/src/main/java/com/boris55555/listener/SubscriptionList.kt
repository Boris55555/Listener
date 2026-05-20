package com.boris55555.listener

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SubscriptionList(
    modifier: Modifier,
    subscriptions: List<Subscription>,
    isLoading: Boolean,
    onAddClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onSubscriptionClick: (Subscription) -> Unit
) {
    val listState = rememberLazyListState()
    val showUpArrow by remember { derivedStateOf { listState.canScrollBackward } }
    val showDownArrow by remember { derivedStateOf { listState.canScrollForward } }

    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SUBSCRIPTIONS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                
                if (!isLoading) {
                    Button(
                        onClick = onUpdateClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("REFRESH")
                    }
                }
            }
            
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = Color.Black,
                    trackColor = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
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
                items(subscriptions) { sub ->
                    Text(
                        text = sub.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSubscriptionClick(sub) }
                            .padding(vertical = 12.dp)
                            .border(1.dp, Color.Black)
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
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

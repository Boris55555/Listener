package com.boris55555.listener

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionsTrigger by remember { mutableStateOf(0) }
    
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.setDownloadPath(context, it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportSubscriptions(context, it) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importSubscriptions(context, it) }
    }

    // Refresh permissions when app returns to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("SETTINGS & PERMISSIONS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        // Download Path Selection
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black)
                .padding(12.dp)
        ) {
            Text("Storage Location", fontWeight = FontWeight.Bold, color = Color.Black)
            Text(viewModel.getDownloadPathName(context), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RectangleShape
            ) {
                Text("SELECT FOLDER", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Auto Refresh Setting
        val currentSetting by viewModel.refreshSetting.collectAsState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black)
                .padding(12.dp)
        ) {
            Text("Refresh interval", fontWeight = FontWeight.Bold, color = Color.Black)
            
            var expanded by remember { mutableStateOf(false) }
            
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                ) {
                    Text(currentSetting.label, fontWeight = FontWeight.Bold)
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color.White).border(1.dp, Color.Black)
                ) {
                    RefreshSetting.entries.forEach { setting ->
                        DropdownMenuItem(
                            text = { Text(setting.label, color = Color.Black, fontWeight = FontWeight.Bold) },
                            onClick = {
                                viewModel.setRefreshSetting(context, setting)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        // Show Notifications Setting (Conditional)
        if (currentSetting.hours > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Black)
                    .padding(12.dp)
            ) {
                val showNotifications by viewModel.showNotifications.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show notifications", fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("Notify about new episodes in background", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Switch(
                        checked = showNotifications,
                        onCheckedChange = { viewModel.setShowNotifications(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color.Black,
                            uncheckedThumbColor = Color.Black,
                            uncheckedTrackColor = Color.White,
                            uncheckedBorderColor = Color.Black
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Management (Export/Import)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black)
                .padding(12.dp)
        ) {
            Text("Data Management", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportLauncher.launch("subscriptions.json") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                    shape = RectangleShape
                ) {
                    Text("EXPORT", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RectangleShape
                ) {
                    Text("IMPORT", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sources Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black)
                .padding(12.dp)
        ) {
            Text("Sources", fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.height(8.dp))
            
            val isYtEnabled by viewModel.isYoutubeEnabled.collectAsState()
            val isLbryEnabled by viewModel.isLbryEnabled.collectAsState()
            
            // YouTube Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable YouTube", fontWeight = FontWeight.Bold, color = Color.Black)
                Switch(
                    checked = isYtEnabled,
                    onCheckedChange = { viewModel.setYoutubeEnabled(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.Black,
                        uncheckedThumbColor = Color.Black,
                        uncheckedTrackColor = Color.White,
                        uncheckedBorderColor = Color.Black
                    )
                )
            }
            
            if (isYtEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                val showYtLive by viewModel.showYoutubeLive.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show YouTube Live", fontWeight = FontWeight.Bold, color = Color.Black)
                    Switch(
                        checked = showYtLive,
                        onCheckedChange = { viewModel.setShowYoutubeLive(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color.Black,
                            uncheckedThumbColor = Color.Black,
                            uncheckedTrackColor = Color.White,
                            uncheckedBorderColor = Color.Black
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // LBRY Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable LBRY", fontWeight = FontWeight.Bold, color = Color.Black)
                Switch(
                    checked = isLbryEnabled,
                    onCheckedChange = { viewModel.setLbryEnabled(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.Black,
                        uncheckedThumbColor = Color.Black,
                        uncheckedTrackColor = Color.White,
                        uncheckedBorderColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mobile Data Setting
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black)
                .padding(12.dp)
        ) {
            val allowMobileData by viewModel.allowMobileData.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow Mobile Data", fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("Use mobile data for updates, streaming and downloads", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                Switch(
                    checked = allowMobileData,
                    onCheckedChange = { viewModel.setAllowMobileData(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.Black,
                        uncheckedThumbColor = Color.Black,
                        uncheckedTrackColor = Color.White,
                        uncheckedBorderColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Battery Optimization
        val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
        val isIgnoringBatteryOptimizations = remember(permissionsTrigger) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else true
        }

        if (!isIgnoringBatteryOptimizations) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Black)
                    .padding(12.dp)
            ) {
                Text("Background Playback", fontWeight = FontWeight.Bold, color = Color.Black)
                Text("The system may kill the app during background playback. Disable battery optimization for the best experience.", 
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RectangleShape
                ) {
                    Text("OPTIMIZATION SETTINGS", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        PermissionItem(
            name = "Internet",
            description = "Needed for search and online playback.",
            isGranted = remember(permissionsTrigger) { hasPermission(context, Manifest.permission.INTERNET) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val isGranted = remember(permissionsTrigger) { hasPermission(context, permission) }
            
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                permissionsTrigger++
            }
            
            PermissionItem(
                name = "Notifications",
                description = "Needed for media controls and download status.",
                isGranted = isGranted,
                onRequest = { 
                    launcher.launch(permission)
                },
                onOpenSettings = {
                    openAppSettings(context)
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        PermissionItem(
            name = "Network State",
            description = "Helps optimize data transfer.",
            isGranted = remember(permissionsTrigger) { hasPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) }
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.Black),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RectangleShape
        ) {
            Text("BACK", fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PermissionItem(
    name: String,
    description: String,
    isGranted: Boolean,
    onRequest: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black)
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = Color.Black)
            Text(
                if (isGranted) "GRANTED" else "NOT GRANTED",
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
        Text(description, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
        if (!isGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRequest != null) {
                    Button(
                        onClick = onRequest,
                        modifier = Modifier.weight(1f).border(1.dp, Color.Black),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RectangleShape
                    ) {
                        Text("REQUEST", fontWeight = FontWeight.Bold)
                    }
                }
                if (onOpenSettings != null) {
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f).border(1.dp, Color.Black),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Text("SETTINGS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

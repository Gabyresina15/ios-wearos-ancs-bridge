package com.ancsbridge.watch.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.ancsbridge.watch.ble.*

class MainActivity : ComponentActivity() {

    private var bleService by mutableStateOf<AncsBleService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as? AncsBleService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startAndBindBleService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        )

        setContent {
            MaterialTheme {
                WatchBridgeScreen(service = bleService)
            }
        }
    }

    private fun startAndBindBleService() {
        val intent = Intent(this, AncsBleService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(serviceConnection) }
    }
}

@Composable
fun WatchBridgeScreen(service: AncsBleService?) {
    val listState = rememberScalingLazyListState()

    val connState by service?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(BleConnectionState.DISCONNECTED) }
    val hfpConnected by service?.isHfpConnected?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val autoReconnect by service?.autoReconnect?.collectAsState()
        ?: remember { mutableStateOf(true) }
    val telemetry by service?.latestTelemetry?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val notifications by service?.notifications?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                val badgeColor = when (connState) {
                    BleConnectionState.READY -> Color(0xFF10B981)
                    BleConnectionState.ADVERTISING,
                    BleConnectionState.CONNECTING,
                    BleConnectionState.BONDING -> Color(0xFFF59E0B)
                    BleConnectionState.DISCONNECTED -> Color(0xFFEF4444)
                }

                Chip(
                    onClick = { service?.startBleBridge() },
                    modifier = Modifier.fillMaxWidth(0.92f),
                    colors = ChipDefaults.primaryChipColors(backgroundColor = badgeColor),
                    label = {
                        Text(
                            text = connState.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    secondaryLabel = {
                        val hfpLabel = if (hfpConnected) "HFP Voz Activo" else "HFP En espera"
                        Text("iPhone 16 • $hfpLabel", fontSize = 10.sp)
                    }
                )
            }

            item {
                Chip(
                    onClick = { service?.syncTelemetryNow() },
                    modifier = Modifier.fillMaxWidth(0.92f),
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        val steps = telemetry?.stepsToday ?: 0L
                        val hr = telemetry?.latestHeartRateBpm ?: 0L
                        Text("👣 $steps pasos • ❤️ $hr bpm", fontSize = 11.sp)
                    },
                    secondaryLabel = {
                        val weight = telemetry?.weightKg?.let { "${"%.1f".format(it)}kg" } ?: "--"
                        val fat = telemetry?.bodyFatPercentage?.let { "${"%.1f".format(it)}%" } ?: "--"
                        Text("BIA: $weight ($fat grasa) • Sync", fontSize = 10.sp)
                    }
                )
            }

            item {
                ToggleChip(
                    checked = autoReconnect,
                    onCheckedChange = { service?.setAutoReconnect(it) },
                    modifier = Modifier.fillMaxWidth(0.92f),
                    label = { Text("Reconexión Low-Power", fontSize = 11.sp) },
                    toggleControl = { Switch(checked = autoReconnect) }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompactButton(onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.PREVIOUS_TRACK) }) {
                        Text("⏮")
                    }
                    CompactButton(onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.TOGGLE_PLAY_PAUSE) }) {
                        Text("⏯")
                    }
                    CompactButton(onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.NEXT_TRACK) }) {
                        Text("⏭")
                    }
                }
            }

            items(notifications, key = { it.uid }) { item ->
                val isCall = item.appIdentifier == "com.apple.mobilephone"
                TitleCard(
                    onClick = { service?.rejectOrDismissNotification(item) },
                    title = {
                        Text(
                            text = "${item.appDisplayName} • ${item.title}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCall) Color(0xFF34D399) else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.fillMaxWidth(0.94f)
                ) {
                    Column {
                        Text(
                            text = item.message.ifEmpty { item.subtitle },
                            fontSize = 11.sp,
                            color = Color(0xFFD1D5DB),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isCall) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                CompactChip(
                                    onClick = { service?.answerOrAcceptNotification(item) },
                                    label = { Text("Contestar HFP", fontSize = 10.sp) },
                                    colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF10B981))
                                )
                                CompactChip(
                                    onClick = { service?.rejectOrDismissNotification(item) },
                                    label = { Text("Cortar", fontSize = 10.sp) },
                                    colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFFEF4444))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.ancsbridge.watch.ble.*
import com.ancsbridge.watch.health.HealthConnectStatus
import com.ancsbridge.watch.health.TelemetrySyncManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var bleService by mutableStateOf<AncsBleService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AncsBleService.LocalBinder)?.getService()
            service?.setAutoReconnect(true)
            service?.setAllowDndNotifications(true)
            service?.setAutoPanInternet(true)
            bleService = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
        }
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        bleService?.syncTelemetryNow()
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startAndBindBleService()
        requestHealthConnectPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtimePermissionLauncher.launch(
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
                CleanWatchScreen(
                    service = bleService,
                    onRequestHealthPermissions = { requestHealthConnectPermissions() }
                )
            }
        }
    }

    private fun requestHealthConnectPermissions() {
        runCatching {
            healthPermissionLauncher.launch(TelemetrySyncManager.REQUIRED_HEALTH_PERMISSIONS)
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
fun CleanWatchScreen(
    service: AncsBleService?,
    onRequestHealthPermissions: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val connState by service?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(BleConnectionState.DISCONNECTED) }
    val healthStatus by service?.healthStatus?.collectAsState()
        ?: remember { mutableStateOf(HealthConnectStatus.UNKNOWN) }
    val telemetry by service?.latestTelemetry?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val notifications by service?.notifications?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }

    val isConnected = connState == BleConnectionState.READY
    val incomingCall = notifications.firstOrNull { it.appIdentifier == "com.apple.mobilephone" }

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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF18181B))
                        .clickable {
                            service?.startBleBridge()
                            service?.syncTelemetryNow()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = "iPhone 16 Pro Max",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Text(
                            text = if (isConnected) "Conectado" else "Conectando...",
                            fontSize = 10.sp,
                            color = if (isConnected) Color(0xFF10B981) else Color(0xFFA1A1AA)
                        )
                    }
                }
            }

            if (incomingCall != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF064E3B))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Llamada entrante",
                            fontSize = 10.sp,
                            color = Color(0xFF6EE7B7)
                        )
                        Text(
                            text = incomingCall.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = { service?.rejectOrDismissNotification(incomingCall) },
                                modifier = Modifier.size(40.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEF4444))
                            ) {
                                Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { service?.answerOrAcceptNotification(incomingCall) },
                                modifier = Modifier.size(40.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF10B981))
                            ) {
                                Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF18181B))
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactButton(
                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.PREVIOUS_TRACK) },
                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF27272A))
                    ) {
                        Text("⏮", fontSize = 13.sp)
                    }
                    Button(
                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.TOGGLE_PLAY_PAUSE) },
                        modifier = Modifier.size(38.dp),
                        colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF3B82F6))
                    ) {
                        Text("⏯", fontSize = 15.sp)
                    }
                    CompactButton(
                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.NEXT_TRACK) },
                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF27272A))
                    ) {
                        Text("⏭", fontSize = 13.sp)
                    }
                }
            }

            item {
                val needsPermission = healthStatus == HealthConnectStatus.PERMISSIONS_MISSING
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF18181B))
                        .clickable {
                            if (needsPermission) onRequestHealthPermissions()
                            else service?.syncTelemetryNow()
                        }
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (needsPermission) {
                        Text(
                            text = "Toca para activar Salud ❤️",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFBBF24)
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${telemetry?.stepsToday ?: 0}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                            Text("Pasos", fontSize = 9.sp, color = Color(0xFFA1A1AA))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${telemetry?.latestHeartRateBpm ?: "--"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFB7185)
                            )
                            Text("BPM", fontSize = 9.sp, color = Color(0xFFA1A1AA))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val weightText = telemetry?.weightKg?.let { "${"%.1f".format(it)}" } ?: "--"
                            Text(
                                text = weightText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                            Text("Kg", fontSize = 9.sp, color = Color(0xFFA1A1AA))
                        }
                    }
                }
            }

            if (notifications.isEmpty()) {
                item {
                    Text(
                        text = "Todo al día",
                        fontSize = 11.sp,
                        color = Color(0xFF71717A),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, bottom = 16.dp)
                    )
                }
            } else {
                items(notifications, key = { it.uid }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF18181B))
                            .clickable { service?.rejectOrDismissNotification(item) }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.appDisplayName,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                maxLines = 1
                            )
                            Text(
                                text = timeFormatter.format(Date(item.timestamp)),
                                fontSize = 9.sp,
                                color = Color(0xFF71717A)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val body = item.message.ifEmpty { item.subtitle }
                        if (body.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = body,
                                fontSize = 11.sp,
                                color = Color(0xFFD4D4D8),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

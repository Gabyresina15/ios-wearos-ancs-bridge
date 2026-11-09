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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
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
import androidx.wear.compose.material.dialog.Dialog
import com.ancsbridge.watch.ble.*
import com.ancsbridge.watch.health.HealthConnectStatus
import com.ancsbridge.watch.health.TelemetrySyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var bleService by mutableStateOf<AncsBleService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AncsBleService.LocalBinder)?.getService()
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
                Manifest.permission.CALL_PHONE,
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
        runCatching { startForegroundService(intent) }
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
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val connState by service?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(BleConnectionState.DISCONNECTED) }
    val phoneName by service?.connectedDeviceName?.collectAsState()
        ?: remember { mutableStateOf("Buscando teléfono...") }
    val iphoneBattery by service?.iphoneBatteryPercent?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val nowPlaying by service?.nowPlaying?.collectAsState()
        ?: remember { mutableStateOf(NowPlayingState()) }
    val phoneContext by service?.phoneContext?.collectAsState()
        ?: remember { mutableStateOf(PhoneContextState()) }
    val healthStatus by service?.healthStatus?.collectAsState()
        ?: remember { mutableStateOf(HealthConnectStatus.UNKNOWN) }
    val telemetry by service?.latestTelemetry?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val notifications by service?.notifications?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val isWatchMuted by service?.isWatchMuted?.collectAsState()
        ?: remember { mutableStateOf(false) }

    var selectedNotification by remember { mutableStateOf<AncsNotification?>(null) }

    val isConnected = connState == BleConnectionState.READY || phoneContext.companionConnected
    val incomingCall = notifications.firstOrNull { it.isIncomingCall }
    val effectivePhoneBattery = phoneContext.phoneBatteryPercent ?: iphoneBattery

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0C))
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Estado del Teléfono + Batería + Clima
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF1A1A1F), Color(0xFF141418))
                            )
                        )
                        .clickable { service?.refreshConnectionOrReconnect() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = phoneName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val batteryBadge = effectivePhoneBattery?.let { " • $it%" } ?: ""
                            Text(
                                text = connState.label + batteryBadge,
                                fontSize = 11.sp,
                                color = if (isConnected) Color(0xFF34D399) else Color(0xFFA1A1AA)
                            )
                        }
                    }

                    if (phoneContext.weatherTempC != null || phoneContext.nextCalendarEvent != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val contextLine = listOfNotNull(
                            phoneContext.weatherTempC?.let { "${it}°C ${phoneContext.weatherCondition.orEmpty()}".trim() },
                            phoneContext.nextCalendarEvent
                        ).joinToString(" • ")
                        Text(
                            text = contextLine,
                            fontSize = 10.sp,
                            color = Color(0xFF7DD3FC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2. Tarjeta de Llamada Entrante Activa
            if (incomingCall != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF064E3B))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📞 Llamada entrante",
                            fontSize = 11.sp,
                            color = Color(0xFF6EE7B7)
                        )
                        Text(
                            text = incomingCall.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = { service?.rejectOrDismissNotification(incomingCall) },
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEF4444))
                            ) {
                                Text("✕", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { service?.answerOrAcceptNotification(incomingCall) },
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF10B981))
                            ) {
                                Text("✓", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Reproductor Multimedia + Volumen
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF1E1E24), Color(0xFF16161A))
                            )
                        )
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = nowPlaying.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = nowPlaying.artist,
                        fontSize = 11.sp,
                        color = Color(0xFFA1A1AA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactButton(
                            onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.PREVIOUS_TRACK) },
                            colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF27272A))
                        ) {
                            Text("⏮", fontSize = 15.sp, color = Color(0xFF38BDF8))
                        }
                        Button(
                            onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.TOGGLE_PLAY_PAUSE) },
                            modifier = Modifier.size(46.dp),
                            colors = ButtonDefaults.primaryButtonColors(
                                backgroundColor = if (nowPlaying.isPlaying) Color(0xFF10B981) else Color(0xFF3B82F6)
                            )
                        ) {
                            Text(
                                if (nowPlaying.isPlaying) "⏸" else "▶",
                                fontSize = 18.sp,
                                color = Color.White
                            )
                        }
                        CompactButton(
                            onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.NEXT_TRACK) },
                            colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF27272A))
                        ) {
                            Text("⏭", fontSize = 15.sp, color = Color(0xFF38BDF8))
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(0.85f),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        CompactButton(
                            onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_DOWN) },
                            colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF222226))
                        ) {
                            Text("🔉-", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                        }
                        CompactButton(
                            onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_UP) },
                            colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF222226))
                        ) {
                            Text("🔊+", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                        }
                    }
                }
            }

            // 4. Acciones Rápidas: Cámara (HID) • Silenciar • Buscar Teléfono
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { service?.triggerCameraShutter() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E3A5F))
                    ) {
                        Text("📸 Cámara", fontSize = 10.sp, color = Color(0xFF7DD3FC))
                    }
                    Button(
                        onClick = { service?.toggleWatchMuted() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isWatchMuted) Color(0xFF7F1D1D) else Color(0xFF1E3A5F)
                        )
                    ) {
                        Text(
                            if (isWatchMuted) "🔕 Mudo" else "🔔 Activo",
                            fontSize = 10.sp,
                            color = if (isWatchMuted) Color(0xFFFCA5A5) else Color(0xFF7DD3FC)
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { service?.ringMyIphone() },
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(38.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF312E81))
                ) {
                    Text("📡 Hacer sonar mi iPhone", fontSize = 11.sp, color = Color(0xFFC7D2FE))
                }
            }

            // 5. Salud y Sensores (Pasos, BPM, Peso / BIA)
            item {
                val needsPermission = healthStatus == HealthConnectStatus.PERMISSIONS_MISSING
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF1A1A1F))
                        .clickable {
                            if (needsPermission) onRequestHealthPermissions()
                            else service?.syncTelemetryNow()
                        }
                        .padding(vertical = 12.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${telemetry?.stepsToday ?: 0}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                        Text("Pasos", fontSize = 10.sp, color = Color(0xFFA1A1AA))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val hrVal = telemetry?.latestHeartRateBpm?.takeIf { it > 0 }?.toString() ?: "--"
                        Text(
                            text = hrVal,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFB7185)
                        )
                        Text("BPM", fontSize = 10.sp, color = Color(0xFFA1A1AA))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val weightText = telemetry?.weightKg?.let { "%.1f".format(it) }
                            ?: if (needsPermission) "Autorizar" else "--"
                        Text(
                            text = weightText,
                            fontSize = if (needsPermission) 10.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (needsPermission) Color(0xFFFBBF24) else Color(0xFF38BDF8)
                        )
                        Text("Kg / BIA", fontSize = 10.sp, color = Color(0xFFA1A1AA))
                    }
                }
            }

            // 6. Bandeja de Notificaciones (Tocar para ver detalle y acciones)
            if (notifications.isEmpty()) {
                item {
                    Text(
                        text = "Sin notificaciones nuevas",
                        fontSize = 12.sp,
                        color = Color(0xFF71717A),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
                    )
                }
            } else {
                items(notifications, key = { it.uid }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1A1F))
                            .clickable { selectedNotification = item }
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.appDisplayName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                maxLines = 1
                            )
                            Text(
                                text = timeFormatter.format(Date(item.timestamp)),
                                fontSize = 10.sp,
                                color = Color(0xFF71717A)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val body = item.message.ifEmpty { item.subtitle }
                        if (body.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = body,
                                fontSize = 12.sp,
                                color = Color(0xFFD4D4D8),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Vista de detalle de notificación (evita borrarla por accidente al leer)
        val detailItem = selectedNotification
        Dialog(
            showDialog = detailItem != null,
            onDismissRequest = { selectedNotification = null }
        ) {
            if (detailItem != null) {
                val detailScroll = rememberScalingLazyListState()
                ScalingLazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                        .padding(horizontal = 10.dp),
                    state = detailScroll,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = detailItem.appDisplayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    item {
                        Text(
                            text = detailItem.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                    item {
                        Text(
                            text = detailItem.message.ifEmpty { detailItem.subtitle },
                            fontSize = 12.sp,
                            color = Color(0xFFE4E4E7),
                            textAlign = TextAlign.Center
                        )
                    }
                    if (detailItem.positiveActionLabel.isNotBlank()) {
                        item {
                            Button(
                                onClick = {
                                    service?.answerOrAcceptNotification(detailItem)
                                    selectedNotification = null
                                },
                                modifier = Modifier.fillMaxWidth(0.9f),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF065F46))
                            ) {
                                Text(detailItem.positiveActionLabel, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    service?.rejectOrDismissNotification(detailItem)
                                    selectedNotification = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF7F1D1D))
                            ) {
                                Text("Descartar", fontSize = 11.sp, color = Color(0xFFFCA5A5))
                            }
                            Button(
                                onClick = { selectedNotification = null },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF27272A))
                            ) {
                                Text("Cerrar", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

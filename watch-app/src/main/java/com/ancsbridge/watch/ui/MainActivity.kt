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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.ancsbridge.watch.health.TelemetrySyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgObsidian = Color(0xFF0A0A0E)
private val CardSurface = Color(0xFF14151C)
private val CardBorder = Color(0xFF232530)
private val AccentEmerald = Color(0xFF10B981)
private val AccentCyan = Color(0xFF06B6D4)
private val AccentCoral = Color(0xFFF43F5E)
private val AccentAmber = Color(0xFFF59E0B)
private val TextSecondary = Color(0xFFA1A1AA)

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
                WatchBridgePagerApp(service = bleService)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchBridgePagerApp(service: AncsBleService?) {
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // 3 páginas: 0 = Notificaciones | 1 = Control Principal (por defecto) | 2 = Salud y Femmto
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val notifListState = rememberScalingLazyListState()
    val controlListState = rememberScalingLazyListState()
    val healthListState = rememberScalingLazyListState()

    val connState by service?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(BleConnectionState.DISCONNECTED) }
    val phoneName by service?.connectedDeviceName?.collectAsState()
        ?: remember { mutableStateOf("Buscando iPhone...") }
    val iphoneBattery by service?.iphoneBatteryPercent?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val nowPlaying by service?.nowPlaying?.collectAsState()
        ?: remember { mutableStateOf(NowPlayingState()) }
    val phoneContext by service?.phoneContext?.collectAsState()
        ?: remember { mutableStateOf(PhoneContextState()) }
    val telemetry by service?.latestTelemetry?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val notifications by service?.notifications?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val isWatchMuted by service?.isWatchMuted?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isMeasuringHr by service?.isMeasuringHr?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isFindingPhone by service?.isFindingPhone?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isWatchRinging by service?.isWatchRinging?.collectAsState()
        ?: remember { mutableStateOf(false) }

    var selectedNotification by remember { mutableStateOf<AncsNotification?>(null) }

    val isConnected = connState == BleConnectionState.READY || phoneContext.companionConnected
    val incomingCall = notifications.firstOrNull { it.isIncomingCall }
    val effectivePhoneBattery = phoneContext.phoneBatteryPercent ?: iphoneBattery

    // Si entra una llamada en vivo, llevamos automáticamente a la pantalla principal
    LaunchedEffect(incomingCall?.uid) {
        if (incomingCall != null && pagerState.currentPage != 1) {
            pagerState.animateScrollToPage(1)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val activeListState = when (pagerState.currentPage) {
        0 -> notifListState
        1 -> controlListState
        else -> healthListState
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = activeListState) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgObsidian)
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        activeListState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    // =========================================================================
                    // PÁGINA 0 (IZQUIERDA): BANDEJA DE NOTIFICACIONES Y MENSAJES
                    // =========================================================================
                    0 -> ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = notifListState,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 14.dp)
                            ) {
                                Text(
                                    text = "Notificaciones (${notifications.size})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isWatchMuted) "🔕 Alertas pausadas en reloj" else "🔔 Alertas activas",
                                    fontSize = 10.sp,
                                    color = if (isWatchMuted) AccentCoral else AccentEmerald
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.9f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { service?.toggleWatchMuted() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (isWatchMuted) Color(0xFF4C1D24) else Color(0xFF12352B)
                                    )
                                ) {
                                    Text(
                                        text = if (isWatchMuted) "Activar" else "Silenciar",
                                        fontSize = 10.sp,
                                        color = if (isWatchMuted) Color(0xFFFDA4AF) else Color(0xFF6EE7B7)
                                    )
                                }
                                if (notifications.isNotEmpty()) {
                                    Button(
                                        onClick = { service?.clearAllNotifications() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = CardSurface)
                                    ) {
                                        Text("Limpiar", fontSize = 10.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        if (notifications.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(0.88f)
                                        .padding(top = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (isWatchMuted) "Notificaciones en pausa" else "Todo al día",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isWatchMuted) {
                                            "No saldrán mensajes ni el punto naranja hasta que las actives."
                                        } else {
                                            "Los mensajes de $phoneName aparecerán aquí."
                                        },
                                        fontSize = 10.sp,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(notifications, key = { it.uid }) { item ->
                                val appAccent = appColorForBundle(item.appIdentifier)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(CardSurface)
                                        .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                                        .clickable { selectedNotification = item }
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(appAccent)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = item.appDisplayName,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = appAccent,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            text = timeFormatter.format(Date(item.timestamp)),
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
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
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================================
                    // PÁGINA 1 (CENTRO): DASHBOARD PRINCIPAL + CONTROLES + MULTIMEDIA
                    // =========================================================================
                    1 -> ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = controlListState,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Estado del iPhone + Batería + Próximo evento
                        item {
                            Column(
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth(0.92f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF151821), Color(0xFF11131A))
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        if (isConnected) AccentEmerald.copy(alpha = 0.45f) else CardBorder,
                                        RoundedCornerShape(24.dp)
                                    )
                                    .clickable { service?.refreshConnectionOrReconnect() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(if (isConnected) AccentEmerald else AccentAmber)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text(
                                            text = phoneName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val batteryBadge = effectivePhoneBattery?.let { " • 🔋 $it%" } ?: ""
                                        val appBadge = if (phoneContext.companionConnected) " • App iOS" else ""
                                        Text(
                                            text = connState.label + batteryBadge + appBadge,
                                            fontSize = 10.sp,
                                            color = if (isConnected) AccentEmerald else TextSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }

                                if (phoneContext.nextCalendarEvent != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "📅 ${phoneContext.nextCalendarEvent}",
                                        fontSize = 10.sp,
                                        color = AccentCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // 2. Llamada Entrante Activa (si existe)
                        if (incomingCall != null) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(Color(0xFF064E3B))
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("📞 Llamada entrante", fontSize = 11.sp, color = Color(0xFF6EE7B7))
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
                                            modifier = Modifier.size(42.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = AccentCoral)
                                        ) {
                                            Text("✕", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = { service?.answerOrAcceptNotification(incomingCall) },
                                            modifier = Modifier.size(42.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = AccentEmerald)
                                        ) {
                                            Text("✓", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Botón Maestro para Silenciar / Pausar Notificaciones en el Reloj
                        item {
                            Button(
                                onClick = { service?.toggleWatchMuted() },
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (isWatchMuted) Color(0xFF4C1D24) else Color(0xFF112E28)
                                )
                            ) {
                                Text(
                                    text = if (isWatchMuted) "🔕 Notif. Silenciadas (Tocar)" else "🔔 Notificaciones Activas",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isWatchMuted) Color(0xFFFDA4AF) else Color(0xFF6EE7B7)
                                )
                            }
                        }

                        // 4. Acciones Rápidas: Cámara • Buscar iPhone (con parada directa desde el reloj)
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.92f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { service?.triggerCameraShutter() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = CardSurface)
                                ) {
                                    Text("📸 Cámara", fontSize = 10.sp, color = AccentCyan)
                                }
                                Button(
                                    onClick = { service?.toggleFindMyIphone() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (isFindingPhone) Color(0xFF4C1D24) else CardSurface
                                    )
                                ) {
                                    Text(
                                        text = if (isFindingPhone) "🔇 Parar alarma" else "📡 Buscar iOS",
                                        fontSize = 10.sp,
                                        color = if (isFindingPhone) Color(0xFFFDA4AF) else Color(0xFFC4B5FD)
                                    )
                                }
                            }
                        }

                        // 5. Reproductor Multimedia AMS + App + Volumen en vivo
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(CardSurface)
                                    .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                                    .padding(vertical = 10.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (nowPlaying.playerName.isNotBlank() || nowPlaying.volumePercent != null) {
                                    val playerBadge = buildString {
                                        if (nowPlaying.playerName.isNotBlank()) append(nowPlaying.playerName)
                                        nowPlaying.volumePercent?.let {
                                            if (isNotEmpty()) append(" • ")
                                            append("🔊 $it%")
                                        }
                                    }
                                    Text(
                                        text = playerBadge,
                                        fontSize = 9.sp,
                                        color = AccentCyan,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Text(
                                    text = nowPlaying.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitleLine = if (nowPlaying.album.isNotBlank()) {
                                    "${nowPlaying.artist} • ${nowPlaying.album}"
                                } else {
                                    nowPlaying.artist
                                }
                                Text(
                                    text = subtitleLine,
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CompactButton(
                                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.PREVIOUS_TRACK) },
                                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF20222C))
                                    ) {
                                        Text("⏮", fontSize = 14.sp, color = AccentCyan)
                                    }
                                    Button(
                                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.TOGGLE_PLAY_PAUSE) },
                                        modifier = Modifier.size(42.dp),
                                        colors = ButtonDefaults.primaryButtonColors(
                                            backgroundColor = if (nowPlaying.isPlaying) AccentEmerald else AccentCyan
                                        )
                                    ) {
                                        Text(
                                            if (nowPlaying.isPlaying) "⏸" else "▶",
                                            fontSize = 16.sp,
                                            color = Color.Black
                                        )
                                    }
                                    CompactButton(
                                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.NEXT_TRACK) },
                                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF20222C))
                                    ) {
                                        Text("⏭", fontSize = 14.sp, color = AccentCyan)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(0.84f),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    CompactButton(
                                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_DOWN) },
                                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF1C1E27))
                                    ) {
                                        Text("🔉-", fontSize = 10.sp, color = TextSecondary)
                                    }
                                    CompactButton(
                                        onClick = { service?.sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_UP) },
                                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF1C1E27))
                                    ) {
                                        Text("🔊+", fontSize = 10.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                text = "⬅ Mensajes (${notifications.size})  •  Salud y Femmto ➡",
                                fontSize = 9.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 14.dp)
                            )
                        }
                    }

                    // =========================================================================
                    // PÁGINA 2 (DERECHA): SALUD DEL RELOJ + BALANZA FEMMTO
                    // =========================================================================
                    else -> ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = healthListState,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "Salud y Femmto",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(top = 14.dp)
                            )
                        }

                        // 1. Pasos y Frecuencia Cardíaca del Reloj
                        item {
                            val steps = telemetry?.stepsToday ?: 0L
                            val distanceKm = (steps * 0.00076).let { String.format(Locale.US, "%.2f km", it) }
                            val kcal = (steps * 0.042).toInt()
                            val hrVal = telemetry?.latestHeartRateBpm?.takeIf { it > 0 }?.toString() ?: "--"

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(CardSurface)
                                    .border(1.dp, CardBorder, RoundedCornerShape(22.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "$steps",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentEmerald
                                        )
                                        Text(
                                            text = "pasos • $distanceKm • $kcal kcal",
                                            fontSize = 9.sp,
                                            color = TextSecondary
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "$hrVal BPM",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentCoral
                                        )
                                        Text(
                                            text = if (isMeasuringHr) "Midiendo PPG..." else "Pulso óptico",
                                            fontSize = 9.sp,
                                            color = if (isMeasuringHr) AccentAmber else TextSecondary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { service?.triggerHeartRateMeasurementNow() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (isMeasuringHr) Color(0xFF4C1D24) else Color(0xFF1F2432)
                                    )
                                ) {
                                    Text(
                                        text = if (isMeasuringHr) "❤️ Midiendo en muñeca..." else "❤️ Medir pulso ahora",
                                        fontSize = 10.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // 2. Tarjeta Dedicada: Balanza Inteligente Femmto (Sincronizada desde el iPhone)
                        item {
                            val weightStr = telemetry?.weightKg?.let { String.format(Locale.US, "%.1f kg", it) } ?: "-- kg"
                            val fatStr = telemetry?.bodyFatPercentage?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--%"
                            val bmiStr = telemetry?.bmi?.let { String.format(Locale.US, "IMC %.1f", it) }
                            val leanStr = telemetry?.leanMassKg?.let { String.format(Locale.US, "Magra %.1f kg", it) }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(CardSurface)
                                    .border(1.dp, AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                                    .clickable { service?.refreshConnectionOrReconnect() }
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "⚖️ Balanza ${telemetry?.bodyCompSource ?: "Femmto"}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = weightStr,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text("Peso", fontSize = 9.sp, color = TextSecondary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = fatStr,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentAmber
                                        )
                                        Text("Grasa corp.", fontSize = 9.sp, color = TextSecondary)
                                    }
                                }
                                if (bmiStr != null || leanStr != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = listOfNotNull(bmiStr, leanStr).joinToString("  •  "),
                                        fontSize = 10.sp,
                                        color = AccentEmerald
                                    )
                                } else if (telemetry?.weightKg == null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Sincronizá desde la app Watch Bridge en $phoneName",
                                        fontSize = 9.sp,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Indicador superior de las 3 páginas (Bandeja | Control | Salud)
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { idx ->
                    val active = pagerState.currentPage == idx
                    Box(
                        modifier = Modifier
                            .size(width = if (active) 14.dp else 5.dp, height = 5.dp)
                            .clip(CircleShape)
                            .background(if (active) AccentEmerald else Color(0xFF3F3F46))
                    )
                }
            }
        }

        // Diálogo de alarma "Buscando tu reloj" disparada desde el iPhone
        Dialog(
            showDialog = isWatchRinging,
            onDismissRequest = { service?.stopWatchFindAlarm(notifyPhone = true) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgObsidian)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🔔 Buscando tu reloj",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCoral,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Alerta enviada desde $phoneName",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { service?.stopWatchFindAlarm(notifyPhone = true) },
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .height(42.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = AccentEmerald)
                ) {
                    Text(
                        text = "Encontrado • Detener",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }

        // Diálogo de lectura completa de notificación
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
                        .background(BgObsidian)
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
                            color = appColorForBundle(detailItem.appIdentifier)
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
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4C1D24))
                            ) {
                                Text("Descartar", fontSize = 10.sp, color = Color(0xFFFDA4AF))
                            }
                            Button(
                                onClick = { selectedNotification = null },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(backgroundColor = CardSurface)
                            ) {
                                Text("Cerrar", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun appColorForBundle(bundleId: String): Color = when {
    bundleId.contains("whatsapp", ignoreCase = true) -> Color(0xFF10B981)
    bundleId.contains("instagram", ignoreCase = true) -> Color(0xFFEC4899)
    bundleId.contains("mobilephone", ignoreCase = true) -> Color(0xFF34D399)
    bundleId.contains("MobileSMS", ignoreCase = true) -> Color(0xFF38BDF8)
    bundleId.contains("telegra", ignoreCase = true) -> Color(0xFF06B6D4)
    bundleId.contains("Gmail", ignoreCase = true) -> Color(0xFFF87171)
    bundleId.contains("mercadopago", ignoreCase = true) -> Color(0xFF60A5FA)
    else -> Color(0xFF06B6D4)
}

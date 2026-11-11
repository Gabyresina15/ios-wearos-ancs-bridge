package com.ancsbridge.watch.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ancsbridge.watch.health.HealthConnectStatus
import com.ancsbridge.watch.health.TelemetrySyncManager
import com.ancsbridge.watch.health.WatchTelemetrySnapshot
import com.ancsbridge.watch.hfp.HfpCallController
import com.ancsbridge.watch.pan.BluetoothPanController
import com.ancsbridge.watch.pan.WatchNetworkMode
import com.ancsbridge.watch.tile.BridgeStatusTileService
import com.ancsbridge.watch.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@SuppressLint("MissingPermission")
class AncsBleService : LifecycleService() {

    companion object {
        private const val TAG = "AncsBleService"
        private const val LEGACY_FG_CHANNEL_ID = "ancs_foreground_channel"
        private const val FG_CHANNEL_ID = "ancs_bg_link_silent_v3"
        private const val ALERTS_CHANNEL_ID = "ancs_ios_alerts_channel"
        private const val SILENT_ALERTS_CHANNEL_ID = "ancs_ios_alerts_silent_v1"
        private const val CALLS_CHANNEL_ID = "ancs_ios_calls_channel"
        private const val FG_NOTIFICATION_ID = 1001
        private const val MAX_GATT_QUEUE_SIZE = 64
        private const val MAX_PREPARED_WRITE_BYTES = 4096
        private const val MAX_TRACKED_UID_METADATA = 128
        private const val GATT_OP_TIMEOUT_MS = 4500L
        private const val BLE_TELEMETRY_SYNC_INTERVAL_MS = 60_000L

        const val ACTION_FAST_RECONNECT = "com.ancsbridge.watch.ACTION_FAST_RECONNECT"
        const val ACTION_WATCHDOG_TICK = "com.ancsbridge.watch.ACTION_WATCHDOG_TICK"
        const val ACTION_HANDLE_NOTIF_INTENT = "com.ancsbridge.watch.ACTION_HANDLE_NOTIF_INTENT"

        private const val PREFS_NAME = "ancs_bridge_prefs"
        private const val KEY_LAST_IPHONE_MAC = "last_iphone_mac"
        private const val KEY_LAST_IPHONE_NAME = "last_iphone_real_name"
        private const val KEY_LAST_CONN_LABEL = "last_conn_state_label"
        private const val KEY_LAST_IPHONE_BATTERY = "last_iphone_battery"
        private const val KEY_ALLOW_DND_NOTIFS = "allow_dnd_notifs"
        private const val KEY_WATCH_MUTED = "watch_notifications_muted"
        private const val KEY_NOTIF_MODE = "watch_notification_mode"
        private const val KEY_BLOCKED_BUNDLES = "blocked_bundles_set"
        private const val KEY_CLOCK_DRIFT_MS = "cts_clock_drift_ms"
    }

    inner class LocalBinder : Binder() {
        fun getService(): AncsBleService = this@AncsBleService
    }

    private val binder = LocalBinder()
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var hfpController: HfpCallController
    private lateinit var panController: BluetoothPanController
    lateinit var telemetryManager: TelemetrySyncManager
        private set

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var bridgeTelemetryChar: BluetoothGattCharacteristic? = null
    private var bridgeWatchEventChar: BluetoothGattCharacteristic? = null
    private var bridgeNotifModeChar: BluetoothGattCharacteristic? = null
    private var remoteIosNotifModeChar: BluetoothGattCharacteristic? = null
    private var hrMeasurementChar: BluetoothGattCharacteristic? = null
    private val subscribedCompanionDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val preparedWriteBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val pendingAppNameQueries = ConcurrentHashMap.newKeySet<String>()

    // Características remotas cuando la app de iOS expone CBPeripheralManager sobre activeGatt
    private var remoteIosTelemetryChar: BluetoothGattCharacteristic? = null
    private var remoteIosCommandChar: BluetoothGattCharacteristic? = null
    private var remoteIosWatchEventChar: BluetoothGattCharacteristic? = null

    private var activeGatt: BluetoothGatt? = null
    private var connectedIphone: BluetoothDevice? = null

    @Volatile private var isAdvertising = false
    @Volatile private var currentAdvertiseMode: Int = -1
    @Volatile private var isConnectingOrConnected = false
    @Volatile private var servicesDiscoveryStarted = false
    @Volatile private var activeCallUid: Int? = null
    @Volatile private var isForegroundPromoted = false

    private var rawGapDeviceName: String? = null
    private var rawDisModelCode: String? = null
    private var reconnectAttempt = 0
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var mtuFallbackJob: Job? = null
    private var gattOpTimeoutJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var watchAlarmTimeoutJob: Job? = null
    private var mediaPushJob: Job? = null
    private var activeWatchRingtone: Ringtone? = null

    private val silentFlagsByUid = ConcurrentHashMap<Int, Boolean>()
    private val categoryByUid = ConcurrentHashMap<Int, Int>()
    private val postedSystemNotificationUids = ConcurrentHashMap.newKeySet<Int>()

    private val _priorityBundles = MutableStateFlow(
        setOf(
            "com.apple.mobilephone",
            "net.whatsapp.WhatsApp",
            "com.apple.MobileSMS"
        )
    )
    val priorityBundles: StateFlow<Set<String>> = _priorityBundles.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow("Buscando iPhone...")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _iphoneBatteryPercent = MutableStateFlow<Int?>(null)
    val iphoneBatteryPercent: StateFlow<Int?> = _iphoneBatteryPercent.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlayingState())
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    private val _phoneContext = MutableStateFlow(PhoneContextState())
    val phoneContext: StateFlow<PhoneContextState> = _phoneContext.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _allowDndNotifications = MutableStateFlow(true)
    val allowDndNotifications: StateFlow<Boolean> = _allowDndNotifications.asStateFlow()

    private val _notificationMode = MutableStateFlow(WatchNotificationMode.NORMAL)
    val notificationMode: StateFlow<WatchNotificationMode> = _notificationMode.asStateFlow()
    private val _isWatchMuted = MutableStateFlow(false)
    val isWatchMuted: StateFlow<Boolean> = _isWatchMuted.asStateFlow()
    private val _cameraPrompt = MutableStateFlow<String?>(null)
    val cameraPrompt: StateFlow<String?> = _cameraPrompt.asStateFlow()
    @Volatile private var remoteCameraReady: Boolean = false

    private val _isFindingPhone = MutableStateFlow(false)
    val isFindingPhone: StateFlow<Boolean> = _isFindingPhone.asStateFlow()

    private val _isWatchRinging = MutableStateFlow(false)
    val isWatchRinging: StateFlow<Boolean> = _isWatchRinging.asStateFlow()

    private val _syncedIphoneTime = MutableStateFlow<SyncedIphoneTime?>(null)
    val syncedIphoneTime: StateFlow<SyncedIphoneTime?> = _syncedIphoneTime.asStateFlow()

    private val _notifications = MutableStateFlow<List<AncsNotification>>(emptyList())
    val notifications: StateFlow<List<AncsNotification>> = _notifications.asStateFlow()

    private val _latestTelemetry = MutableStateFlow<WatchTelemetrySnapshot?>(null)
    val latestTelemetry: StateFlow<WatchTelemetrySnapshot?> = _latestTelemetry.asStateFlow()

    val isHfpConnected: StateFlow<Boolean> get() = hfpController.isHfpConnected
    val isPanConnected: StateFlow<Boolean> get() = panController.isPanConnected
    val autoPanEnabled: StateFlow<Boolean> get() = panController.autoPanEnabled
    val networkMode: StateFlow<WatchNetworkMode> get() = panController.networkMode
    val healthStatus: StateFlow<HealthConnectStatus> get() = telemetryManager.healthStatus
    val isMeasuringHr: StateFlow<Boolean> get() = telemetryManager.isMeasuringHr

    private val _blockedBundles = MutableStateFlow<Set<String>>(emptySet())
    val blockedBundles: StateFlow<Set<String>> = _blockedBundles.asStateFlow()

    private val nextGattOpId = AtomicLong(1L)

    private sealed class GattOperation(val opId: Long) {
        class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic, opId: Long) : GattOperation(opId)
        class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val payload: ByteArray, opId: Long) : GattOperation(opId)
        class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray,
            val writeType: Int,
            opId: Long
        ) : GattOperation(opId)
    }

    private val gattQueue = ConcurrentLinkedQueue<GattOperation>()
    @Volatile private var inFlightOpId: Long = 0L
    private val pendingAmsAttributeRequests = ConcurrentLinkedQueue<Pair<Int, Int>>()
    @Volatile private var isPassiveScanArmed: Boolean = false

    private val dataSourceCollector = AncsDataSourceCollector(
        expectedAttributesCount = 6,
        onAppAttributeParsed = { bundleId, realAppName ->
            pendingAppNameQueries.remove(bundleId)
            AppBundleResolver.cacheResolvedAppName(this@AncsBleService, bundleId, realAppName)
            _notifications.update { list ->
                list.map { item ->
                    if (item.appIdentifier == bundleId) item.copy(appDisplayName = realAppName) else item
                }
            }
            notifyCompanionWatchEvent(
                JSONObject().apply {
                    put("evt", "APP_NAME")
                    put("app", bundleId.take(48))
                    put("appName", realAppName.take(28))
                }
            )
        },
        onNotificationParsed = { parsed ->
            val wasSilent = silentFlagsByUid.remove(parsed.uid) ?: false
            val catId = categoryByUid.remove(parsed.uid) ?: 0
            val correctedTs = System.currentTimeMillis() + getSavedClockDrift()
            handleParsedIosNotification(
                parsed.copy(
                    categoryId = catId,
                    receivedInDndMode = wasSilent,
                    timestamp = correctedTs
                )
            )
        }
    )

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (device?.address == connectedIphone?.address) {
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            device?.let { d ->
                                saveTargetIphoneMac(d.address)
                                updateDetectedPhoneName(d.name, null)
                                hfpController.bindToIphone(d)
                                panController.bindToIphone(d)
                            }
                            activeGatt?.let { proceedToBondOrDiscover(it) }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            resetGattAndReschedule(activeGatt, forceCacheRefresh = true)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppBundleResolver.initialize(this)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        hfpController = HfpCallController(this, bluetoothManager.adapter).also { it.initialize() }
        panController = BluetoothPanController(this, bluetoothManager.adapter).also { it.initialize() }
        telemetryManager = TelemetrySyncManager(this, lifecycleScope).also { manager ->
            manager.setOnHeartRateUpdatedListener { syncTelemetryNow() }
            manager.setOnStepsUpdatedListener { syncTelemetryNow() }
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _allowDndNotifications.value = prefs.getBoolean(KEY_ALLOW_DND_NOTIFS, true)
        val storedMode = WatchNotificationMode.fromWire(prefs.getString(KEY_NOTIF_MODE, null))
            ?: WatchNotificationMode.fromLegacyMutedFlag(prefs.getBoolean(KEY_WATCH_MUTED, false))
        applyNotificationMode(storedMode, persist = false, notifyCompanions = false)
        _blockedBundles.value = prefs.getStringSet(KEY_BLOCKED_BUNDLES, setOf("com.apple.tips")) ?: emptySet()
        prefs.getString(KEY_LAST_IPHONE_NAME, null)?.let { savedName ->
            rawGapDeviceName = savedName
            _connectedDeviceName.value = savedName
        }
        val cachedPhoneBat = prefs.getInt(KEY_LAST_IPHONE_BATTERY, -1)
        if (cachedPhoneBat in 0..100) _iphoneBatteryPercent.value = cachedPhoneBat

        createNotificationChannels()
        promoteToForegroundSafely()

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        BleReconnectWorker.enqueuePeriodicWatchdog(this)

        // Todas las operaciones de inicio BLE corren en el hilo principal para evitar carreras GATT 133
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            startBleBridgeIfNeeded()
            syncTelemetryNow()
        }
    }

    /**
     * Promueve el servicio a Foreground con tipo CONNECTED_DEVICE y canal IMPORTANCE_MIN, sin insignia
     * y diferido, evitando el punto naranja en One UI Watch y protegiendo la vida del servicio en API 34.
     */
    private fun promoteToForegroundSafely() {
        if (isForegroundPromoted) return
        val notification = buildSilentForegroundNotification()
        runCatching {
            ServiceCompat.startForeground(
                this,
                FG_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            isForegroundPromoted = true
        }.onFailure { err ->
            Log.w(TAG, "No se pudo promover FGS aún: ${err.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        promoteToForegroundSafely()

        when (intent?.action) {
            ACTION_FAST_RECONNECT -> {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device != null && _connectionState.value != BleConnectionState.READY) {
                    connectToIphoneGatt(device, autoConnect = false)
                } else if (activeGatt != null && !_phoneContext.value.companionConnected) {
                    runCatching { activeGatt?.discoverServices() }
                }
            }
            ACTION_WATCHDOG_TICK -> {
                panController.evaluateAndArbitrateNetwork()
                if (_connectionState.value == BleConnectionState.READY && activeGatt != null) {
                    requestIphoneBatteryLevel()
                    requestIphoneTimeSync()
                    if (!_phoneContext.value.companionConnected && remoteIosCommandChar == null) {
                        runCatching { activeGatt?.discoverServices() }
                    }
                } else if (_autoReconnect.value) {
                    triggerLowPowerFilteredReconnect()
                }
                syncTelemetryNow()
            }
            ACTION_HANDLE_NOTIF_INTENT -> {
                val uid = intent.getIntExtra(BlePassiveScanReceiver.EXTRA_NOTIF_UID, -1)
                val op = intent.getStringExtra(BlePassiveScanReceiver.EXTRA_NOTIF_OP)
                if (uid != -1 && op != null) {
                    handleSystemNotificationAction(uid, op)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchFindAlarm(notifyPhone = false)
        stopCallVibrationIfNeeded()
        runCatching { unregisterReceiver(bondStateReceiver) }
        stopBleBridge()
        hfpController.release()
        panController.release()
        telemetryManager.release()
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnect.value = enabled
        if (enabled && _connectionState.value == BleConnectionState.DISCONNECTED) {
            reconnectAttempt = 0
            startBleBridgeIfNeeded()
        } else if (!enabled) {
            stopBleBridge()
        }
    }

    fun setAllowDndNotifications(enabled: Boolean) {
        _allowDndNotifications.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALLOW_DND_NOTIFS, enabled).apply()
    }

    fun setWatchMuted(muted: Boolean) {
        setNotificationMode(WatchNotificationMode.fromLegacyMutedFlag(muted))
    }

    fun setNotificationMode(mode: WatchNotificationMode) {
        applyNotificationMode(mode, persist = true, notifyCompanions = true)
    }

    fun cycleNotificationMode(): WatchNotificationMode {
        val next = when (_notificationMode.value) {
            WatchNotificationMode.NORMAL -> WatchNotificationMode.MUTE
            WatchNotificationMode.MUTE -> WatchNotificationMode.DND
            WatchNotificationMode.DND -> WatchNotificationMode.NORMAL
        }
        setNotificationMode(next)
        return next
    }

    fun toggleWatchMuted(): Boolean {
        val next = if (_notificationMode.value == WatchNotificationMode.NORMAL) {
            WatchNotificationMode.DND
        } else {
            WatchNotificationMode.NORMAL
        }
        setNotificationMode(next)
        return next.pausesAlerts
    }

    private fun applyNotificationMode(
        mode: WatchNotificationMode,
        persist: Boolean,
        notifyCompanions: Boolean
    ) {
        val previous = _notificationMode.value
        _notificationMode.value = mode
        _isWatchMuted.value = mode.pausesAlerts
        if (persist) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NOTIF_MODE, mode.wireValue)
                .putBoolean(KEY_WATCH_MUTED, mode.blocksDelivery)
                .apply()
        }
        if (mode.blocksDelivery && previous != WatchNotificationMode.DND) {
            clearAllSystemNotificationsOnly()
        }
        if (notifyCompanions) {
            notifyNotificationMode()
            syncTelemetryNow()
        }
        BridgeStatusTileService.requestUpdate(this)
    }

    fun clearAllNotifications() {
        stopCallVibrationIfNeeded()
        _notifications.value = emptyList()
        clearAllSystemNotificationsOnly()
    }

    private fun clearAllSystemNotificationsOnly() {
        for (uid in postedSystemNotificationUids) {
            runCatching { notificationManager.cancel(uid) }
        }
        postedSystemNotificationUids.clear()
    }

    fun triggerHeartRateMeasurementNow() {
        telemetryManager.triggerHeartRateMeasurement()
    }

    fun updateBlockedBundles(bundles: Set<String>) {
        _blockedBundles.value = bundles
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_BLOCKED_BUNDLES, bundles).apply()
    }

    fun setAutoPanInternet(enabled: Boolean) {
        panController.setAutoPan(enabled)
    }

    fun refreshConnectionOrReconnect() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            setupBridgeGattServer()
            updateAdvertisingStateForCurrentLink()
            if (_connectionState.value == BleConnectionState.READY && activeGatt != null) {
                requestIphoneBatteryLevel()
                requestIphoneTimeSync()
                runCatching { activeGatt?.discoverServices() }
                syncTelemetryNow()
            } else {
                reconnectAttempt = 0
                startBleBridgeIfNeeded()
            }
        }
    }

    fun startBleBridge() = startBleBridgeIfNeeded()

    fun startBleBridgeIfNeeded() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val adapter = bluetoothManager.adapter ?: return@launch
            if (!adapter.isEnabled) return@launch

            setupBridgeGattServer()
            updateAdvertisingStateForCurrentLink()

            if (_connectionState.value == BleConnectionState.READY && activeGatt != null) return@launch

            val bondedIphone = findBondedPhone(adapter)
            if (bondedIphone != null) {
                saveTargetIphoneMac(bondedIphone.address)
                updateDetectedPhoneName(bondedIphone.name, null)
                connectToIphoneGatt(bondedIphone, autoConnect = true)
                startHardwareFilteredLowPowerScan(adapter, bondedIphone.address)
            } else {
                startHardwareFilteredLowPowerScan(adapter, null)
            }
        }
    }

    fun stopBleBridge() {
        connectJob?.cancel()
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        connectionWatchdogJob?.cancel()
        mtuFallbackJob?.cancel()
        gattOpTimeoutJob?.cancel()
        periodicSyncJob?.cancel()
        stopActiveLowPowerScan()
        stopPassiveHardwareScan()
        stopSafeAdvertising()
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
        remoteIosTelemetryChar = null
        remoteIosCommandChar = null
        remoteIosWatchEventChar = null
        remoteIosNotifModeChar = null
        remoteCameraReady = false
        _cameraPrompt.value = null
        _nowPlaying.value = AmsEntityParser.clearedForDisconnect()
        isConnectingOrConnected = false
        servicesDiscoveryStarted = false
        subscribedCompanionDevices.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        gattQueue.clear()
        inFlightOpId = 0L
        updateConnectionState(BleConnectionState.DISCONNECTED)
    }

    /**
     * Busca únicamente el iPhone vinculado por su MAC guardada o por nombre "iPhone"
     * (evitando enlazar por error teléfonos Android cercanos).
     */
    private fun findBondedPhone(adapter: BluetoothAdapter): BluetoothDevice? {
        val savedMac = getSavedIphoneMac()
        val bonded = adapter.bondedDevices ?: return null

        bonded.firstOrNull {
            savedMac != null && it.address.equals(savedMac, ignoreCase = true)
        }?.let { return it }

        return bonded.firstOrNull { device ->
            val name = device.name.orEmpty()
            name.contains("iPhone", ignoreCase = true)
        }
    }

    private fun refreshGattCache(gatt: BluetoothGatt?) {
        if (gatt == null) return
        runCatching {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(gatt)
        }
    }

    private fun resetGattAndReschedule(gatt: BluetoothGatt?, forceCacheRefresh: Boolean) {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            connectJob?.cancel()
            connectionWatchdogJob?.cancel()
            mtuFallbackJob?.cancel()
            gattOpTimeoutJob?.cancel()
            periodicSyncJob?.cancel()
            gattQueue.clear()
            inFlightOpId = 0L
            pendingAmsAttributeRequests.clear()
            isConnectingOrConnected = false
            servicesDiscoveryStarted = false
            remoteIosTelemetryChar = null
            remoteIosCommandChar = null
            remoteIosWatchEventChar = null
            remoteIosNotifModeChar = null
            remoteCameraReady = false
            _cameraPrompt.value = null
            _nowPlaying.value = AmsEntityParser.clearedForDisconnect()

            if (forceCacheRefresh) refreshGattCache(gatt)
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            if (activeGatt == gatt) activeGatt = null

            // Evitar companionConnected stale, que dejaba el advertising apagado en READY.
            _phoneContext.update {
                it.copy(companionConnected = subscribedCompanionDevices.isNotEmpty())
            }
            if (activeGatt == null && subscribedCompanionDevices.isEmpty()) {
                updateConnectionState(BleConnectionState.DISCONNECTED)
            }
            updateAdvertisingStateForCurrentLink()

            // Reconectar el cliente ANCS aunque el iPhone siga suscrito al GATT server del reloj.
            if (activeGatt == null && _autoReconnect.value) {
                scheduleExponentialBackoffReconnect()
            }
        }
    }

    /**
     * Escaneo BLE con ventana de 15s por callback + PendingIntent pasivo en hardware mientras está
     * desconectado (que se frena explícitamente al conectar en stopPassiveHardwareScan()).
     */
    private fun startHardwareFilteredLowPowerScan(adapter: BluetoothAdapter, targetMac: String?) {
        scanner = adapter.bluetoothLeScanner ?: return
        stopActiveLowPowerScan()

        val filters = mutableListOf<ScanFilter>()
        if (!targetMac.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            filters.add(ScanFilter.Builder().setDeviceAddress(targetMac).build())
        }
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(AppleBleUuids.BRIDGE_SYNC_SERVICE))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        runCatching { scanner?.startScan(filters, settings, lowPowerScanCallback) }
        armPassiveHardwareScanIfNeeded(filters, settings)

        scanTimeoutJob?.cancel()
        scanTimeoutJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            delay(15_000L)
            stopActiveLowPowerScan()
        }
    }

    private fun stopActiveLowPowerScan() {
        scanTimeoutJob?.cancel()
        runCatching { scanner?.stopScan(lowPowerScanCallback) }
    }

    private fun armPassiveHardwareScanIfNeeded(filters: List<ScanFilter>, settings: ScanSettings) {
        if (isPassiveScanArmed || (activeGatt != null && _connectionState.value == BleConnectionState.READY)) return
        val result = runCatching {
            scanner?.startScan(filters, settings, buildPassiveScanPendingIntent())
        }.getOrNull()
        if (result == BluetoothStatusCodes.SUCCESS) {
            isPassiveScanArmed = true
        }
    }

    private fun stopPassiveHardwareScan() {
        // Siempre se llama stopScan con el mismo PendingIntent: el flag in-memory se pierde
        // si el proceso murió, y el scan del sistema puede seguir armado.
        isPassiveScanArmed = false
        val activeScanner = scanner ?: bluetoothManager.adapter?.bluetoothLeScanner
        runCatching { activeScanner?.stopScan(buildPassiveScanPendingIntent()) }
    }

    private fun buildPassiveScanPendingIntent(): PendingIntent {
        val intent = Intent(this, BlePassiveScanReceiver::class.java).apply {
            action = BlePassiveScanReceiver.ACTION_PASSIVE_BLE_FOUND
        }
        return PendingIntent.getBroadcast(
            this,
            901,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private val lowPowerScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            stopActiveLowPowerScan()
            if ((activeGatt == null || _connectionState.value != BleConnectionState.READY) && !isConnectingOrConnected) {
                connectToIphoneGatt(device, autoConnect = false)
            } else if (activeGatt != null && !_phoneContext.value.companionConnected && remoteIosCommandChar == null) {
                runCatching { activeGatt?.discoverServices() }
            }
        }
    }

    private fun scheduleExponentialBackoffReconnect() {
        if (!_autoReconnect.value) return
        reconnectJob?.cancel()

        val expSeconds = min(90L, (3.0 * 2.0.pow(reconnectAttempt.toDouble())).toLong())
        val totalDelaySeconds = expSeconds + Random.nextLong(1, 4)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)

        if (totalDelaySeconds >= 60L) {
            BleReconnectWorker.scheduleExponentialReconnect(this, totalDelaySeconds)
        } else {
            reconnectJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
                delay(totalDelaySeconds * 1000L)
                triggerLowPowerFilteredReconnect()
            }
        }
    }

    private fun triggerLowPowerFilteredReconnect() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val adapter = bluetoothManager.adapter ?: return@launch
            setupBridgeGattServer()
            updateAdvertisingStateForCurrentLink()
            if (_connectionState.value == BleConnectionState.READY && activeGatt != null) return@launch

            val bonded = findBondedPhone(adapter)
            if (bonded != null) {
                saveTargetIphoneMac(bonded.address)
                updateDetectedPhoneName(bonded.name, null)
                connectToIphoneGatt(bonded, autoConnect = true)
                startHardwareFilteredLowPowerScan(adapter, bonded.address)
            } else {
                startHardwareFilteredLowPowerScan(adapter, null)
            }
        }
    }

    /**
     * Configura el servidor GATT local del reloj registrando secuencialmente BRIDGE_SYNC_SERVICE
     * y luego GENERIC_HEART_RATE_SERVICE dentro de onServiceAdded.
     */
    private fun setupBridgeGattServer() {
        if (gattServer != null) return

        val hrChar = BluetoothGattCharacteristic(
            AppleBleUuids.HEART_RATE_MEASUREMENT_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    AppleBleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        hrMeasurementChar = hrChar

        val hrService = BluetoothGattService(
            AppleBleUuids.GENERIC_HEART_RATE_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(hrChar)
        }

        val bridgeService = BluetoothGattService(
            AppleBleUuids.BRIDGE_SYNC_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val telemetryChar = BluetoothGattCharacteristic(
            AppleBleUuids.BRIDGE_TELEMETRY_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    AppleBleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

        val commandConfigChar = BluetoothGattCharacteristic(
            AppleBleUuids.BRIDGE_COMMAND_CONFIG_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val watchEventChar = BluetoothGattCharacteristic(
            AppleBleUuids.BRIDGE_WATCH_EVENT_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    AppleBleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

        val notifModeChar = BluetoothGattCharacteristic(
            AppleBleUuids.BRIDGE_NOTIF_MODE_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    AppleBleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

        bridgeService.addCharacteristic(telemetryChar)
        bridgeService.addCharacteristic(commandConfigChar)
        bridgeService.addCharacteristic(watchEventChar)
        bridgeService.addCharacteristic(notifModeChar)

        bridgeTelemetryChar = telemetryChar
        bridgeWatchEventChar = watchEventChar
        bridgeNotifModeChar = notifModeChar

        gattServer = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == AppleBleUuids.BRIDGE_SYNC_SERVICE) {
                    runCatching { gattServer?.addService(hrService) }
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    saveTargetIphoneMac(device.address)
                    updateDetectedPhoneName(device.name, null)
                    hfpController.bindToIphone(device)
                    if (!isConnectingOrConnected && activeGatt == null) {
                        connectToIphoneGatt(device, autoConnect = false)
                    } else if (activeGatt != null && remoteIosCommandChar == null) {
                        runCatching { activeGatt?.discoverServices() }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribedCompanionDevices.remove(device)
                    preparedWriteBuffers.remove(device.address)
                    _phoneContext.update {
                        it.copy(companionConnected = subscribedCompanionDevices.isNotEmpty() || remoteIosCommandChar != null)
                    }
                    updateAdvertisingStateForCurrentLink()
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                when (characteristic.uuid) {
                    AppleBleUuids.BRIDGE_TELEMETRY_CHAR -> {
                        val payload = buildCurrentTelemetryBleBytes()
                        val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    }
                    AppleBleUuids.HEART_RATE_MEASUREMENT_CHAR -> {
                        val bpm = (_latestTelemetry.value?.latestHeartRateBpm ?: 0L).coerceIn(0L, 255L).toByte()
                        val hrBytes = byteArrayOf(0x00, bpm)
                        val slice = if (offset < hrBytes.size) hrBytes.copyOfRange(offset, hrBytes.size) else ByteArray(0)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    }
                    AppleBleUuids.BRIDGE_NOTIF_MODE_CHAR -> {
                        val payload = BridgeProtocol.encodeNotificationMode(_notificationMode.value)
                        val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    }
                    else -> {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (characteristic.uuid == AppleBleUuids.BRIDGE_NOTIF_MODE_CHAR && value != null) {
                    subscribedCompanionDevices.add(device)
                    _phoneContext.update { it.copy(companionConnected = true) }
                    updateAdvertisingStateForCurrentLink()
                    if (!preparedWrite) {
                        BridgeProtocol.decodeNotificationMode(value)?.let { setNotificationMode(it) }
                    }
                } else if (characteristic.uuid == AppleBleUuids.BRIDGE_COMMAND_CONFIG_CHAR && value != null) {
                    subscribedCompanionDevices.add(device)
                    _phoneContext.update { it.copy(companionConnected = true) }
                    updateAdvertisingStateForCurrentLink()

                    if (preparedWrite) {
                        val buf = preparedWriteBuffers.getOrPut(device.address) { ByteArrayOutputStream() }
                        if (buf.size() + value.size > MAX_PREPARED_WRITE_BYTES) {
                            preparedWriteBuffers.remove(device.address)
                            if (responseNeeded) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                            }
                            return
                        }
                        buf.write(value)
                    } else {
                        handleCompanionBleCommand(String(value, StandardCharsets.UTF_8))
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                val buf = preparedWriteBuffers.remove(device.address)
                if (execute && buf != null && buf.size() > 0) {
                    handleCompanionBleCommand(String(buf.toByteArray(), StandardCharsets.UTF_8))
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                if (descriptor.uuid == AppleBleUuids.CCCD) {
                    val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                    if (enabled) {
                        subscribedCompanionDevices.add(device)
                        _phoneContext.update { it.copy(companionConnected = true) }
                        if (_connectionState.value != BleConnectionState.READY) {
                            updateConnectionState(BleConnectionState.READY)
                        }
                        updateAdvertisingStateForCurrentLink()
                        syncTelemetryNow()
                    } else {
                        subscribedCompanionDevices.remove(device)
                        updateAdvertisingStateForCurrentLink()
                    }
                }
            }
        })

        runCatching {
            gattServer?.addService(bridgeService)
        }
    }

    private fun handleCompanionBleCommand(rawJson: String) {
        runCatching {
            val json = JSONObject(rawJson)
            when (json.optString("cmd")) {
                "PING_WATCH" -> {
                    val active = json.optBoolean("active", true)
                    if (active) {
                        startWatchFindAlarm()
                    } else {
                        stopWatchFindAlarm(notifyPhone = false)
                    }
                }

                "FIND_PHONE_STOPPED" -> {
                    _isFindingPhone.value = false
                }

                "SYNC_NOW" -> syncTelemetryNow()

                "MEASURE_HR" -> triggerHeartRateMeasurementNow()

                "CLEAR_NOTIFS" -> clearAllNotifications()

                "SET_DND" -> {
                    BridgeProtocol.decodeModeFromCommand(json)?.let { setNotificationMode(it) }
                }

                BridgeProtocol.CMD_SET_NOTIF_MODE -> {
                    BridgeProtocol.decodeModeFromCommand(json)?.let { setNotificationMode(it) }
                }

                BridgeProtocol.CMD_CAMERA_STATUS -> {
                    BridgeProtocol.decodeCameraStatus(json)?.let { handleCameraStatus(it) }
                }

                "SET_BLOCKED" -> {
                    val arr = json.optJSONArray("bundles") ?: JSONArray()
                    val updated = mutableSetOf<String>()
                    for (i in 0 until arr.length()) updated.add(arr.getString(i))
                    updateBlockedBundles(updated)
                }

                "BODY_COMP" -> {
                    val wt = if (json.has("wt") && !json.isNull("wt")) json.optDouble("wt") else null
                    val fat = if (json.has("fat") && !json.isNull("fat")) json.optDouble("fat") else null
                    val bmi = if (json.has("bmi") && !json.isNull("bmi")) json.optDouble("bmi") else null
                    val lean = if (json.has("lean") && !json.isNull("lean")) json.optDouble("lean") else null
                    val src = json.optString("src", "Apple Health").takeIf { it.isNotBlank() }
                    val mts = json.optLong("mts", System.currentTimeMillis())

                    telemetryManager.saveBodyCompositionFromPhone(
                        weightKg = wt,
                        bodyFatPct = fat,
                        bmi = bmi,
                        leanMassKg = lean,
                        source = src,
                        measuredAtMillis = mts
                    )
                    syncTelemetryNow()
                }

                "PHONE_CONTEXT" -> {
                    val phoneBat = json.optInt("bat", -1).takeIf { it in 0..100 }
                    val phoneChg = json.optBoolean("chg", false)
                    val tempC = if (json.has("tempC") && !json.isNull("tempC")) json.optInt("tempC") else null
                    val weather = json.optString("weather", "").takeIf { it.isNotBlank() }
                    val nextEvent = json.optString("nextEvent", "").takeIf { it.isNotBlank() }
                    val platform = json.optString("platform", "iOS")
                    val phoneName = json.optString("phoneName", "").takeIf { it.isNotBlank() }
                    val phoneModel = json.optString("phoneModel", "").takeIf { it.isNotBlank() }

                    if (phoneName != null || phoneModel != null) {
                        updateDetectedPhoneName(phoneName, phoneModel)
                    }
                    if (phoneBat != null) updatePhoneBattery(phoneBat)

                    if (json.has("wt") || json.has("fat")) {
                        val wt = if (json.has("wt") && !json.isNull("wt")) json.optDouble("wt") else null
                        val fat = if (json.has("fat") && !json.isNull("fat")) json.optDouble("fat") else null
                        val bmi = if (json.has("bmi") && !json.isNull("bmi")) json.optDouble("bmi") else null
                        val lean = if (json.has("lean") && !json.isNull("lean")) json.optDouble("lean") else null
                        telemetryManager.saveBodyCompositionFromPhone(
                            weightKg = wt,
                            bodyFatPct = fat,
                            bmi = bmi,
                            leanMassKg = lean,
                            source = json.optString("src", "Apple Health"),
                            measuredAtMillis = json.optLong("mts", System.currentTimeMillis())
                        )
                    }

                    _phoneContext.value = PhoneContextState(
                        phoneBatteryPercent = phoneBat ?: _iphoneBatteryPercent.value,
                        phoneIsCharging = phoneChg,
                        weatherTempC = tempC,
                        weatherCondition = weather,
                        nextCalendarEvent = nextEvent,
                        companionPlatform = platform,
                        companionConnected = true
                    )
                    updateConnectionState(BleConnectionState.READY)
                    updateAdvertisingStateForCurrentLink()
                    syncTelemetryNow()
                }

                "MEDIA_CMD" -> {
                    val cmdName = json.optString("action")
                    AmsRemoteCommandId.entries.firstOrNull { it.name == cmdName }?.let {
                        sendAmsMediaCommand(it)
                    }
                }

                else -> Unit
            }
            Unit
        }.onFailure { err ->
            Log.w(TAG, "Comando BLE inválido desde iOS: ${err.message}")
        }
    }

    private fun startWatchFindAlarm() {
        _isWatchRinging.value = true
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 200, 450, 250), 0))

        runCatching {
            activeWatchRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
            activeWatchRingtone = ringtone
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { startActivity(openIntent) }

        watchAlarmTimeoutJob?.cancel()
        watchAlarmTimeoutJob = lifecycleScope.launch {
            delay(18_000L)
            if (_isWatchRinging.value) {
                stopWatchFindAlarm(notifyPhone = true)
            }
        }
    }

    fun stopWatchFindAlarm(notifyPhone: Boolean = true) {
        watchAlarmTimeoutJob?.cancel()
        _isWatchRinging.value = false
        runCatching {
            activeWatchRingtone?.stop()
            activeWatchRingtone = null
        }
        runCatching {
            val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            vibrator.cancel()
        }
        if (notifyPhone) {
            notifyCompanionWatchEvent(JSONObject().put("evt", "WATCH_FOUND"))
        }
    }

    private fun notifyCompanionWatchEvent(eventJson: JSONObject) {
        val bytes = eventJson.toString().toByteArray(StandardCharsets.UTF_8)

        val server = gattServer
        val char = bridgeWatchEventChar
        if (server != null && char != null) {
            for (device in subscribedCompanionDevices) {
                runCatching {
                    server.notifyCharacteristicChanged(device, char, false, bytes)
                }
            }
        }

        val remoteEventChar = remoteIosWatchEventChar
        if (activeGatt != null && remoteEventChar != null) {
            val writeType = if ((remoteEventChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            enqueueGattOperation(
                GattOperation.WriteCharacteristic(remoteEventChar, bytes, writeType, nextGattOpId.incrementAndGet())
            )
        }
    }

    private fun notifyNotificationMode() {
        val bytes = BridgeProtocol.encodeNotificationMode(_notificationMode.value)
        val server = gattServer
        val char = bridgeNotifModeChar
        if (server != null && char != null) {
            for (device in subscribedCompanionDevices) {
                runCatching {
                    server.notifyCharacteristicChanged(device, char, false, bytes)
                }
            }
        }
    }

    private fun notifyCompanionTelemetry(snapshot: WatchTelemetrySnapshot) {
        val bytes = telemetryManager.toBleJson(
            snapshot = snapshot,
            notificationMode = _notificationMode.value,
            isHfpConnected = hfpController.isHfpConnected.value,
            isHidConnected = false,
            nowPlaying = _nowPlaying.value
        ).toByteArray(StandardCharsets.UTF_8)

        val server = gattServer
        val char = bridgeTelemetryChar
        val hrChar = hrMeasurementChar
        val hrBytes = if (snapshot.latestHeartRateBpm > 0L) {
            byteArrayOf(0x00, snapshot.latestHeartRateBpm.coerceIn(30L, 240L).toByte())
        } else null

        if (server != null) {
            for (device in subscribedCompanionDevices) {
                if (char != null) {
                    runCatching { server.notifyCharacteristicChanged(device, char, false, bytes) }
                }
                if (hrChar != null && hrBytes != null) {
                    runCatching { server.notifyCharacteristicChanged(device, hrChar, false, hrBytes) }
                }
            }
        }

        val remoteTelChar = remoteIosTelemetryChar
        if (activeGatt != null && remoteTelChar != null) {
            val writeType = if ((remoteTelChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            enqueueGattOperation(
                GattOperation.WriteCharacteristic(remoteTelChar, bytes, writeType, nextGattOpId.incrementAndGet())
            )
        }
    }

    private fun buildCurrentTelemetryBleBytes(): ByteArray {
        val snap = _latestTelemetry.value ?: WatchTelemetrySnapshot(
            watchBatteryPercent = telemetryManager.readBatteryState().first,
            isCharging = telemetryManager.readBatteryState().second,
            stepsToday = 0L,
            latestHeartRateBpm = 0L,
            weightKg = null,
            bodyFatPercentage = null
        )
        return telemetryManager.toBleJson(
            snapshot = snap,
            notificationMode = _notificationMode.value,
            isHfpConnected = hfpController.isHfpConnected.value,
            isHidConnected = false,
            nowPlaying = _nowPlaying.value
        ).toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Ajusta o apaga dinámicamente la publicidad BLE según el estado del enlace para ahorrar batería:
     * • Si ANCS + App iOS están enlazados (READY + companionConnected), apaga el advertising (0% gasto).
     * • Si ANCS está READY pero la app iOS aún no se suscribió, usa ADVERTISE_MODE_LOW_POWER.
     * • Si está desconectado buscando enlace inicial, usa ADVERTISE_MODE_BALANCED.
     */
    private fun updateAdvertisingStateForCurrentLink() {
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) {
            stopSafeAdvertising()
            return
        }

        val isAnscReady = _connectionState.value == BleConnectionState.READY
        val isCompanionLinked = subscribedCompanionDevices.isNotEmpty() || remoteIosCommandChar != null || _phoneContext.value.companionConnected

        if (isAnscReady && isCompanionLinked) {
            stopSafeAdvertising()
            return
        }

        val desiredMode = if (isAnscReady) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        } else {
            AdvertiseSettings.ADVERTISE_MODE_BALANCED
        }

        if (isAdvertising && currentAdvertiseMode == desiredMode) {
            return
        }

        stopSafeAdvertising()
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        val txPower = if (desiredMode == AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) {
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        } else {
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(desiredMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(txPower)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(AppleBleUuids.BRIDGE_SYNC_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceSolicitationUuid(ParcelUuid(AppleBleUuids.ANCS_SERVICE))
            .addServiceUuid(ParcelUuid(AppleBleUuids.GENERIC_HEART_RATE_SERVICE))
            .build()

        runCatching {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            isAdvertising = true
            currentAdvertiseMode = desiredMode
            if (_connectionState.value == BleConnectionState.DISCONNECTED) {
                updateConnectionState(BleConnectionState.ADVERTISING)
            }
        }
    }

    private fun stopSafeAdvertising() {
        if (!isAdvertising) return
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        isAdvertising = false
        currentAdvertiseMode = -1
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Error en BLE Advertising code=$errorCode")
            isAdvertising = (errorCode == ADVERTISE_FAILED_ALREADY_STARTED)
        }
    }

    /**
     * Conecta al iPhone serializando en el hilo principal y dejando 220ms de asentamiento tras
     * cerrar un BluetoothGatt previo para evitar el error GATT 133 en controladores Bluetooth de Wear OS.
     */
    private fun connectToIphoneGatt(device: BluetoothDevice, autoConnect: Boolean) {
        if (isConnectingOrConnected && activeGatt != null && connectedIphone?.address == device.address) {
            return
        }
        isConnectingOrConnected = true
        servicesDiscoveryStarted = false
        connectedIphone = device
        updateDetectedPhoneName(device.name, null)
        updateConnectionState(BleConnectionState.CONNECTING)

        connectJob?.cancel()
        connectJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            val previousGatt = activeGatt
            if (previousGatt != null) {
                activeGatt = null
                runCatching {
                    previousGatt.disconnect()
                    previousGatt.close()
                }
                delay(220L)
            }

            val gatt = runCatching {
                device.connectGatt(
                    this@AncsBleService,
                    autoConnect,
                    gattClientCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }.getOrNull()
            activeGatt = gatt
            if (gatt == null) {
                isConnectingOrConnected = false
                if (_autoReconnect.value) {
                    scheduleExponentialBackoffReconnect()
                }
                return@launch
            }

            connectionWatchdogJob?.cancel()
            val watchdogTimeoutMs = if (autoConnect) 55_000L else 25_000L
            connectionWatchdogJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
                delay(watchdogTimeoutMs)
                if (_connectionState.value != BleConnectionState.READY) {
                    resetGattAndReschedule(activeGatt, forceCacheRefresh = !autoConnect)
                }
            }
        }
    }

    private fun startPeriodicSyncLoop() {
        periodicSyncJob?.cancel()
        periodicSyncJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BleConnectionState.READY) {
                delay(20_000L)
                if (_connectionState.value == BleConnectionState.READY) {
                    if (!_phoneContext.value.companionConnected && remoteIosCommandChar == null) {
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { activeGatt?.discoverServices() }
                        }
                    }
                    syncTelemetryNow()
                }
                delay(BLE_TELEMETRY_SYNC_INTERVAL_MS - 20_000L)
            }
        }
    }

    @Synchronized
    private fun proceedToBondOrDiscover(gatt: BluetoothGatt) {
        mtuFallbackJob?.cancel()
        if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
            updateConnectionState(BleConnectionState.BONDING)
            if (gatt.device.bondState == BluetoothDevice.BOND_NONE) {
                gatt.device.createBond()
            }
        } else if (!servicesDiscoveryStarted) {
            servicesDiscoveryStarted = true
            gatt.discoverServices()
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (activeGatt != null && gatt != activeGatt) {
                runCatching { gatt.close() }
                return
            }
            if (status == 133 || status == 8 || status == 62) {
                Log.w(TAG, "GATT status=$status. Refrescando caché y reconectando.")
                resetGattAndReschedule(gatt, forceCacheRefresh = true)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnectingOrConnected = true
                    servicesDiscoveryStarted = false
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    connectionWatchdogJob?.cancel()
                    stopActiveLowPowerScan()
                    stopPassiveHardwareScan()

                    saveTargetIphoneMac(gatt.device.address)
                    updateDetectedPhoneName(gatt.device.name, null)
                    gattQueue.clear()
                    inFlightOpId = 0L
                    dataSourceCollector.reset()

                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    val mtuRequested = runCatching { gatt.requestMtu(247) }.getOrDefault(false)

                    mtuFallbackJob?.cancel()
                    if (!mtuRequested) {
                        proceedToBondOrDiscover(gatt)
                    } else {
                        mtuFallbackJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
                            delay(2_200L)
                            if (!servicesDiscoveryStarted && activeGatt == gatt) {
                                proceedToBondOrDiscover(gatt)
                            }
                        }
                    }

                    hfpController.bindToIphone(gatt.device)
                    panController.bindToIphone(gatt.device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    panController.evaluateAndArbitrateNetwork()
                    resetGattAndReschedule(gatt, forceCacheRefresh = false)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onMtuChanged devolvió status=$status (usando MTU por defecto=$mtu)")
            }
            proceedToBondOrDiscover(gatt)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            servicesDiscoveryStarted = true
            runCatching { gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                resetGattAndReschedule(gatt, forceCacheRefresh = true)
                return
            }

            // 1. Verificar si la app Watch Bridge del iPhone expuso BridgeSyncService (CBPeripheralManager)
            val iosBridgeService = gatt.getService(AppleBleUuids.BRIDGE_SYNC_SERVICE)
            if (iosBridgeService != null) {
                remoteIosTelemetryChar = iosBridgeService.getCharacteristic(AppleBleUuids.BRIDGE_TELEMETRY_CHAR)
                remoteIosCommandChar = iosBridgeService.getCharacteristic(AppleBleUuids.BRIDGE_COMMAND_CONFIG_CHAR)
                remoteIosWatchEventChar = iosBridgeService.getCharacteristic(AppleBleUuids.BRIDGE_WATCH_EVENT_CHAR)
                remoteIosNotifModeChar = iosBridgeService.getCharacteristic(AppleBleUuids.BRIDGE_NOTIF_MODE_CHAR)

                remoteIosCommandChar?.let { cmdChar ->
                    enableCharacteristicNotifications(gatt, cmdChar)
                    enqueueGattOperation(GattOperation.ReadCharacteristic(cmdChar, nextGattOpId.incrementAndGet()))
                }
                _phoneContext.update { it.copy(companionConnected = true) }
            }

            // 2. Suscripción a ANCS (Notificaciones nativas del sistema iOS: Data Source primero, luego Notification Source)
            val ancsService = gatt.getService(AppleBleUuids.ANCS_SERVICE)
            if (ancsService == null && iosBridgeService == null) {
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                }
                return
            }

            if (ancsService != null) {
                val dataSourceChar = ancsService.getCharacteristic(AppleBleUuids.ANCS_DATA_SOURCE)
                val notifSourceChar = ancsService.getCharacteristic(AppleBleUuids.ANCS_NOTIFICATION_SOURCE)
                if (dataSourceChar != null && notifSourceChar != null) {
                    enableCharacteristicNotifications(gatt, dataSourceChar)
                    enableCharacteristicNotifications(gatt, notifSourceChar)
                }
            }

            // 3. Suscripción a AMS (Control multimedia nativo del sistema iOS)
            val amsService = gatt.getService(AppleBleUuids.AMS_SERVICE)
            amsService?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND)?.let {
                enableCharacteristicNotifications(gatt, it)
            }
            amsService?.getCharacteristic(AppleBleUuids.AMS_ENTITY_UPDATE)?.let { entityChar ->
                enableCharacteristicNotifications(gatt, entityChar)
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        entityChar, AmsEntityParser.SUBSCRIBE_PLAYER_PLAYBACK_INFO,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        nextGattOpId.incrementAndGet()
                    )
                )
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        entityChar, AmsEntityParser.SUBSCRIBE_TRACK_ARTIST_AND_TITLE,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        nextGattOpId.incrementAndGet()
                    )
                )
            }

            gatt.getService(AppleBleUuids.GAP_SERVICE)
                ?.getCharacteristic(AppleBleUuids.GAP_DEVICE_NAME_CHAR)
                ?.let { enqueueGattOperation(GattOperation.ReadCharacteristic(it, nextGattOpId.incrementAndGet())) }

            gatt.getService(AppleBleUuids.DIS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.DIS_MODEL_NUMBER_CHAR)
                ?.let { enqueueGattOperation(GattOperation.ReadCharacteristic(it, nextGattOpId.incrementAndGet())) }

            gatt.getService(AppleBleUuids.BAS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.BAS_BATTERY_LEVEL_CHAR)
                ?.let {
                    enableCharacteristicNotifications(gatt, it)
                    enqueueGattOperation(GattOperation.ReadCharacteristic(it, nextGattOpId.incrementAndGet()))
                }

            gatt.getService(AppleBleUuids.CTS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.CTS_CURRENT_TIME_CHAR)
                ?.let {
                    enableCharacteristicNotifications(gatt, it)
                    enqueueGattOperation(GattOperation.ReadCharacteristic(it, nextGattOpId.incrementAndGet()))
                }

            connectionWatchdogJob?.cancel()
            updateConnectionState(BleConnectionState.READY)
            updateAdvertisingStateForCurrentLink()
            startPeriodicSyncLoop()

            lifecycleScope.launch(Dispatchers.IO) {
                delay(1200)
                panController.evaluateAndArbitrateNetwork()
                syncTelemetryNow()
                delay(2500)
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            completeInFlightGattOperation()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    AppleBleUuids.GAP_DEVICE_NAME_CHAR ->
                        updateDetectedPhoneName(String(value, StandardCharsets.UTF_8).trim(), null)
                    AppleBleUuids.DIS_MODEL_NUMBER_CHAR ->
                        updateDetectedPhoneName(null, String(value, StandardCharsets.UTF_8).trim())
                    AppleBleUuids.CTS_CURRENT_TIME_CHAR -> handleCtsTimePayload(value)
                    AppleBleUuids.BAS_BATTERY_LEVEL_CHAR -> handleIphoneBatteryPayload(value)
                    AppleBleUuids.AMS_ENTITY_ATTRIBUTE -> {
                        val target = pendingAmsAttributeRequests.poll()
                        if (target != null && value.isNotEmpty()) {
                            _nowPlaying.update { current ->
                                AmsEntityParser.applyFullEntityAttribute(current, target.first, target.second, value)
                            }
                            scheduleThrottledMediaSync()
                        }
                    }
                    AppleBleUuids.BRIDGE_COMMAND_CONFIG_CHAR -> {
                        if (value.isNotEmpty()) {
                            handleCompanionBleCommand(String(value, StandardCharsets.UTF_8))
                        }
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            completeInFlightGattOperation()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            completeInFlightGattOperation()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                AppleBleUuids.ANCS_NOTIFICATION_SOURCE -> handleNotificationSourceEvent(value)
                AppleBleUuids.ANCS_DATA_SOURCE -> dataSourceCollector.onDataChunkReceived(value)
                AppleBleUuids.CTS_CURRENT_TIME_CHAR -> handleCtsTimePayload(value)
                AppleBleUuids.BAS_BATTERY_LEVEL_CHAR -> handleIphoneBatteryPayload(value)
                AppleBleUuids.AMS_ENTITY_UPDATE -> {
                    val updated = AmsEntityParser.applyEntityUpdate(_nowPlaying.value, value)
                    _nowPlaying.value = updated
                    if (updated.isTruncated && value.size >= 2) {
                        requestFullAmsEntityAttribute(gatt, value[0], value[1])
                    }
                    scheduleThrottledMediaSync()
                }
                AppleBleUuids.BRIDGE_COMMAND_CONFIG_CHAR -> {
                    if (value.isNotEmpty()) {
                        handleCompanionBleCommand(String(value, StandardCharsets.UTF_8))
                    }
                }
            }
        }
    }

    private fun requestFullAmsEntityAttribute(gatt: BluetoothGatt, entityId: Byte, attrId: Byte) {
        val entityAttrChar = gatt.getService(AppleBleUuids.AMS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.AMS_ENTITY_ATTRIBUTE) ?: return
        pendingAmsAttributeRequests.add((entityId.toInt() and 0xFF) to (attrId.toInt() and 0xFF))
        enqueueGattOperation(
            GattOperation.WriteCharacteristic(
                entityAttrChar,
                AmsEntityParser.buildEntityAttributeCommand(entityId, attrId),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                nextGattOpId.incrementAndGet()
            )
        )
        enqueueGattOperation(
            GattOperation.ReadCharacteristic(entityAttrChar, nextGattOpId.incrementAndGet())
        )
    }

    private fun scheduleThrottledMediaSync() {
        if (mediaPushJob?.isActive == true) return
        mediaPushJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(650L)
            syncTelemetryNow()
        }
    }

    private fun updateDetectedPhoneName(newGapName: String?, newModelCode: String?) {
        val cleanCandidate = newGapName?.trim()
        if (!cleanCandidate.isNullOrBlank()) {
            val isGenericIphone = cleanCandidate.equals("iPhone", ignoreCase = true)
            if (!isGenericIphone || rawGapDeviceName.isNullOrBlank()) {
                rawGapDeviceName = cleanCandidate
            }
        }
        if (!newModelCode.isNullOrBlank()) {
            rawDisModelCode = newModelCode.trim()
        }

        val resolved = AppleDeviceModelResolver.formatDisplayIdentity(
            gapName = rawGapDeviceName ?: connectedIphone?.name,
            modelCode = rawDisModelCode
        )
        _connectedDeviceName.value = resolved
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_IPHONE_NAME, resolved).apply()
        BridgeStatusTileService.requestUpdate(this)
    }

    fun requestIphoneTimeSync() {
        val char = activeGatt?.getService(AppleBleUuids.CTS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.CTS_CURRENT_TIME_CHAR) ?: return
        enqueueGattOperation(GattOperation.ReadCharacteristic(char, nextGattOpId.incrementAndGet()))
    }

    fun requestIphoneBatteryLevel() {
        val char = activeGatt?.getService(AppleBleUuids.BAS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.BAS_BATTERY_LEVEL_CHAR) ?: return
        enqueueGattOperation(GattOperation.ReadCharacteristic(char, nextGattOpId.incrementAndGet()))
    }

    private fun handleIphoneBatteryPayload(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val pct = (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
        updatePhoneBattery(pct)
    }

    private fun updatePhoneBattery(pct: Int) {
        _iphoneBatteryPercent.value = pct
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LAST_IPHONE_BATTERY, pct).apply()
        BridgeStatusTileService.requestUpdate(this)
    }

    private fun handleCtsTimePayload(bytes: ByteArray) {
        val parsed = CtsTimeParser.parseCurrentTime(bytes) ?: return
        saveClockDrift(parsed.clockDriftMillis)
        _syncedIphoneTime.value = parsed.copy(systemClockUpdated = false)
    }

    fun triggerRemoteCamera() {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE))
        if (remoteCameraReady) {
            notifyCompanionWatchEvent(JSONObject(BridgeProtocol.encodeCameraEvent(capture = true)))
        } else {
            _cameraPrompt.value = "Abriendo la cámara en el iPhone…"
            notifyCompanionWatchEvent(JSONObject(BridgeProtocol.encodeCameraEvent(capture = false)))
        }
    }

    private fun handleCameraStatus(status: String) {
        when (status) {
            BridgeProtocol.CAMERA_STATUS_READY -> {
                remoteCameraReady = true
                _cameraPrompt.value = "Listo. Tocá de nuevo para disparar."
            }
            BridgeProtocol.CAMERA_STATUS_NEED_FOREGROUND -> {
                remoteCameraReady = false
                _cameraPrompt.value = "Abrí la app en el iPhone"
            }
            BridgeProtocol.CAMERA_STATUS_DENIED -> {
                remoteCameraReady = false
                _cameraPrompt.value = "Permití cámara y Fotos en el iPhone"
            }
            BridgeProtocol.CAMERA_STATUS_CAPTURED -> {
                remoteCameraReady = true
                _cameraPrompt.value = "Foto guardada en el carrete"
            }
            BridgeProtocol.CAMERA_STATUS_SAVING -> {
                _cameraPrompt.value = "Guardando foto…"
            }
            else -> {
                remoteCameraReady = false
                _cameraPrompt.value = "No se pudo usar la cámara"
            }
        }
    }

    fun toggleFindMyIphone() {
        if (_isFindingPhone.value) {
            stopFindMyIphone()
        } else {
            ringMyIphone()
        }
    }

    fun ringMyIphone() {
        _isFindingPhone.value = true
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))

        notifyCompanionWatchEvent(
            JSONObject().apply {
                put("evt", "FIND_PHONE")
                put("active", true)
            }
        )

        lifecycleScope.launch {
            if (!_phoneContext.value.companionConnected) {
                sendAmsMediaCommand(AmsRemoteCommandId.PLAY)
                repeat(4) {
                    delay(200)
                    sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_UP)
                }
            }
            delay(16_000L)
            _isFindingPhone.value = false
        }
    }

    fun stopFindMyIphone() {
        _isFindingPhone.value = false
        notifyCompanionWatchEvent(
            JSONObject().apply {
                put("evt", "FIND_PHONE")
                put("active", false)
            }
        )
    }

    private fun enableCharacteristicNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(AppleBleUuids.CCCD) ?: return
        enqueueGattOperation(
            GattOperation.WriteDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, nextGattOpId.incrementAndGet())
        )
    }

    private fun handleNotificationSourceEvent(payload: ByteArray) {
        val header = AncsProtocol.parseNotificationSource(payload) ?: return
        when (header.eventId) {
            AncsEventId.ADDED, AncsEventId.MODIFIED -> {
                if (header.isPreExisting && !header.isIncomingCall) return
                if (_notificationMode.value.blocksDelivery) return
                if (header.isSilent && !_allowDndNotifications.value && !header.isIncomingCall) return

                if (silentFlagsByUid.size >= MAX_TRACKED_UID_METADATA) {
                    silentFlagsByUid.keys().nextElement()?.let { silentFlagsByUid.remove(it) }
                }
                if (categoryByUid.size >= MAX_TRACKED_UID_METADATA) {
                    categoryByUid.keys().nextElement()?.let { categoryByUid.remove(it) }
                }
                silentFlagsByUid[header.notificationUid] = header.isSilent
                categoryByUid[header.notificationUid] = header.categoryId

                val controlPoint = activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
                    ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return
                val cmdBytes = AncsProtocol.buildGetNotificationAttributesCommand(header.notificationUid)
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        controlPoint, cmdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        nextGattOpId.incrementAndGet()
                    )
                )
            }
            AncsEventId.REMOVED -> {
                silentFlagsByUid.remove(header.notificationUid)
                categoryByUid.remove(header.notificationUid)
                removeNotificationByUid(header.notificationUid)
            }
        }
    }

    private fun removeNotificationByUid(uid: Int) {
        if (activeCallUid == uid) {
            stopCallVibrationIfNeeded()
        }
        _notifications.update { list -> list.filterNot { it.uid == uid } }
        postedSystemNotificationUids.remove(uid)
        notificationManager.cancel(uid)
    }

    private fun handleParsedIosNotification(notification: AncsNotification) {
        if (_blockedBundles.value.contains(notification.appIdentifier) && !notification.isIncomingCall) return
        if (_notificationMode.value.blocksDelivery) return

        val bundleId = notification.appIdentifier
        if (bundleId.isNotBlank() && !AppBundleResolver.hasResolvedName(bundleId) && pendingAppNameQueries.add(bundleId)) {
            activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT)
                ?.let { controlPoint ->
                    val appAttrCmd = AncsProtocol.buildGetAppAttributesCommand(bundleId)
                    enqueueGattOperation(
                        GattOperation.WriteCharacteristic(
                            controlPoint, appAttrCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                            nextGattOpId.incrementAndGet()
                        )
                    )
                }
        }

        _notifications.update { current ->
            (listOf(notification) + current.filterNot { it.uid == notification.uid }).take(40)
        }
        postLocalWearNotification(notification, forceVibrate = notification.isIncomingCall)

        notifyCompanionWatchEvent(
            JSONObject().apply {
                put("evt", "NOTIF_LOG")
                put("uid", notification.uid)
                put("app", notification.appIdentifier.take(36))
                put("appName", notification.appDisplayName.take(20))
                put("title", notification.title.take(36))
                put("msg", notification.message.ifEmpty { notification.subtitle }.take(64))
                put("ts", notification.timestamp)
            }
        )
    }

    private fun handleSystemNotificationAction(uid: Int, op: String) {
        val target = _notifications.value.firstOrNull { it.uid == uid }
        when (op) {
            BlePassiveScanReceiver.OP_POSITIVE -> {
                if (target != null) answerOrAcceptNotification(target)
                else performNotificationAction(uid, AncsActionId.POSITIVE)
            }
            BlePassiveScanReceiver.OP_NEGATIVE,
            BlePassiveScanReceiver.OP_DISMISSED -> {
                if (target != null) rejectOrDismissNotification(target)
                else {
                    performNotificationAction(uid, AncsActionId.NEGATIVE)
                    notifyCompanionWatchEvent(JSONObject().put("evt", "NOTIF_DISMISS").put("uid", uid))
                    removeNotificationByUid(uid)
                }
            }
        }
    }

    fun answerOrAcceptNotification(item: AncsNotification) {
        stopCallVibrationIfNeeded()
        performNotificationAction(item.uid, AncsActionId.POSITIVE)
        if (item.isIncomingCall) {
            hfpController.answerIncomingCall()
        }
        notifyCompanionWatchEvent(JSONObject().put("evt", "NOTIF_POSITIVE").put("uid", item.uid))
        removeNotificationByUid(item.uid)
    }

    fun rejectOrDismissNotification(item: AncsNotification) {
        stopCallVibrationIfNeeded()
        performNotificationAction(item.uid, AncsActionId.NEGATIVE)
        if (item.isIncomingCall) {
            hfpController.rejectOrHangupCall()
        }
        notifyCompanionWatchEvent(JSONObject().put("evt", "NOTIF_DISMISS").put("uid", item.uid))
        removeNotificationByUid(item.uid)
    }

    fun performNotificationAction(uid: Int, action: AncsActionId) {
        val controlPoint = activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT)
        if (controlPoint != null) {
            val cmd = AncsProtocol.buildPerformNotificationActionCommand(uid, action)
            enqueueGattOperation(
                GattOperation.WriteCharacteristic(
                    controlPoint, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    nextGattOpId.incrementAndGet()
                )
            )
        }
        if (action == AncsActionId.NEGATIVE) {
            removeNotificationByUid(uid)
        }
    }

    fun sendAmsMediaCommand(command: AmsRemoteCommandId) {
        val char = activeGatt?.getService(AppleBleUuids.AMS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND)
        if (char != null) {
            enqueueGattOperation(
                GattOperation.WriteCharacteristic(
                    char, byteArrayOf(command.code), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    nextGattOpId.incrementAndGet()
                )
            )
        }
        notifyCompanionWatchEvent(
            JSONObject().apply {
                put("evt", "MEDIA_CMD")
                put("action", command.name)
            }
        )
    }

    fun syncTelemetryNow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = telemetryManager.collectTelemetrySnapshot(getSavedClockDrift())
            _latestTelemetry.value = snapshot
            notifyCompanionTelemetry(snapshot)
            BridgeStatusTileService.requestUpdate(this@AncsBleService)
        }
    }

    /**
     * Encola operaciones GATT protegiendo siempre las suscripciones de descriptores CCCD (jamás se descartan)
     * y utilizando un token de operación en vuelo (`inFlightOpId`) para evitar condiciones de carrera por timeout.
     */
    @Synchronized
    private fun enqueueGattOperation(operation: GattOperation) {
        if (gattQueue.size >= MAX_GATT_QUEUE_SIZE) {
            val nonDescriptor = gattQueue.firstOrNull { it !is GattOperation.WriteDescriptor }
            if (nonDescriptor != null) {
                gattQueue.remove(nonDescriptor)
            } else {
                gattQueue.poll()
            }
        }
        gattQueue.add(operation)
        if (inFlightOpId == 0L) processNextGattOperation()
    }

    @Synchronized
    private fun completeInFlightGattOperation(expectedOpId: Long? = null) {
        if (expectedOpId != null && inFlightOpId != expectedOpId) {
            return
        }
        gattOpTimeoutJob?.cancel()
        inFlightOpId = 0L
        processNextGattOperation()
    }

    @Synchronized
    private fun processNextGattOperation() {
        val gatt = activeGatt ?: return
        if (inFlightOpId != 0L) return
        val nextOp = gattQueue.poll() ?: return
        val currentOpId = nextOp.opId
        inFlightOpId = currentOpId

        gattOpTimeoutJob?.cancel()
        gattOpTimeoutJob = lifecycleScope.launch {
            delay(GATT_OP_TIMEOUT_MS)
            completeInFlightGattOperation(expectedOpId = currentOpId)
        }

        val dispatched = runCatching {
            when (nextOp) {
                is GattOperation.ReadCharacteristic -> gatt.readCharacteristic(nextOp.characteristic)
                is GattOperation.WriteDescriptor ->
                    gatt.writeDescriptor(nextOp.descriptor, nextOp.payload) == BluetoothStatusCodes.SUCCESS
                is GattOperation.WriteCharacteristic ->
                    gatt.writeCharacteristic(nextOp.characteristic, nextOp.payload, nextOp.writeType) == BluetoothStatusCodes.SUCCESS
            }
        }.getOrDefault(false)

        if (!dispatched) {
            completeInFlightGattOperation(expectedOpId = currentOpId)
        }
    }

    private fun stopCallVibrationIfNeeded() {
        activeCallUid = null
        runCatching {
            val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            vibrator.cancel()
        }
    }

    /**
     * Construye un PendingIntent inmutable con requestCode libre de overflow de Int.
     */
    private fun buildNotificationOpPendingIntent(uid: Int, op: String, requestCodeOffset: Int): PendingIntent {
        val intent = Intent(this, BlePassiveScanReceiver::class.java).apply {
            action = BlePassiveScanReceiver.ACTION_NOTIFICATION_ACTION
            putExtra(BlePassiveScanReceiver.EXTRA_NOTIF_UID, uid)
            putExtra(BlePassiveScanReceiver.EXTRA_NOTIF_OP, op)
        }
        val safeRequestCode = AncsProtocol.computeNotificationRequestCode(uid, requestCodeOffset)
        return PendingIntent.getBroadcast(
            this,
            safeRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postLocalWearNotification(item: AncsNotification, forceVibrate: Boolean = false) {
        val isIncomingCall = item.isIncomingCall
        val mode = _notificationMode.value
        if (mode.blocksDelivery) {
            return
        }

        val mutedDelivery = mode == WatchNotificationMode.MUTE && !forceVibrate && !isIncomingCall
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        val isPriorityApp = _priorityBundles.value.contains(item.appIdentifier)

        if (!mutedDelivery) {
            when {
                isIncomingCall -> {
                    activeCallUid = item.uid
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 600), 0))
                }
                isPriorityApp -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 280), -1))
                else -> vibrator.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else if (isIncomingCall) {
            activeCallUid = item.uid
        }

        val bodyText = buildString {
            if (item.subtitle.isNotEmpty()) append("${item.subtitle}\n")
            append(item.message)
        }.ifEmpty { item.appDisplayName }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            AncsProtocol.computeNotificationRequestCode(item.uid, 0),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deletePendingIntent = buildNotificationOpPendingIntent(
            item.uid, BlePassiveScanReceiver.OP_DISMISSED, 1
        )
        val positivePendingIntent = buildNotificationOpPendingIntent(
            item.uid, BlePassiveScanReceiver.OP_POSITIVE, 2
        )
        val negativePendingIntent = buildNotificationOpPendingIntent(
            item.uid, BlePassiveScanReceiver.OP_NEGATIVE, 3
        )

        val channelId = when {
            isIncomingCall -> CALLS_CHANNEL_ID
            mutedDelivery -> SILENT_ALERTS_CHANNEL_ID
            else -> ALERTS_CHANNEL_ID
        }

        val builder = Notification.Builder(this, channelId)
            .setSmallIcon(if (isIncomingCall) android.R.drawable.sym_call_incoming else android.R.drawable.sym_action_chat)
            .setContentTitle(if (isIncomingCall) item.title else "${item.appDisplayName}: ${item.title}")
            .setContentText(bodyText)
            .setStyle(Notification.BigTextStyle().bigText(bodyText))
            .setCategory(if (isIncomingCall) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setAutoCancel(!isIncomingCall)
            .setOngoing(isIncomingCall)

        if (isIncomingCall) {
            builder.setFullScreenIntent(contentPendingIntent, true)
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_call),
                    item.positiveActionLabel.ifEmpty { "Contestar" },
                    positivePendingIntent
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    item.negativeActionLabel.ifEmpty { "Rechazar" },
                    negativePendingIntent
                ).build()
            )
        } else {
            if (item.positiveActionLabel.isNotBlank()) {
                builder.addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_input_add),
                        item.positiveActionLabel,
                        positivePendingIntent
                    ).build()
                )
            }

            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    item.negativeActionLabel.ifEmpty { "Descartar" },
                    negativePendingIntent
                ).build()
            )
        }

        postedSystemNotificationUids.add(item.uid)
        notificationManager.notify(item.uid, builder.build())
    }

    private fun updateConnectionState(newState: BleConnectionState) {
        _connectionState.value = newState
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_CONN_LABEL, newState.label).apply()
        BridgeStatusTileService.requestUpdate(this)
    }

    private fun saveTargetIphoneMac(mac: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_IPHONE_MAC, mac).apply()
    }

    private fun getSavedIphoneMac(): String? =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_IPHONE_MAC, null)

    private fun saveClockDrift(driftMs: Long) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_CLOCK_DRIFT_MS, driftMs).apply()
    }

    private fun getSavedClockDrift(): Long =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_CLOCK_DRIFT_MS, 0L)

    private fun createNotificationChannels() {
        runCatching { notificationManager.deleteNotificationChannel(LEGACY_FG_CHANNEL_ID) }
        runCatching { notificationManager.deleteNotificationChannel("ancs_bg_link_silent_v2") }

        val silentBgChannel = NotificationChannel(
            FG_CHANNEL_ID,
            "Servicio en segundo plano (Silencioso)",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        notificationManager.createNotificationChannel(silentBgChannel)

        notificationManager.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Mensajes del iPhone", NotificationManager.IMPORTANCE_HIGH)
        )
        val silentAlerts = NotificationChannel(
            SILENT_ALERTS_CHANNEL_ID,
            "Mensajes del iPhone (silencio)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(silentAlerts)
        notificationManager.createNotificationChannel(
            NotificationChannel(CALLS_CHANNEL_ID, "Llamadas Entrantes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildSilentForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Watch Bridge")
            .setContentText("Activo en segundo plano")
            .setContentIntent(pendingIntent)
            .setLocalOnly(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setGroup("ancs_silent_bg_service")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }
}

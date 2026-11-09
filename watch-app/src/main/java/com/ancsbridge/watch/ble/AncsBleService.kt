package com.ancsbridge.watch.ble

import android.Manifest
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@SuppressLint("MissingPermission")
class AncsBleService : LifecycleService() {

    companion object {
        private const val TAG = "AncsBleService"
        private const val FG_CHANNEL_ID = "ancs_foreground_channel"
        private const val ALERTS_CHANNEL_ID = "ancs_ios_alerts_channel"
        private const val CALLS_CHANNEL_ID = "ancs_ios_calls_channel"
        private const val FG_NOTIFICATION_ID = 1001
        private const val PASSIVE_SCAN_REQUEST_CODE = 2002
        private const val MAX_GATT_QUEUE_SIZE = 64
        private const val GATT_OP_TIMEOUT_MS = 5000L
        private const val BLE_TELEMETRY_SYNC_INTERVAL_MS = 180_000L // Cada 3 min (ahorro de batería)

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
    private lateinit var hidController: HidShutterController
    lateinit var telemetryManager: TelemetrySyncManager
        private set

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var bridgeTelemetryChar: BluetoothGattCharacteristic? = null
    private var bridgeWatchEventChar: BluetoothGattCharacteristic? = null
    private val subscribedCompanionDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    private var activeGatt: BluetoothGatt? = null
    private var connectedIphone: BluetoothDevice? = null

    @Volatile private var isAdvertising = false
    @Volatile private var isConnectingOrConnected = false
    @Volatile private var activeCallUid: Int? = null

    private var rawGapDeviceName: String? = null
    private var rawDisModelCode: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var gattOpTimeoutJob: Job? = null
    private var periodicSyncJob: Job? = null

    private val silentFlagsByUid = ConcurrentHashMap<Int, Boolean>()
    private val categoryByUid = ConcurrentHashMap<Int, Int>()

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

    private val _isWatchMuted = MutableStateFlow(false)
    val isWatchMuted: StateFlow<Boolean> = _isWatchMuted.asStateFlow()

    private val _syncedIphoneTime = MutableStateFlow<SyncedIphoneTime?>(null)
    val syncedIphoneTime: StateFlow<SyncedIphoneTime?> = _syncedIphoneTime.asStateFlow()

    private val _notifications = MutableStateFlow<List<AncsNotification>>(emptyList())
    val notifications: StateFlow<List<AncsNotification>> = _notifications.asStateFlow()

    private val _latestTelemetry = MutableStateFlow<WatchTelemetrySnapshot?>(null)
    val latestTelemetry: StateFlow<WatchTelemetrySnapshot?> = _latestTelemetry.asStateFlow()

    val isHfpConnected: StateFlow<Boolean> get() = hfpController.isHfpConnected
    val isHidConnected: StateFlow<Boolean> get() = hidController.isHidConnected
    val isPanConnected: StateFlow<Boolean> get() = panController.isPanConnected
    val autoPanEnabled: StateFlow<Boolean> get() = panController.autoPanEnabled
    val networkMode: StateFlow<WatchNetworkMode> get() = panController.networkMode
    val healthStatus: StateFlow<HealthConnectStatus> get() = telemetryManager.healthStatus

    private val _blockedBundles = MutableStateFlow<Set<String>>(emptySet())
    val blockedBundles: StateFlow<Set<String>> = _blockedBundles.asStateFlow()

    private sealed class GattOperation {
        data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : GattOperation()
        data class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val payload: ByteArray) : GattOperation()
        data class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray,
            val writeType: Int
        ) : GattOperation()
    }

    private val gattQueue = ConcurrentLinkedQueue<GattOperation>()
    @Volatile private var isGattBusy = false

    private val dataSourceCollector = AncsDataSourceCollector { parsed ->
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
                                hidController.bindToPhone(d)
                            }
                            activeGatt?.discoverServices()
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
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        hfpController = HfpCallController(this, bluetoothManager.adapter).also { it.initialize() }
        panController = BluetoothPanController(this, bluetoothManager.adapter).also { it.initialize() }
        hidController = HidShutterController(this, bluetoothManager.adapter, lifecycleScope).also { it.initialize() }
        telemetryManager = TelemetrySyncManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _allowDndNotifications.value = prefs.getBoolean(KEY_ALLOW_DND_NOTIFS, true)
        _isWatchMuted.value = prefs.getBoolean(KEY_WATCH_MUTED, false)
        _blockedBundles.value = prefs.getStringSet(KEY_BLOCKED_BUNDLES, setOf("com.apple.tips")) ?: emptySet()
        prefs.getString(KEY_LAST_IPHONE_NAME, null)?.let { _connectedDeviceName.value = it }
        val cachedPhoneBat = prefs.getInt(KEY_LAST_IPHONE_BATTERY, -1)
        if (cachedPhoneBat in 0..100) _iphoneBatteryPercent.value = cachedPhoneBat

        createNotificationChannels()
        promoteToForegroundSafely("Iniciando enlace BLE...")

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        BleReconnectWorker.enqueuePeriodicWatchdog(this)

        lifecycleScope.launch(Dispatchers.IO) {
            startBleBridgeIfNeeded()
            syncTelemetryNow()
        }
    }

    private fun promoteToForegroundSafely(statusText: String) {
        val notification = buildForegroundNotification(statusText)
        val hasBtConnect = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        runCatching {
            if (hasBtConnect) {
                ServiceCompat.startForeground(
                    this,
                    FG_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(FG_NOTIFICATION_ID, notification)
            }
        }.onFailure { err ->
            Log.w(TAG, "No se pudo promover FGS aún: ${err.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        promoteToForegroundSafely(_connectionState.value.label)

        when (intent?.action) {
            ACTION_FAST_RECONNECT -> {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device != null && _connectionState.value != BleConnectionState.READY) {
                    connectToIphoneGatt(device, autoConnect = false)
                }
            }
            ACTION_WATCHDOG_TICK -> {
                panController.evaluateAndArbitrateNetwork()
                if (_connectionState.value == BleConnectionState.READY && activeGatt != null) {
                    requestIphoneBatteryLevel()
                    requestIphoneTimeSync()
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
        stopCallVibrationIfNeeded()
        runCatching { unregisterReceiver(bondStateReceiver) }
        stopBleBridge()
        hfpController.release()
        panController.release()
        hidController.release()
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
        _isWatchMuted.value = muted
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WATCH_MUTED, muted).apply()
        syncTelemetryNow()
    }

    fun toggleWatchMuted(): Boolean {
        val next = !_isWatchMuted.value
        setWatchMuted(next)
        return next
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
        if (_connectionState.value == BleConnectionState.READY && activeGatt != null) {
            requestIphoneBatteryLevel()
            requestIphoneTimeSync()
            syncTelemetryNow()
        } else {
            reconnectAttempt = 0
            startBleBridgeIfNeeded()
        }
    }

    fun startBleBridge() = startBleBridgeIfNeeded()

    fun startBleBridgeIfNeeded() {
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        // El servidor GATT BridgeSyncService siempre debe estar activo para las apps nativas (iOS y Android)
        setupBridgeGattServer()

        if (_connectionState.value == BleConnectionState.READY && activeGatt != null) return

        val bondedIphone = findBondedPhone(adapter)
        if (bondedIphone != null) {
            saveTargetIphoneMac(bondedIphone.address)
            updateDetectedPhoneName(bondedIphone.name, null)
            connectToIphoneGatt(bondedIphone, autoConnect = true)
            startHardwareFilteredLowPowerScan(adapter, bondedIphone.address)
            startBalancedSolicitedAdvertising(adapter)
        } else {
            startBalancedSolicitedAdvertising(adapter)
        }
    }

    fun stopBleBridge() {
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        connectionWatchdogJob?.cancel()
        gattOpTimeoutJob?.cancel()
        periodicSyncJob?.cancel()
        stopActiveLowPowerScan()
        stopSafeAdvertising()
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
        isConnectingOrConnected = false
        subscribedCompanionDevices.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        gattQueue.clear()
        isGattBusy = false
        updateConnectionState(BleConnectionState.DISCONNECTED)
    }

    /**
     * Busca únicamente teléfonos emparejados (ignorando auriculares, relojes o parlantes Bluetooth).
     */
    private fun findBondedPhone(adapter: BluetoothAdapter): BluetoothDevice? {
        val savedMac = getSavedIphoneMac()
        val bonded = adapter.bondedDevices ?: return null

        // 1. Coincidencia exacta por MAC guardada previamente
        bonded.firstOrNull {
            savedMac != null && it.address.equals(savedMac, ignoreCase = true)
        }?.let { return it }

        // 2. Coincidencia por nombre explícito de iPhone o por clase Bluetooth PHONE
        return bonded.firstOrNull { device ->
            val name = device.name.orEmpty()
            val isPhoneClass = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
            name.contains("iPhone", ignoreCase = true) || isPhoneClass
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
        connectionWatchdogJob?.cancel()
        gattOpTimeoutJob?.cancel()
        periodicSyncJob?.cancel()
        gattQueue.clear()
        isGattBusy = false
        isConnectingOrConnected = false

        if (forceCacheRefresh) refreshGattCache(gatt)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        if (activeGatt == gatt) activeGatt = null

        if (subscribedCompanionDevices.isEmpty()) {
            updateConnectionState(BleConnectionState.DISCONNECTED)
            scheduleExponentialBackoffReconnect()
        }
    }

    private fun startHardwareFilteredLowPowerScan(adapter: BluetoothAdapter, targetMac: String?) {
        scanner = adapter.bluetoothLeScanner ?: return
        val filters = mutableListOf<ScanFilter>()

        if (!targetMac.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            filters.add(ScanFilter.Builder().setDeviceAddress(targetMac).build())
        }
        // También permitimos descubrir teléfonos que anuncien el servicio BridgeSync
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

        val intent = Intent(this, BlePassiveScanReceiver::class.java).apply {
            action = BlePassiveScanReceiver.ACTION_PASSIVE_BLE_FOUND
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, PASSIVE_SCAN_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        runCatching { scanner?.startScan(filters, settings, pendingIntent) }
        runCatching { scanner?.startScan(filters, settings, lowPowerScanCallback) }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = lifecycleScope.launch {
            delay(15_000L)
            stopActiveLowPowerScan()
        }
    }

    private fun stopActiveLowPowerScan() {
        runCatching { scanner?.stopScan(lowPowerScanCallback) }
    }

    private val lowPowerScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            stopActiveLowPowerScan()
            if (_connectionState.value != BleConnectionState.READY && !isConnectingOrConnected) {
                connectToIphoneGatt(device, autoConnect = false)
            }
        }
    }

    private fun scheduleExponentialBackoffReconnect() {
        if (!_autoReconnect.value) return
        reconnectJob?.cancel()

        val expSeconds = min(120L, (3.0 * 2.0.pow(reconnectAttempt.toDouble())).toLong())
        val totalDelaySeconds = expSeconds + Random.nextLong(1, 4)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)

        if (totalDelaySeconds >= 60L) {
            BleReconnectWorker.scheduleExponentialReconnect(this, totalDelaySeconds)
        } else {
            reconnectJob = lifecycleScope.launch(Dispatchers.IO) {
                delay(totalDelaySeconds * 1000L)
                triggerLowPowerFilteredReconnect()
            }
        }
    }

    private fun triggerLowPowerFilteredReconnect() {
        if (_connectionState.value == BleConnectionState.READY && activeGatt != null) return
        val adapter = bluetoothManager.adapter ?: return
        setupBridgeGattServer()
        val bonded = findBondedPhone(adapter)

        if (bonded != null) {
            saveTargetIphoneMac(bonded.address)
            updateDetectedPhoneName(bonded.name, null)
            connectToIphoneGatt(bonded, autoConnect = true)
            startHardwareFilteredLowPowerScan(adapter, bonded.address)
            startBalancedSolicitedAdvertising(adapter)
        } else {
            startBalancedSolicitedAdvertising(adapter)
        }
    }

    /**
     * Configura el servidor GATT local del reloj con:
     * 1) GENERIC_HEART_RATE_SERVICE (0x180D) para visibilidad estándar en Ajustes Bluetooth de iOS.
     * 2) BRIDGE_SYNC_SERVICE para sincronización directa sin nube con la App Nativa de iOS o Android.
     */
    private fun setupBridgeGattServer() {
        if (gattServer != null) return
        gattServer = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    saveTargetIphoneMac(device.address)
                    updateDetectedPhoneName(device.name, null)
                    hfpController.bindToIphone(device)
                    hidController.bindToPhone(device)
                    if (!isConnectingOrConnected && activeGatt == null) {
                        connectToIphoneGatt(device, autoConnect = false)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribedCompanionDevices.remove(device)
                    _phoneContext.update { it.copy(companionConnected = subscribedCompanionDevices.isNotEmpty()) }
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
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                if (characteristic.uuid == AppleBleUuids.BRIDGE_COMMAND_CONFIG_CHAR && value != null) {
                    subscribedCompanionDevices.add(device)
                    handleCompanionBleCommand(String(value, StandardCharsets.UTF_8))
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
                        syncTelemetryNow()
                    } else {
                        subscribedCompanionDevices.remove(device)
                    }
                }
            }
        })

        val hrService = BluetoothGattService(
            AppleBleUuids.GENERIC_HEART_RATE_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

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

        bridgeService.addCharacteristic(telemetryChar)
        bridgeService.addCharacteristic(commandConfigChar)
        bridgeService.addCharacteristic(watchEventChar)

        bridgeTelemetryChar = telemetryChar
        bridgeWatchEventChar = watchEventChar

        runCatching {
            gattServer?.addService(hrService)
            lifecycleScope.launch {
                delay(250)
                runCatching { gattServer?.addService(bridgeService) }
            }
        }
    }

    private fun handleCompanionBleCommand(rawJson: String) {
        runCatching {
            val json = JSONObject(rawJson)
            when (json.optString("cmd")) {
                "PING_WATCH" -> {
                    val title = json.optString("title", "Buscando tu reloj 🔔")
                    val msg = json.optString("msg", "Alerta enviada desde tu iPhone")
                    vibrateAlertPattern(forceIgnoreMute = true)
                    postLocalWearNotification(
                        AncsNotification(
                            uid = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                            appIdentifier = "com.ancsbridge.ios",
                            appDisplayName = _connectedDeviceName.value,
                            title = title,
                            subtitle = "",
                            message = msg
                        ),
                        forceVibrate = true
                    )
                }

                "SYNC_NOW" -> syncTelemetryNow()

                "SET_DND" -> {
                    val muted = json.optBoolean("muted", false)
                    setWatchMuted(muted)
                }

                "SET_BLOCKED" -> {
                    val arr = json.optJSONArray("bundles") ?: JSONArray()
                    val updated = mutableSetOf<String>()
                    for (i in 0 until arr.length()) updated.add(arr.getString(i))
                    updateBlockedBundles(updated)
                }

                "PHONE_CONTEXT" -> {
                    val phoneBat = json.optInt("bat", -1).takeIf { it in 0..100 }
                    val phoneChg = json.optBoolean("chg", false)
                    val tempC = if (json.has("tempC") && !json.isNull("tempC")) json.optInt("tempC") else null
                    val weather = json.optString("weather", "").takeIf { it.isNotBlank() }
                    val nextEvent = json.optString("nextEvent", "").takeIf { it.isNotBlank() }
                    val platform = json.optString("platform", "iOS")
                    val phoneName = json.optString("phoneName", "").takeIf { it.isNotBlank() }

                    if (phoneName != null) updateDetectedPhoneName(phoneName, null)
                    if (phoneBat != null) updatePhoneBattery(phoneBat)

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

    private fun notifyCompanionWatchEvent(eventJson: JSONObject) {
        val server = gattServer ?: return
        val char = bridgeWatchEventChar ?: return
        val bytes = eventJson.toString().toByteArray(StandardCharsets.UTF_8)
        for (device in subscribedCompanionDevices) {
            runCatching {
                server.notifyCharacteristicChanged(device, char, false, bytes)
            }
        }
    }

    private fun notifyCompanionTelemetry(snapshot: WatchTelemetrySnapshot) {
        val server = gattServer ?: return
        val char = bridgeTelemetryChar ?: return
        val bytes = telemetryManager.toBleJson(
            snapshot = snapshot,
            isWatchMuted = _isWatchMuted.value,
            isHfpConnected = hfpController.isHfpConnected.value,
            isHidConnected = hidController.isHidConnected.value
        ).toByteArray(StandardCharsets.UTF_8)

        for (device in subscribedCompanionDevices) {
            runCatching {
                server.notifyCharacteristicChanged(device, char, false, bytes)
            }
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
            isWatchMuted = _isWatchMuted.value,
            isHfpConnected = hfpController.isHfpConnected.value,
            isHidConnected = hidController.isHidConnected.value
        ).toByteArray(StandardCharsets.UTF_8)
    }

    private fun startBalancedSolicitedAdvertising(adapter: BluetoothAdapter) {
        if (isAdvertising) return
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0) // Publicidad continua de bajo consumo para reconexión inmediata
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceSolicitationUuid(ParcelUuid(AppleBleUuids.ANCS_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(AppleBleUuids.BRIDGE_SYNC_SERVICE))
            .build()

        runCatching {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            isAdvertising = true
            if (_connectionState.value == BleConnectionState.DISCONNECTED) {
                updateConnectionState(BleConnectionState.ADVERTISING)
            }
        }
    }

    private fun stopSafeAdvertising() {
        if (!isAdvertising) return
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        isAdvertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = (errorCode == ADVERTISE_FAILED_ALREADY_STARTED)
        }
    }

    @Synchronized
    private fun connectToIphoneGatt(device: BluetoothDevice, autoConnect: Boolean) {
        if (isConnectingOrConnected && activeGatt != null && connectedIphone?.address == device.address) {
            return
        }
        isConnectingOrConnected = true
        connectedIphone = device
        updateDetectedPhoneName(device.name, null)
        updateConnectionState(BleConnectionState.CONNECTING)

        runCatching { activeGatt?.close() }
        activeGatt = device.connectGatt(this, autoConnect, gattClientCallback, BluetoothDevice.TRANSPORT_LE)

        connectionWatchdogJob?.cancel()
        if (!autoConnect) {
            connectionWatchdogJob = lifecycleScope.launch {
                delay(25_000L)
                if (_connectionState.value != BleConnectionState.READY) {
                    resetGattAndReschedule(activeGatt, forceCacheRefresh = true)
                }
            }
        }
    }

    private fun startPeriodicSyncLoop() {
        periodicSyncJob?.cancel()
        periodicSyncJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BleConnectionState.READY) {
                delay(BLE_TELEMETRY_SYNC_INTERVAL_MS)
                if (_connectionState.value == BleConnectionState.READY) {
                    syncTelemetryNow()
                }
            }
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
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    connectionWatchdogJob?.cancel()
                    stopActiveLowPowerScan()

                    saveTargetIphoneMac(gatt.device.address)
                    updateDetectedPhoneName(gatt.device.name, null)
                    gattQueue.clear()
                    isGattBusy = false
                    dataSourceCollector.reset()

                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    gatt.requestMtu(247)

                    hfpController.bindToIphone(gatt.device)
                    panController.bindToIphone(gatt.device)
                    hidController.bindToPhone(gatt.device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    panController.evaluateAndArbitrateNetwork()
                    resetGattAndReschedule(gatt, forceCacheRefresh = false)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                updateConnectionState(BleConnectionState.BONDING)
                gatt.device.createBond()
            } else {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                resetGattAndReschedule(gatt, forceCacheRefresh = true)
                return
            }

            val ancsService = gatt.getService(AppleBleUuids.ANCS_SERVICE)
            if (ancsService == null) {
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                } else {
                    // Puede ser un teléfono Android usando BridgeSyncService sin ANCS de Apple
                    connectionWatchdogJob?.cancel()
                    updateConnectionState(BleConnectionState.READY)
                    startPeriodicSyncLoop()
                }
                return
            }

            // En ANCS siempre se debe suscribir primero a Data Source y luego a Notification Source
            val dataSourceChar = ancsService.getCharacteristic(AppleBleUuids.ANCS_DATA_SOURCE)
            val notifSourceChar = ancsService.getCharacteristic(AppleBleUuids.ANCS_NOTIFICATION_SOURCE)
            if (dataSourceChar != null && notifSourceChar != null) {
                enableCharacteristicNotifications(gatt, dataSourceChar)
                enableCharacteristicNotifications(gatt, notifSourceChar)
            }

            val amsService = gatt.getService(AppleBleUuids.AMS_SERVICE)
            amsService?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND)?.let {
                enableCharacteristicNotifications(gatt, it)
            }
            amsService?.getCharacteristic(AppleBleUuids.AMS_ENTITY_UPDATE)?.let { entityChar ->
                enableCharacteristicNotifications(gatt, entityChar)
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        entityChar, AmsEntityParser.SUBSCRIBE_PLAYER_PLAYBACK_INFO,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                )
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        entityChar, AmsEntityParser.SUBSCRIBE_TRACK_ARTIST_AND_TITLE,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                )
            }

            gatt.getService(AppleBleUuids.GAP_SERVICE)
                ?.getCharacteristic(AppleBleUuids.GAP_DEVICE_NAME_CHAR)
                ?.let { enqueueGattOperation(GattOperation.ReadCharacteristic(it)) }

            gatt.getService(AppleBleUuids.DIS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.DIS_MODEL_NUMBER_CHAR)
                ?.let { enqueueGattOperation(GattOperation.ReadCharacteristic(it)) }

            gatt.getService(AppleBleUuids.BAS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.BAS_BATTERY_LEVEL_CHAR)
                ?.let {
                    enableCharacteristicNotifications(gatt, it)
                    enqueueGattOperation(GattOperation.ReadCharacteristic(it))
                }

            gatt.getService(AppleBleUuids.CTS_SERVICE)
                ?.getCharacteristic(AppleBleUuids.CTS_CURRENT_TIME_CHAR)
                ?.let {
                    enableCharacteristicNotifications(gatt, it)
                    enqueueGattOperation(GattOperation.ReadCharacteristic(it))
                }

            connectionWatchdogJob?.cancel()
            updateConnectionState(BleConnectionState.READY)
            startPeriodicSyncLoop()

            lifecycleScope.launch(Dispatchers.IO) {
                delay(1500)
                panController.evaluateAndArbitrateNetwork()
                syncTelemetryNow()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            completeCurrentGattOperation()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    AppleBleUuids.GAP_DEVICE_NAME_CHAR ->
                        updateDetectedPhoneName(String(value, StandardCharsets.UTF_8).trim(), null)
                    AppleBleUuids.DIS_MODEL_NUMBER_CHAR ->
                        updateDetectedPhoneName(null, String(value, StandardCharsets.UTF_8).trim())
                    AppleBleUuids.CTS_CURRENT_TIME_CHAR -> handleCtsTimePayload(value)
                    AppleBleUuids.BAS_BATTERY_LEVEL_CHAR -> handleIphoneBatteryPayload(value)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            completeCurrentGattOperation()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            completeCurrentGattOperation()
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
                    _nowPlaying.update { current -> AmsEntityParser.applyEntityUpdate(current, value) }
                }
            }
        }
    }

    private fun updateDetectedPhoneName(newGapName: String?, newModelCode: String?) {
        if (!newGapName.isNullOrBlank()) rawGapDeviceName = newGapName
        if (!newModelCode.isNullOrBlank()) rawDisModelCode = newModelCode

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
        enqueueGattOperation(GattOperation.ReadCharacteristic(char))
    }

    fun requestIphoneBatteryLevel() {
        val char = activeGatt?.getService(AppleBleUuids.BAS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.BAS_BATTERY_LEVEL_CHAR) ?: return
        enqueueGattOperation(GattOperation.ReadCharacteristic(char))
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

    /**
     * Dispara el obturador de la cámara del iPhone/Android usando:
     * 1) Perfil nativo BluetoothHidDevice (Consumer Control Volume Up 0xE9).
     * 2) Evento BLE directo hacia la app nativa compañera.
     * 3) Respaldo AMS Volume Up.
     */
    fun triggerCameraShutter() {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE))

        val sentViaHid = hidController.sendShutterVolumeUpPulse()
        notifyCompanionWatchEvent(JSONObject().put("evt", "CAMERA_SHUTTER"))
        if (!sentViaHid) {
            sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_UP)
        }
    }

    /**
     * Hace sonar y parpadear el teléfono ("Buscar mi iPhone / Android"):
     * 1) Envía el evento FIND_PHONE por BLE a la app nativa (activa alarma por altavoz aún en modo Silencio + flash LED).
     * 2) Como respaldo si la app está cerrada, envía comando AMS PLAY + subida de volumen.
     */
    fun ringMyIphone() {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))

        notifyCompanionWatchEvent(
            JSONObject().apply {
                put("evt", "FIND_PHONE")
                put("active", true)
            }
        )

        lifecycleScope.launch {
            sendAmsMediaCommand(AmsRemoteCommandId.PLAY)
            repeat(4) {
                delay(200)
                sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_UP)
            }
        }
    }

    private fun enableCharacteristicNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(AppleBleUuids.CCCD) ?: return
        enqueueGattOperation(GattOperation.WriteDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    }

    private fun handleNotificationSourceEvent(payload: ByteArray) {
        val header = AncsProtocol.parseNotificationSource(payload) ?: return
        when (header.eventId) {
            AncsEventId.ADDED, AncsEventId.MODIFIED -> {
                // Ignoramos notificaciones viejas al reconectar, EXCEPTO llamadas entrantes activas (categoryId == 1)
                if (header.isPreExisting && !header.isIncomingCall) return
                if (header.isSilent && !_allowDndNotifications.value && !header.isIncomingCall) return

                silentFlagsByUid[header.notificationUid] = header.isSilent
                categoryByUid[header.notificationUid] = header.categoryId

                val controlPoint = activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
                    ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return
                val cmdBytes = AncsProtocol.buildGetNotificationAttributesCommand(header.notificationUid)
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        controlPoint, cmdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
        notificationManager.cancel(uid)
    }

    private fun handleParsedIosNotification(notification: AncsNotification) {
        if (_blockedBundles.value.contains(notification.appIdentifier) && !notification.isIncomingCall) return

        _notifications.update { current ->
            (listOf(notification) + current.filterNot { it.uid == notification.uid }).take(40)
        }
        postLocalWearNotification(notification, forceVibrate = notification.isIncomingCall)

        // Notificamos a la app nativa compañera por BLE para el historial y descubrimiento dinámico de apps
        notifyCompanionWatchEvent(
            JSONObject().apply {
                put("evt", "NOTIF_LOG")
                put("uid", notification.uid)
                put("app", notification.appIdentifier)
                put("appName", notification.appDisplayName)
                put("title", notification.title)
                put("msg", notification.message.ifEmpty { notification.subtitle })
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
                GattOperation.WriteCharacteristic(controlPoint, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
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
                    char, byteArrayOf(command.code), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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

    @Synchronized
    private fun enqueueGattOperation(operation: GattOperation) {
        if (gattQueue.size >= MAX_GATT_QUEUE_SIZE) gattQueue.poll()
        gattQueue.add(operation)
        if (!isGattBusy) processNextGattOperation()
    }

    @Synchronized
    private fun completeCurrentGattOperation() {
        gattOpTimeoutJob?.cancel()
        isGattBusy = false
        processNextGattOperation()
    }

    @Synchronized
    private fun processNextGattOperation() {
        val gatt = activeGatt ?: return
        if (isGattBusy) return
        val nextOp = gattQueue.poll() ?: return
        isGattBusy = true

        gattOpTimeoutJob?.cancel()
        gattOpTimeoutJob = lifecycleScope.launch {
            delay(GATT_OP_TIMEOUT_MS)
            if (isGattBusy) completeCurrentGattOperation()
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

        if (!dispatched) completeCurrentGattOperation()
    }

    private fun vibrateAlertPattern(forceIgnoreMute: Boolean = false) {
        if (_isWatchMuted.value && !forceIgnoreMute) return
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 400), -1))
    }

    private fun stopCallVibrationIfNeeded() {
        activeCallUid = null
        runCatching {
            val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            vibrator.cancel()
        }
    }

    private fun buildNotificationOpPendingIntent(uid: Int, op: String, requestCodeOffset: Int): PendingIntent {
        val intent = Intent(this, BlePassiveScanReceiver::class.java).apply {
            action = BlePassiveScanReceiver.ACTION_NOTIFICATION_ACTION
            putExtra(BlePassiveScanReceiver.EXTRA_NOTIF_UID, uid)
            putExtra(BlePassiveScanReceiver.EXTRA_NOTIF_OP, op)
        }
        return PendingIntent.getBroadcast(
            this,
            (uid * 10) + requestCodeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun postLocalWearNotification(item: AncsNotification, forceVibrate: Boolean = false) {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        val isIncomingCall = item.isIncomingCall
        val isPriorityApp = _priorityBundles.value.contains(item.appIdentifier)

        if (!_isWatchMuted.value || forceVibrate) {
            when {
                isIncomingCall -> {
                    activeCallUid = item.uid
                    // Vibración continua en bucle (repeat = 0) hasta atender, rechazar o cortar
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 600), 0))
                }
                isPriorityApp -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 280), -1))
                else -> vibrator.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        val bodyText = buildString {
            if (item.subtitle.isNotEmpty()) append("${item.subtitle}\n")
            append(item.message)
        }.ifEmpty { item.appDisplayName }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, item.uid, openAppIntent,
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

        val channelId = if (isIncomingCall) CALLS_CHANNEL_ID else ALERTS_CHANNEL_ID

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
                    android.R.drawable.ic_menu_call,
                    item.positiveActionLabel.ifEmpty { "Contestar" },
                    positivePendingIntent
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    item.negativeActionLabel.ifEmpty { "Rechazar" },
                    negativePendingIntent
                ).build()
            )
        } else {
            if (item.positiveActionLabel.isNotBlank()) {
                builder.addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_input_add,
                        item.positiveActionLabel,
                        positivePendingIntent
                    ).build()
                )
            }

            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    item.negativeActionLabel.ifEmpty { "Descartar" },
                    negativePendingIntent
                ).build()
            )
        }

        notificationManager.notify(item.uid, builder.build())
    }

    private fun updateConnectionState(newState: BleConnectionState) {
        _connectionState.value = newState
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_CONN_LABEL, newState.label).apply()
        notificationManager.notify(FG_NOTIFICATION_ID, buildForegroundNotification(newState.label))
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
        notificationManager.createNotificationChannel(
            NotificationChannel(FG_CHANNEL_ID, "Enlace Bluetooth", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Notificaciones del Teléfono", NotificationManager.IMPORTANCE_HIGH)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CALLS_CHANNEL_ID, "Llamadas Entrantes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(_connectedDeviceName.value)
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

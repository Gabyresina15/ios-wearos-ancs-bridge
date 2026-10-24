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
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ancsbridge.watch.BuildConfig
import com.ancsbridge.watch.health.HealthConnectStatus
import com.ancsbridge.watch.health.TelemetrySyncManager
import com.ancsbridge.watch.health.WatchTelemetrySnapshot
import com.ancsbridge.watch.hfp.HfpCallController
import com.ancsbridge.watch.pan.BluetoothPanController
import com.ancsbridge.watch.pan.WatchNetworkMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@SuppressLint("MissingPermission")
class AncsBleService : LifecycleService() {

    companion object {
        private const val TAG = "AncsBleService"
        private const val FG_CHANNEL_ID = "ancs_foreground_channel"
        private const val ALERTS_CHANNEL_ID = "ancs_ios_alerts_channel"
        private const val FG_NOTIFICATION_ID = 1001
        private const val PASSIVE_SCAN_REQUEST_CODE = 2002
        private const val MAX_GATT_QUEUE_SIZE = 64
        private const val GATT_OP_TIMEOUT_MS = 5000L
        private const val BLE_KEEPALIVE_INTERVAL_MS = 45_000L

        const val ACTION_FAST_RECONNECT = "com.ancsbridge.watch.ACTION_FAST_RECONNECT"
        const val ACTION_WATCHDOG_TICK = "com.ancsbridge.watch.ACTION_WATCHDOG_TICK"

        private const val PREFS_NAME = "ancs_bridge_prefs"
        private const val KEY_LAST_IPHONE_MAC = "last_iphone_mac"
        private const val KEY_LAST_IPHONE_NAME = "last_iphone_real_name"
        private const val KEY_ALLOW_DND_NOTIFS = "allow_dnd_notifs"
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
    private var activeGatt: BluetoothGatt? = null
    private var connectedIphone: BluetoothDevice? = null

    @Volatile private var isAdvertising = false
    @Volatile private var isConnectingOrConnected = false

    private var rawGapDeviceName: String? = null
    private var rawDisModelCode: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var gattOpTimeoutJob: Job? = null
    private var bleKeepAliveJob: Job? = null

    private val silentFlagsByUid = ConcurrentHashMap<Int, Boolean>()
    private val _priorityBundles = MutableStateFlow(
        setOf("com.apple.mobilephone", "net.whatsapp.WhatsApp", "com.apple.MobileSMS")
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

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _allowDndNotifications = MutableStateFlow(true)
    val allowDndNotifications: StateFlow<Boolean> = _allowDndNotifications.asStateFlow()

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
    val pendingQueueCount: StateFlow<Int> get() = telemetryManager.pendingQueueCount

    private val _blockedBundles = MutableStateFlow<Set<String>>(emptySet())

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
        val correctedTs = System.currentTimeMillis() + getSavedClockDrift()
        handleParsedIosNotification(parsed.copy(receivedInDndMode = wasSilent, timestamp = correctedTs))
    }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (device?.address == connectedIphone?.address) {
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            saveTargetIphoneMac(device.address)
                            updateDetectedPhoneName(device.name, null)
                            hfpController.bindToIphone(device)
                            panController.bindToIphone(device)
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
        telemetryManager = TelemetrySyncManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _allowDndNotifications.value = prefs.getBoolean(KEY_ALLOW_DND_NOTIFS, true)
        prefs.getString(KEY_LAST_IPHONE_NAME, null)?.let { _connectedDeviceName.value = it }

        createNotificationChannels()
        startForeground(FG_NOTIFICATION_ID, buildForegroundNotification("Iniciando enlace BLE..."))

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        BlePassiveScanReceiver.scheduleDozeHeartbeatAlarm(this, 20L)
        BleReconnectWorker.enqueuePeriodicWatchdog(this)

        lifecycleScope.launch(Dispatchers.IO) {
            startBleBridgeIfNeeded()
            syncTelemetryNow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        BlePassiveScanReceiver.scheduleDozeHeartbeatAlarm(this, 5L)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondStateReceiver)
        stopBleBridge()
        hfpController.release()
        panController.release()
        webSocket?.close(1000, "Service stopped")
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

    fun setAutoPanInternet(enabled: Boolean) {
        panController.setAutoPan(enabled)
    }

    fun refreshConnectionOrReconnect() {
        if (_connectionState.value == BleConnectionState.READY && activeGatt != null) {
            requestIphoneBatteryLevel()
            requestIphoneTimeSync()
            syncTelemetryNow()
            ensureWebSocketConnected()
        } else {
            reconnectAttempt = 0
            startBleBridgeIfNeeded()
        }
    }

    fun startBleBridge() = startBleBridgeIfNeeded()

    fun startBleBridgeIfNeeded() {
        if (_connectionState.value == BleConnectionState.READY && activeGatt != null) return
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        val bondedIphone = findBondedIphone(adapter)
        if (bondedIphone != null) {
            saveTargetIphoneMac(bondedIphone.address)
            updateDetectedPhoneName(bondedIphone.name, null)
            connectToIphoneGatt(bondedIphone, autoConnect = true)
            startHardwareFilteredLowPowerScan(adapter, bondedIphone.address)
        } else {
            setupMinimalGattServer()
            startBalancedSolicitedAdvertising(adapter)
        }
    }

    fun stopBleBridge() {
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        connectionWatchdogJob?.cancel()
        gattOpTimeoutJob?.cancel()
        bleKeepAliveJob?.cancel()
        stopActiveLowPowerScan()
        stopSafeAdvertising()
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
        isConnectingOrConnected = false
        runCatching { gattServer?.close() }
        gattServer = null
        gattQueue.clear()
        isGattBusy = false
        updateConnectionState(BleConnectionState.DISCONNECTED)
    }

    private fun findBondedIphone(adapter: BluetoothAdapter): BluetoothDevice? {
        val savedMac = getSavedIphoneMac()
        val bonded = adapter.bondedDevices ?: return null
        return bonded.firstOrNull {
            savedMac != null && it.address.equals(savedMac, ignoreCase = true)
        } ?: bonded.firstOrNull {
            it.name?.contains("iPhone", ignoreCase = true) == true ||
                it.name?.contains("Apple", ignoreCase = true) == true
        } ?: bonded.firstOrNull()
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
        bleKeepAliveJob?.cancel()
        gattQueue.clear()
        isGattBusy = false
        isConnectingOrConnected = false

        if (forceCacheRefresh) refreshGattCache(gatt)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        if (activeGatt == gatt) activeGatt = null

        updateConnectionState(BleConnectionState.DISCONNECTED)
        scheduleExponentialBackoffReconnect()
    }

    private fun startHardwareFilteredLowPowerScan(adapter: BluetoothAdapter, targetMac: String?) {
        scanner = adapter.bluetoothLeScanner ?: return
        val filters = mutableListOf<ScanFilter>()
        filters.add(
            ScanFilter.Builder()
                .setServiceSolicitationUuid(ParcelUuid(AppleBleUuids.ANCS_SERVICE))
                .build()
        )
        if (!targetMac.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            filters.add(ScanFilter.Builder().setDeviceAddress(targetMac).build())
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        val intent = Intent(this, BlePassiveScanReceiver::class.java).apply {
            action = "com.ancsbridge.watch.ACTION_PASSIVE_BLE_FOUND"
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
        val bonded = findBondedIphone(adapter)

        if (bonded != null) {
            saveTargetIphoneMac(bonded.address)
            updateDetectedPhoneName(bonded.name, null)
            connectToIphoneGatt(bonded, autoConnect = true)
            startHardwareFilteredLowPowerScan(adapter, bonded.address)
        } else {
            setupMinimalGattServer()
            startBalancedSolicitedAdvertising(adapter)
        }
    }

    private fun setupMinimalGattServer() {
        if (gattServer != null) return
        gattServer = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    saveTargetIphoneMac(device.address)
                    updateDetectedPhoneName(device.name, null)
                    if (!isConnectingOrConnected && activeGatt == null) {
                        connectToIphoneGatt(device, autoConnect = false)
                    }
                }
            }
        })
        val hrService = BluetoothGattService(
            AppleBleUuids.GENERIC_HEART_RATE_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        gattServer?.addService(hrService)
    }

    private fun startBalancedSolicitedAdvertising(adapter: BluetoothAdapter) {
        if (isAdvertising || _connectionState.value == BleConnectionState.READY) return
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        runCatching {
            if (adapter.name != "GW6-ANCS-Bridge") adapter.name = "GW6-ANCS-Bridge"
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(120_000)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceSolicitationUuid(ParcelUuid(AppleBleUuids.ANCS_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppleBleUuids.GENERIC_HEART_RATE_SERVICE))
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

    private fun startBleKeepAliveLoop() {
        bleKeepAliveJob?.cancel()
        bleKeepAliveJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BleConnectionState.READY) {
                delay(BLE_KEEPALIVE_INTERVAL_MS)
                if (_connectionState.value == BleConnectionState.READY) {
                    requestIphoneBatteryLevel()
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
                    stopSafeAdvertising()

                    saveTargetIphoneMac(gatt.device.address)
                    updateDetectedPhoneName(gatt.device.name, null)
                    gattQueue.clear()
                    isGattBusy = false
                    dataSourceCollector.reset()

                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    gatt.requestMtu(247)

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
                    resetGattAndReschedule(gatt, forceCacheRefresh = true)
                }
                return
            }

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
            startBleKeepAliveLoop()

            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000)
                panController.evaluateAndArbitrateNetwork()
                ensureWebSocketConnected()
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
        _iphoneBatteryPercent.value = (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
    }

    private fun handleCtsTimePayload(bytes: ByteArray) {
        val parsed = CtsTimeParser.parseCurrentTime(bytes) ?: return
        saveClockDrift(parsed.clockDriftMillis)
        _syncedIphoneTime.value = parsed.copy(systemClockUpdated = false)
    }

    fun triggerCameraShutter() {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        sendAmsMediaCommand(AmsRemoteCommandId.VOLUME_UP)
    }

    fun ringMyIphone() {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        lifecycleScope.launch {
            sendAmsMediaCommand(AmsRemoteCommandId.PLAY)
            repeat(5) {
                delay(220)
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
                if (header.isPreExisting) return
                if (header.isSilent && !_allowDndNotifications.value) return
                silentFlagsByUid[header.notificationUid] = header.isSilent

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
                _notifications.update { list -> list.filterNot { it.uid == header.notificationUid } }
                notificationManager.cancel(header.notificationUid)
            }
        }
    }

    private fun handleParsedIosNotification(notification: AncsNotification) {
        if (_blockedBundles.value.contains(notification.appIdentifier)) return
        _notifications.update { current ->
            (listOf(notification) + current.filterNot { it.uid == notification.uid }).take(35)
        }
        postLocalWearNotification(notification)
        sendNotificationToBackend(notification)
    }

    fun answerOrAcceptNotification(item: AncsNotification) {
        performNotificationAction(item.uid, AncsActionId.POSITIVE)
        if (item.appIdentifier == "com.apple.mobilephone") {
            hfpController.answerIncomingCall()
        }
    }

    fun rejectOrDismissNotification(item: AncsNotification) {
        performNotificationAction(item.uid, AncsActionId.NEGATIVE)
        if (item.appIdentifier == "com.apple.mobilephone") {
            hfpController.rejectOrHangupCall()
        }
    }

    fun performNotificationAction(uid: Int, action: AncsActionId) {
        val controlPoint = activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return
        val cmd = AncsProtocol.buildPerformNotificationActionCommand(uid, action)
        enqueueGattOperation(
            GattOperation.WriteCharacteristic(controlPoint, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        )
        if (action == AncsActionId.NEGATIVE) {
            _notifications.update { list -> list.filterNot { it.uid == uid } }
            notificationManager.cancel(uid)
        }
    }

    fun sendAmsMediaCommand(command: AmsRemoteCommandId) {
        val char = activeGatt?.getService(AppleBleUuids.AMS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND) ?: return
        enqueueGattOperation(
            GattOperation.WriteCharacteristic(
                char, byteArrayOf(command.code), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        )
    }

    fun syncTelemetryNow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = telemetryManager.collectTelemetrySnapshot(getSavedClockDrift())
            _latestTelemetry.value = snapshot
            val jsonPayload = telemetryManager.toJson(snapshot).toString()
            val sentViaWs = webSocket?.send(jsonPayload) ?: false
            if (sentViaWs) {
                telemetryManager.flushOfflineQueue()
            } else {
                val flushed = telemetryManager.enqueueAndFlushToBackend(snapshot)
                if (!flushed) BleReconnectWorker.enqueueNetworkFlush(this@AncsBleService)
            }
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

    private fun postLocalWearNotification(item: AncsNotification) {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        val isIncomingCall = item.appIdentifier == "com.apple.mobilephone"
        val isPriorityApp = _priorityBundles.value.contains(item.appIdentifier)

        when {
            isIncomingCall -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 200, 450), -1))
            isPriorityApp -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 280), -1))
            else -> vibrator.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        val bodyText = buildString {
            if (item.subtitle.isNotEmpty()) append("${item.subtitle}\n")
            append(item.message)
        }

        val wearNotif = Notification.Builder(this, ALERTS_CHANNEL_ID)
            .setSmallIcon(if (isIncomingCall) android.R.drawable.sym_call_incoming else android.R.drawable.sym_action_chat)
            .setContentTitle("${item.appDisplayName}: ${item.title}")
            .setContentText(bodyText)
            .setStyle(Notification.BigTextStyle().bigText(bodyText))
            .setCategory(if (isIncomingCall) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(item.uid, wearNotif)
    }

    private fun updateConnectionState(newState: BleConnectionState) {
        _connectionState.value = newState
        notificationManager.notify(FG_NOTIFICATION_ID, buildForegroundNotification(newState.label))
        sendStatusToBackend(newState)
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

    private fun ensureWebSocketConnected() {
        if (webSocket != null || panController.networkMode.value == WatchNetworkMode.OFFLINE) return
        val request = Request.Builder().url(BuildConfig.WS_BACKEND_URL).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sendStatusToBackend(_connectionState.value)
                syncTelemetryNow()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "REMOTE_ALERT" -> handleParsedIosNotification(
                            AncsNotification(
                                uid = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                                appIdentifier = "com.ancsbridge.webapp",
                                appDisplayName = _connectedDeviceName.value,
                                title = json.optString("title", "Alerta"),
                                subtitle = "",
                                message = json.optString("message", "")
                            )
                        )
                        "SYNC_CONFIG" -> {
                            val arr = json.optJSONArray("blockedBundles") ?: JSONArray()
                            val set = mutableSetOf<String>()
                            for (i in 0 until arr.length()) set.add(arr.getString(i))
                            _blockedBundles.value = set
                        }
                        "AMS_COMMAND" -> {
                            val cmd = json.optString("command")
                            AmsRemoteCommandId.entries.firstOrNull { it.name == cmd }?.let { sendAmsMediaCommand(it) }
                        }
                        "REQUEST_TELEMETRY" -> syncTelemetryNow()
                    }
                }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { webSocket = null }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { webSocket = null }
        })
    }

    private fun sendStatusToBackend(state: BleConnectionState) {
        val (batteryPct, isCharging) = telemetryManager.readBatteryState()
        val payload = JSONObject().apply {
            put("type", "WATCH_STATUS")
            put("state", state.name)
            put("stateLabel", state.label)
            put("hfpConnected", hfpController.isHfpConnected.value)
            put("panConnected", panController.isPanConnected.value)
            put("watchBatteryPercent", batteryPct)
            put("isCharging", isCharging)
            put("deviceModel", android.os.Build.MODEL)
            put("pairedIphone", _connectedDeviceName.value)
        }
        webSocket?.send(payload.toString())
    }

    private fun sendNotificationToBackend(item: AncsNotification) {
        val payload = JSONObject().apply {
            put("type", "ANCS_NOTIFICATION")
            put("uid", item.uid)
            put("appIdentifier", item.appIdentifier)
            put("appDisplayName", item.appDisplayName)
            put("title", item.title)
            put("subtitle", item.subtitle)
            put("message", item.message)
            put("timestamp", item.timestamp)
        }
        webSocket?.send(payload.toString())
    }

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(FG_CHANNEL_ID, "Conexión con iPhone", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Notificaciones y Llamadas", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        return Notification.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(_connectedDeviceName.value)
            .setContentText(statusText)
            .setOngoing(true)
            .build()
    }
}

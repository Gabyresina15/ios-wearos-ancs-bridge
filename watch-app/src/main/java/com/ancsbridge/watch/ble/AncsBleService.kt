package com.ancsbridge.watch.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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
import com.ancsbridge.watch.health.TelemetrySyncManager
import com.ancsbridge.watch.health.WatchTelemetrySnapshot
import com.ancsbridge.watch.hfp.HfpCallController
import com.ancsbridge.watch.pan.BluetoothPanController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class AncsBleService : LifecycleService() {

    companion object {
        private const val TAG = "AncsBleService"
        private const val FG_CHANNEL_ID = "ancs_foreground_channel"
        private const val ALERTS_CHANNEL_ID = "ancs_ios_alerts_channel"
        private const val FG_NOTIFICATION_ID = 1001

        const val ACTION_FAST_RECONNECT = "com.ancsbridge.watch.ACTION_FAST_RECONNECT"
        const val ACTION_WATCHDOG_TICK = "com.ancsbridge.watch.ACTION_WATCHDOG_TICK"

        private const val PREFS_NAME = "ancs_bridge_prefs"
        private const val KEY_LAST_IPHONE_MAC = "last_iphone_mac"
    }

    inner class LocalBinder : Binder() {
        fun getService(): AncsBleService = this@AncsBleService
    }

    private val binder = LocalBinder()
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var hfpController: HfpCallController
    private lateinit var panController: BluetoothPanController
    private lateinit var telemetryManager: TelemetrySyncManager

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGatt: BluetoothGatt? = null
    private var connectedIphone: BluetoothDevice? = null

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

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

    val healthStatus get() = telemetryManager.healthStatus
    val pendingQueueCount get() = telemetryManager.pendingQueueCount

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

    private val dataSourceCollector = AncsDataSourceCollector { parsedNotification ->
        handleParsedIosNotification(parsedNotification)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (device?.address == connectedIphone?.address && bondState == BluetoothDevice.BOND_BONDED) {
                    device?.let { d ->
                        saveTargetIphoneMac(d.address)
                        hfpController.bindToIphone(d)
                        panController.bindToIphone(d)
                    }
                    activeGatt?.discoverServices()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        hfpController = HfpCallController(this, bluetoothManager.adapter)
        panController = BluetoothPanController(this, bluetoothManager.adapter)
        telemetryManager = TelemetrySyncManager(this)

        createNotificationChannels()
        startForeground(FG_NOTIFICATION_ID, buildForegroundNotification("Iniciando motor BLE ANCS + HFP..."))

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        hfpController.initialize()
        panController.initialize()
        ensureWebSocketConnected()
        startBleBridge()
        BleReconnectWorker.enqueuePeriodicWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FAST_RECONNECT -> {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                device?.let { connectToIphoneGatt(it) }
            }
            ACTION_WATCHDOG_TICK -> {
                if (_connectionState.value == BleConnectionState.DISCONNECTED && _autoReconnect.value) {
                    startBleBridge()
                }
                syncTelemetryNow()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondStateReceiver)
        hfpController.release()
        panController.release()
        stopBleBridge()
        webSocket?.close(1000, "Service destroyed")
    }

    fun setAllowDndNotifications(enabled: Boolean) {
        _allowDndNotifications.value = enabled
    }

    fun setAutoPanInternet(enabled: Boolean) {
        panController.setAutoPan(enabled)
    }

    fun requestIphoneTimeSync() {
        val ctsChar = activeGatt?.getService(AppleBleUuids.CTS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.CTS_CURRENT_TIME_CHAR) ?: return
        enqueueGattOperation(GattOperation.ReadCharacteristic(ctsChar))
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnect.value = enabled
        if (enabled && _connectionState.value == BleConnectionState.DISCONNECTED) {
            startBleBridge()
        } else if (!enabled) {
            stopBleBridge()
        }
    }

    fun startBleBridge() {
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        adapter.name = "GW6-ANCS-Bridge"
        setupMinimalGattServer()
        startSolicitedAdvertising(adapter)

        adapter.bondedDevices?.firstOrNull()?.let { bondedDevice ->
            Log.i(TAG, "Intentando reconexión directa: ${bondedDevice.name}")
            connectToIphoneGatt(bondedDevice)
        }
    }

    fun stopBleBridge() {
        advertiser?.stopAdvertising(advertiseCallback)
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        gattServer?.close()
        gattServer = null
        gattQueue.clear()
        isGattBusy = false
        updateConnectionState(BleConnectionState.DISCONNECTED)
    }

    private fun setupMinimalGattServer() {
        if (gattServer != null) return
        gattServer = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "iPhone conectado al GATT Server: ${device.address}")
                    if (activeGatt == null) connectToIphoneGatt(device)
                }
            }
        })

        val hrService = BluetoothGattService(
            AppleBleUuids.GENERIC_HEART_RATE_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        gattServer?.addService(hrService)
    }

    private fun startSolicitedAdvertising(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceSolicitationUuid(ParcelUuid(AppleBleUuids.ANCS_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppleBleUuids.GENERIC_HEART_RATE_SERVICE))
            .build()

        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        updateConnectionState(BleConnectionState.ADVERTISING)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising BLE ANCS iniciado.")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Error advertising: $errorCode")
        }
    }

    private fun connectToIphoneGatt(device: BluetoothDevice) {
        connectedIphone = device
        updateConnectionState(BleConnectionState.CONNECTING)
        activeGatt = device.connectGatt(this, _autoReconnect.value, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Conectado. Negociando MTU...")
                    advertiser?.stopAdvertising(advertiseCallback)
                    gattQueue.clear()
                    isGattBusy = false
                    dataSourceCollector.reset()
                    gatt.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Desconectado (status=$status)")
                    gattQueue.clear()
                    isGattBusy = false
                    updateConnectionState(BleConnectionState.DISCONNECTED)
                    if (_autoReconnect.value) {
                        lifecycleScope.launch {
                            delay(2000)
                            startBleBridge()
                        }
                    }
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
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val ancsService = gatt.getService(AppleBleUuids.ANCS_SERVICE)
            if (ancsService == null) {
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) gatt.device.createBond()
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
            hfpController.bindToIphone(gatt.device)
            panController.bindToIphone(gatt.device)
            requestIphoneTimeSync()
            updateConnectionState(BleConnectionState.READY)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isGattBusy = false
            processNextGattOperation()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            isGattBusy = false
            processNextGattOperation()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            isGattBusy = false
            if (characteristic.uuid == AppleBleUuids.CTS_CURRENT_TIME_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                CtsTimeParser.parseCurrentTime(value)?.let { synced ->
                    _syncedIphoneTime.value = synced
                    runCatching {
                        val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                        alarm.setTime(synced.epochMillis)
                        _syncedIphoneTime.value = synced.copy(systemClockUpdated = true)
                        Log.i(TAG, "Reloj del sistema actualizado a ${synced.formattedTime}")
                    }.onFailure {
                        Log.w(TAG, "No se pudo ajustar el reloj del sistema (falta permiso SET_TIME?)", it)
                    }
                }
            }
            processNextGattOperation()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                AppleBleUuids.ANCS_NOTIFICATION_SOURCE -> handleNotificationSourceEvent(value)
                AppleBleUuids.ANCS_DATA_SOURCE -> dataSourceCollector.onDataChunkReceived(value)
                else -> {}
            }
        }
    }

    private fun enableCharacteristicNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(AppleBleUuids.CCCD) ?: return
        enqueueGattOperation(GattOperation.WriteDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    }

    private fun handleNotificationSourceEvent(payload: ByteArray) {
        val header = AncsProtocol.parseNotificationSource(payload) ?: return
        when (header.eventId) {
            AncsEventId.ADDED, AncsEventId.MODIFIED -> {
                if (header.isSilent && (!_allowDndNotifications.value || header.isPreExisting)) return
                val controlPoint = activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
                    ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return
                val cmd = AncsProtocol.buildGetNotificationAttributesCommand(header.notificationUid)
                enqueueGattOperation(GattOperation.WriteCharacteristic(controlPoint, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
            }
            AncsEventId.REMOVED -> {
                _notifications.update { it.filterNot { n -> n.uid == header.notificationUid } }
                notificationManager.cancel(header.notificationUid)
            }
        }
    }

    private fun handleParsedIosNotification(notification: AncsNotification) {
        if (_blockedBundles.value.contains(notification.appIdentifier)) return
        _notifications.update { (listOf(notification) + it.filterNot { n -> n.uid == notification.uid }).take(35) }
        postLocalWearNotification(notification)
        sendNotificationToBackend(notification)
    }

    fun answerOrAcceptNotification(item: AncsNotification) {
        if (item.appIdentifier == "com.apple.mobilephone") {
            if (!hfpController.answerIncomingCall()) {
                performNotificationAction(item.uid, AncsActionId.POSITIVE)
            }
        } else {
            performNotificationAction(item.uid, AncsActionId.POSITIVE)
        }
    }

    fun rejectOrDismissNotification(item: AncsNotification) {
        if (item.appIdentifier == "com.apple.mobilephone") {
            hfpController.rejectOrHangupCall()
        }
        performNotificationAction(item.uid, AncsActionId.NEGATIVE)
    }

    fun performNotificationAction(uid: Int, action: AncsActionId) {
        val controlPoint = activeGatt?.getService(AppleBleUuids.ANCS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return
        val cmd = AncsProtocol.buildPerformNotificationActionCommand(uid, action)
        enqueueGattOperation(GattOperation.WriteCharacteristic(controlPoint, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        if (action == AncsActionId.NEGATIVE) {
            _notifications.update { it.filterNot { n -> n.uid == uid } }
            notificationManager.cancel(uid)
        }
    }

    fun sendAmsMediaCommand(command: AmsRemoteCommandId) {
        val remote = activeGatt?.getService(AppleBleUuids.AMS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND) ?: return
        enqueueGattOperation(GattOperation.WriteCharacteristic(remote, byteArrayOf(command.code), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
    }

    fun syncTelemetryNow() {
        lifecycleScope.launch {
            val snapshot = telemetryManager.collectTelemetrySnapshot()
            _latestTelemetry.value = snapshot
            val json = telemetryManager.toJson(snapshot).toString()
            if (webSocket?.send(json) != true) {
                telemetryManager.enqueueAndFlushToBackend(snapshot)
            }
        }
    }

    @Synchronized
    private fun enqueueGattOperation(operation: GattOperation) {
        gattQueue.add(operation)
        if (!isGattBusy) processNextGattOperation()
    }

    @Synchronized
    private fun processNextGattOperation() {
        val gatt = activeGatt ?: return
        if (isGattBusy) return
        val next = gattQueue.poll() ?: return
        isGattBusy = true
        val result = when (next) {
            is GattOperation.ReadCharacteristic -> gatt.readCharacteristic(next.characteristic)
            is GattOperation.WriteDescriptor -> gatt.writeDescriptor(next.descriptor, next.payload)
            is GattOperation.WriteCharacteristic -> gatt.writeCharacteristic(next.characteristic, next.payload, next.writeType)
        }
        if (result != BluetoothStatusCodes.SUCCESS) {
            isGattBusy = false
            processNextGattOperation()
        }
    }

    private fun postLocalWearNotification(item: AncsNotification) {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        val isCall = item.appIdentifier == "com.apple.mobilephone"
        if (isCall) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        val body = buildString {
            if (item.subtitle.isNotEmpty()) append("${item.subtitle}\n")
            append(item.message)
        }
        val dndBadge = if (item.receivedInDndMode) " 🌙" else ""
        val notif = Notification.Builder(this, ALERTS_CHANNEL_ID)
            .setSmallIcon(if (isCall) android.R.drawable.sym_call_incoming else android.R.drawable.sym_action_chat)
            .setContentTitle("${item.appDisplayName}$dndBadge: ${item.title}")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(if (isCall) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(item.uid, notif)
    }

    private fun updateConnectionState(newState: BleConnectionState) {
        _connectionState.value = newState
        notificationManager.notify(FG_NOTIFICATION_ID, buildForegroundNotification(newState.label))
        sendStatusToBackend(newState)
    }

    private fun saveTargetIphoneMac(mac: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_IPHONE_MAC, mac).apply()
    }

    private fun ensureWebSocketConnected() {
        if (webSocket != null) return
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
                                appDisplayName = "Web Companion",
                                title = json.optString("title", "Alerta Web"),
                                subtitle = "Push Remoto",
                                message = json.optString("message", "")
                            )
                        )
                        "SYNC_CONFIG" -> {
                            val arr = json.optJSONArray("blockedBundles") ?: JSONArray()
                            _blockedBundles.value = (0 until arr.length()).map { arr.getString(it) }.toSet()
                        }
                        "AMS_COMMAND" -> {
                            val cmd = json.optString("command")
                            AmsRemoteCommandId.entries.firstOrNull { it.name == cmd }?.let { sendAmsMediaCommand(it) }
                        }
                        "REQUEST_TELEMETRY" -> syncTelemetryNow()
                        else -> {}
                    }
                }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { webSocket = null }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { webSocket = null }
        })
    }

    private fun sendStatusToBackend(state: BleConnectionState) {
        val (bat, charging) = telemetryManager.readBatteryState()
        val payload = JSONObject().apply {
            put("type", "WATCH_STATUS")
            put("state", state.name)
            put("stateLabel", state.label)
            put("hfpConnected", hfpController.isHfpConnected.value)
            put("panConnected", panController.isPanConnected.value)
            put("watchBatteryPercent", bat)
            put("isCharging", charging)
            put("deviceModel", "Galaxy Watch 6 Classic")
            put("pairedIphone", connectedIphone?.name ?: "iPhone 16 Pro Max")
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
            NotificationChannel(FG_CHANNEL_ID, "Enlace BLE iOS (Bajo Consumo)", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Notificaciones y Llamadas iPhone", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        return Notification.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Puente iOS ANCS + HFP")
            .setContentText(statusText)
            .setOngoing(true)
            .build()
    }
}

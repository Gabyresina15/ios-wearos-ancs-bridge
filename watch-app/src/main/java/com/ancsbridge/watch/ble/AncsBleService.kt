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
    }

    inner class LocalBinder : Binder() {
        fun getService(): AncsBleService = this@AncsBleService
    }

    private val binder = LocalBinder()
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var notificationManager: NotificationManager
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGatt: BluetoothGatt? = null
    private var connectedIphone: BluetoothDevice? = null

    // Estado reactivo expuesto a Jetpack Compose
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _notifications = MutableStateFlow<List<AncsNotification>>(emptyList())
    val notifications: StateFlow<List<AncsNotification>> = _notifications.asStateFlow()

    private val _blockedBundles = MutableStateFlow<Set<String>>(emptySet())

    // Cola secuencial de operaciones GATT (Android falla si se ejecutan 2 operaciones simultáneas)
    private sealed class GattOperation {
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

    // Cliente WebSocket hacia el Backend
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    // Receptor de eventos de emparejamiento (Bonding) con iOS
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (device?.address == connectedIphone?.address && bondState == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "iPhone emparejado (BOND_BONDED). Descubriendo servicios ANCS/AMS...")
                    activeGatt?.discoverServices()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannels()
        startForeground(FG_NOTIFICATION_ID, buildForegroundNotification("Iniciando motor BLE ANCS..."))

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        connectWebSocket()
        startBleBridge()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondStateReceiver)
        stopBleBridge()
        webSocket?.close(1000, "Service destroyed")
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnect.value = enabled
        if (enabled && _connectionState.value == BleConnectionState.DISCONNECTED) {
            startBleBridge()
        } else if (!enabled) {
            stopBleBridge()
        }
    }

    /**
     * Inicia el anuncio periférico (con Service Solicitation para ANCS) y levanta un GATT Server
     * mínimo para que el iPhone muestre el reloj en Ajustes > Bluetooth.
     */
    fun startBleBridge() {
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        // Renombrar el adaptador para identificarlo fácilmente en iOS
        adapter.name = "GW6-ANCS-Bridge"

        setupMinimalGattServer()
        startSolicitedAdvertising(adapter)

        // Si ya existe un iPhone vinculado previamente en el llavero Bluetooth del reloj, reconectar directo
        adapter.bondedDevices?.firstOrNull()?.let { bondedDevice ->
            Log.i(TAG, "Intentando reconexión directa a dispositivo vinculado: ${bondedDevice.name}")
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
                    Log.i(TAG, "iPhone conectado al GATT Server periférico: ${device.address}")
                    // En cuanto el iPhone nos conecta desde Ajustes > Bluetooth, abrimos nuestro cliente GATT hacia él
                    if (activeGatt == null) {
                        connectToIphoneGatt(device)
                    }
                }
            }
        })

        // Exponemos el servicio genérico 0x180D para que el filtro de Ajustes > Bluetooth de iOS muestre el reloj
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

        // Crítico: addServiceSolicitationUuid con ANCS indica a iOS que este periférico solicita ANCS
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
            Log.i(TAG, "Advertising BLE con Service Solicitation ANCS iniciado correctamente.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Error al iniciar BLE Advertising: código $errorCode")
        }
    }

    private fun connectToIphoneGatt(device: BluetoothDevice) {
        connectedIphone = device
        updateConnectionState(BleConnectionState.CONNECTING)
        // autoConnect = true permite que el stack BLE reconecte en hardware cuando el iPhone vuelva a rango
        activeGatt = device.connectGatt(
            this,
            _autoReconnect.value,
            gattClientCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Conectado al GATT Server del iPhone. Negociando MTU...")
                    advertiser?.stopAdvertising(advertiseCallback)
                    gattQueue.clear()
                    isGattBusy = false
                    dataSourceCollector.reset()
                    gatt.requestMtu(247)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Desconectado del iPhone (status=$status).")
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
            Log.i(TAG, "MTU negociado en $mtu bytes. Verificando estado de Bonding...")
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
                Log.w(TAG, "Servicio ANCS no visible aún. Forzando emparejamiento cifrado...")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                }
                return
            }

            // REGLA DE ORO ANCS: Suscribir SIEMPRE Data Source ANTES que Notification Source
            val dataSourceChar = ancsService.getCharacteristic(AppleBleUuids.ANCS_DATA_SOURCE)
            val notifSourceChar = ancsService.getCharacteristic(AppleBleUuids.ANCS_NOTIFICATION_SOURCE)

            if (dataSourceChar != null && notifSourceChar != null) {
                enableCharacteristicNotifications(gatt, dataSourceChar)
                enableCharacteristicNotifications(gatt, notifSourceChar)
            }

            // Suscribir Apple Media Service (AMS) si está disponible
            val amsService = gatt.getService(AppleBleUuids.AMS_SERVICE)
            val remoteCommandChar = amsService?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND)
            if (remoteCommandChar != null) {
                enableCharacteristicNotifications(gatt, remoteCommandChar)
            }

            updateConnectionState(BleConnectionState.READY)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            isGattBusy = false
            processNextGattOperation()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isGattBusy = false
            processNextGattOperation()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                AppleBleUuids.ANCS_NOTIFICATION_SOURCE -> handleNotificationSourceEvent(value)
                AppleBleUuids.ANCS_DATA_SOURCE -> dataSourceCollector.onDataChunkReceived(value)
            }
        }
    }

    private fun enableCharacteristicNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(AppleBleUuids.CCCD) ?: return
        enqueueGattOperation(
            GattOperation.WriteDescriptor(
                descriptor = cccd,
                payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        )
    }

    private fun handleNotificationSourceEvent(payload: ByteArray) {
        val header = AncsProtocol.parseNotificationSource(payload) ?: return

        when (header.eventId) {
            AncsEventId.ADDED, AncsEventId.MODIFIED -> {
                if (header.isSilent) return
                val controlPoint = activeGatt
                    ?.getService(AppleBleUuids.ANCS_SERVICE)
                    ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return

                val cmdBytes = AncsProtocol.buildGetNotificationAttributesCommand(header.notificationUid)
                enqueueGattOperation(
                    GattOperation.WriteCharacteristic(
                        characteristic = controlPoint,
                        payload = cmdBytes,
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                )
            }

            AncsEventId.REMOVED -> {
                _notifications.update { list -> list.filterNot { it.uid == header.notificationUid } }
                notificationManager.cancel(header.notificationUid)
            }
        }
    }

    private fun handleParsedIosNotification(notification: AncsNotification) {
        if (_blockedBundles.value.contains(notification.appIdentifier)) {
            Log.d(TAG, "Notificación ignorada por filtro de BundleID: ${notification.appIdentifier}")
            return
        }

        _notifications.update { current ->
            (listOf(notification) + current.filterNot { it.uid == notification.uid }).take(35)
        }

        postLocalWearNotification(notification)
        sendNotificationToBackend(notification)
    }

    /**
     * Ejecuta una acción sobre la notificación en el iPhone (Aceptar / Descartar) vía ANCS Control Point
     */
    fun performNotificationAction(uid: Int, action: AncsActionId) {
        val controlPoint = activeGatt
            ?.getService(AppleBleUuids.ANCS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.ANCS_CONTROL_POINT) ?: return

        val cmd = AncsProtocol.buildPerformNotificationActionCommand(uid, action)
        enqueueGattOperation(
            GattOperation.WriteCharacteristic(
                characteristic = controlPoint,
                payload = cmd,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        )

        if (action == AncsActionId.NEGATIVE) {
            _notifications.update { list -> list.filterNot { it.uid == uid } }
            notificationManager.cancel(uid)
        }
    }

    /**
     * Envía comandos multimedia (Play/Pause/Next/Prev/Vol) al iPhone vía Apple Media Service (AMS)
     */
    fun sendAmsMediaCommand(command: AmsRemoteCommandId) {
        val remoteCommandChar = activeGatt
            ?.getService(AppleBleUuids.AMS_SERVICE)
            ?.getCharacteristic(AppleBleUuids.AMS_REMOTE_COMMAND) ?: return

        enqueueGattOperation(
            GattOperation.WriteCharacteristic(
                characteristic = remoteCommandChar,
                payload = byteArrayOf(command.code),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        )
    }

    @Synchronized
    private fun enqueueGattOperation(operation: GattOperation) {
        gattQueue.add(operation)
        if (!isGattBusy) {
            processNextGattOperation()
        }
    }

    @Synchronized
    private fun processNextGattOperation() {
        val gatt = activeGatt ?: return
        if (isGattBusy) return
        val nextOp = gattQueue.poll() ?: return
        isGattBusy = true

        val result = when (nextOp) {
            is GattOperation.WriteDescriptor -> {
                gatt.writeDescriptor(nextOp.descriptor, nextOp.payload)
            }
            is GattOperation.WriteCharacteristic -> {
                gatt.writeCharacteristic(nextOp.characteristic, nextOp.payload, nextOp.writeType)
            }
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "Error despachando operación GATT: code=$result")
            isGattBusy = false
            processNextGattOperation()
        }
    }

    private fun postLocalWearNotification(item: AncsNotification) {
        val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))

        val bodyText = buildString {
            if (item.subtitle.isNotEmpty()) append("${item.subtitle}\n")
            append(item.message)
        }

        val wearNotif = Notification.Builder(this, ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("${item.appDisplayName}: ${item.title}")
            .setContentText(bodyText)
            .setStyle(Notification.BigTextStyle().bigText(bodyText))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(item.uid, wearNotif)
    }

    private fun updateConnectionState(newState: BleConnectionState) {
        _connectionState.value = newState
        notificationManager.notify(FG_NOTIFICATION_ID, buildForegroundNotification(newState.label))
        sendStatusToBackend(newState)
    }

    // =========================================================================
    // Sincronización WebSocket con Backend Node.js / Fastify
    // =========================================================================

    private fun connectWebSocket() {
        val request = Request.Builder().url(BuildConfig.WS_BACKEND_URL).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendStatusToBackend(_connectionState.value)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "REMOTE_ALERT" -> {
                            val title = json.optString("title", "Alerta Web")
                            val message = json.optString("message", "")
                            handleParsedIosNotification(
                                AncsNotification(
                                    uid = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                                    appIdentifier = "com.ancsbridge.webapp",
                                    appDisplayName = "Web Companion",
                                    title = title,
                                    subtitle = "Push Remoto",
                                    message = message
                                )
                            )
                        }
                        "SYNC_CONFIG" -> {
                            val blockedArray = json.optJSONArray("blockedBundles") ?: JSONArray()
                            val updatedSet = mutableSetOf<String>()
                            for (i in 0 until blockedArray.length()) {
                                updatedSet.add(blockedArray.getString(i))
                            }
                            _blockedBundles.value = updatedSet
                        }
                        "AMS_COMMAND" -> {
                            val cmdName = json.optString("command")
                            AmsRemoteCommandId.entries.firstOrNull { it.name == cmdName }?.let {
                                sendAmsMediaCommand(it)
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lifecycleScope.launch {
                    delay(5000)
                    connectWebSocket()
                }
            }
        })
    }

    private fun sendStatusToBackend(state: BleConnectionState) {
        val payload = JSONObject().apply {
            put("type", "WATCH_STATUS")
            put("state", state.name)
            put("stateLabel", state.label)
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
        val fgChannel = NotificationChannel(
            FG_CHANNEL_ID,
            "Enlace BLE iOS",
            NotificationManager.IMPORTANCE_LOW
        )
        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "Notificaciones de iPhone (ANCS)",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(fgChannel)
        notificationManager.createNotificationChannel(alertsChannel)
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        return Notification.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Puente iOS ANCS")
            .setContentText(statusText)
            .setOngoing(true)
            .build()
    }
}

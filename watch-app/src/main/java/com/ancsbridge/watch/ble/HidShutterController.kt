package com.ancsbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Controlador nativo Bluetooth HID (Human Interface Device) para disparar el obturador de la
 * app Cámara nativa de iOS y Android emulando el botón físico Volume Up (Consumer Control 0xE9).
 */
@SuppressLint("MissingPermission")
class HidShutterController(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HidShutterController"
        private const val REPORT_ID_CONSUMER: Int = 1

        // Descriptor HID Consumer Control estándar: Bit 0 = Volume Increment (0xE9), Bit 1 = Volume Decrement (0xEA)
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x0C.toByte(), // Usage Page (Consumer)
            0x09.toByte(), 0x01.toByte(), // Usage (Consumer Control)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), REPORT_ID_CONSUMER.toByte(), // Report ID (1)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), // Report Size (1 bit)
            0x95.toByte(), 0x02.toByte(), // Report Count (2 bits)
            0x09.toByte(), 0xE9.toByte(), // Usage (Volume Increment - dispara el obturador de cámara iOS/Android)
            0x09.toByte(), 0xEA.toByte(), // Usage (Volume Decrement)
            0x81.toByte(), 0x02.toByte(), // Input (Data, Var, Abs)
            0x95.toByte(), 0x06.toByte(), // Report Count (6 bits padding)
            0x81.toByte(), 0x03.toByte(), // Input (Const, Var, Abs)
            0xC0.toByte()                 // End Collection
        )
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var targetPhone: BluetoothDevice? = null
    private var pendingShutterPulse = false
    private val executor = Executors.newSingleThreadExecutor()

    private val _isHidConnected = MutableStateFlow(false)
    val isHidConnected: StateFlow<Boolean> = _isHidConnected.asStateFlow()

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) {
                _isHidConnected.value = false
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val connected = (state == BluetoothProfile.STATE_CONNECTED)
            _isHidConnected.value = connected
            if (connected && device != null && pendingShutterPulse) {
                pendingShutterPulse = false
                dispatchPulseAndDisconnect(device)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                _isHidConnected.value = false
            }
        }
    }

    fun initialize() {
        runCatching {
            adapter?.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        }.onFailure {
            Log.w(TAG, "Perfil HID_DEVICE no soportado: ${it.message}")
        }
    }

    private fun registerHidApp() {
        val hid = hidDevice ?: return
        // IMPORTANTE: Usamos SUBCLASS1_NONE (no SUBCLASS1_COMBO ni KEYBOARD) para que iOS
        // NO interprete el reloj como un teclado físico externo y NO oculte el teclado en pantalla del iPhone.
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "GW6 Remote Shutter",
            "Wear OS Camera Remote",
            "Samsung",
            BluetoothHidDevice.SUBCLASS1_NONE,
            HID_REPORT_DESCRIPTOR
        )
        runCatching {
            hid.registerApp(sdp, null, null, executor, hidCallback)
        }
    }

    /**
     * Guarda la referencia del iPhone sin mantener una sesión HID permanente,
     * evitando que iOS bloquee u oculte el teclado virtual en pantalla.
     */
    fun bindToPhone(device: BluetoothDevice) {
        targetPhone = device
        val hid = hidDevice ?: return
        runCatching {
            if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                hid.disconnect(device)
            }
        }
    }

    /**
     * Conecta bajo demanda el perfil HID, envía el pulso Consumer Control Volume Up (0x01 -> 0x00)
     * y desconecta inmediatamente el perfil HID para no interferir con el teclado virtual de iOS.
     */
    fun sendShutterVolumeUpPulse(): Boolean {
        val hid = hidDevice ?: return false
        val device = targetPhone ?: return false

        return runCatching {
            if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                dispatchPulseAndDisconnect(device)
            } else {
                pendingShutterPulse = true
                hid.connect(device)
            }
            true
        }.getOrDefault(false)
    }

    private fun dispatchPulseAndDisconnect(device: BluetoothDevice) {
        val hid = hidDevice ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                hid.sendReport(device, REPORT_ID_CONSUMER, byteArrayOf(0x01))
                delay(65)
                hid.sendReport(device, REPORT_ID_CONSUMER, byteArrayOf(0x00))
                delay(800)
                // Desconectamos HID después del disparo para que iOS jamás oculte su teclado virtual
                hid.disconnect(device)
            }
        }
    }

    fun release() {
        runCatching {
            hidDevice?.unregisterApp()
            hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        }
        hidDevice = null
        _isHidConnected.value = false
    }
}

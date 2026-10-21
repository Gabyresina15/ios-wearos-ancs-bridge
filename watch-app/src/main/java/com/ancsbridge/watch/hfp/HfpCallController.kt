package com.ancsbridge.watch.hfp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission", "PrivateApi")
class HfpCallController(private val context: Context, private val adapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "HfpCallController"
        private const val PROFILE_HEADSET_CLIENT = 16
        const val CALL_ACCEPT_NONE = 0
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var headsetClientProxy: BluetoothProfile? = null
    private var targetDevice: BluetoothDevice? = null

    private val _isHfpConnected = MutableStateFlow(false)
    val isHfpConnected: StateFlow<Boolean> = _isHfpConnected.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == PROFILE_HEADSET_CLIENT) {
                headsetClientProxy = proxy
                Log.i(TAG, "Proxy BluetoothHeadsetClient (HFP) enlazado.")
                targetDevice?.let { connectHfpProfile(it) }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PROFILE_HEADSET_CLIENT) {
                headsetClientProxy = null
                _isHfpConnected.value = false
            }
        }
    }

    fun initialize() {
        adapter?.getProfileProxy(context, profileListener, PROFILE_HEADSET_CLIENT)
    }

    fun bindToIphone(device: BluetoothDevice) {
        targetDevice = device
        connectHfpProfile(device)
    }

    fun connectHfpProfile(device: BluetoothDevice) {
        val proxy = headsetClientProxy ?: return
        runCatching {
            val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val initiated = connectMethod.invoke(proxy, device) as? Boolean ?: false
            val state = proxy.getConnectionState(device)
            _isHfpConnected.value = (state == BluetoothProfile.STATE_CONNECTED) || initiated
            Log.i(TAG, "Solicitud de conexión HFP a ${device.name}: initiated=$initiated, state=$state")
        }.onFailure { err ->
            Log.e(TAG, "No se pudo invocar connect() en BluetoothHeadsetClient", err)
        }
    }

    fun answerIncomingCall(): Boolean {
        val proxy = headsetClientProxy ?: return false
        val device = targetDevice ?: proxy.connectedDevices.firstOrNull() ?: return false

        return runCatching {
            enableWatchScoAudio()
            val acceptMethod = proxy.javaClass.getMethod(
                "acceptCall",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            acceptMethod.invoke(proxy, device, CALL_ACCEPT_NONE) as? Boolean ?: false
        }.getOrElse { err ->
            Log.e(TAG, "Error aceptando llamada por HFP", err)
            false
        }
    }

    fun rejectOrHangupCall(): Boolean {
        val proxy = headsetClientProxy ?: return false
        val device = targetDevice ?: proxy.connectedDevices.firstOrNull() ?: return false

        return runCatching {
            val rejectMethod = proxy.javaClass.getMethod("rejectCall", BluetoothDevice::class.java)
            val rejected = rejectMethod.invoke(proxy, device) as? Boolean ?: false
            if (!rejected) {
                val terminateMethod = proxy.javaClass.methods.find { it.name == "terminateCall" }
                terminateMethod?.invoke(proxy, device, null)
            }
            disableWatchScoAudio()
            true
        }.getOrElse { err ->
            Log.e(TAG, "Error colgando llamada por HFP", err)
            false
        }
    }

    fun dialNumber(phoneNumber: String): Boolean {
        val proxy = headsetClientProxy ?: return false
        val device = targetDevice ?: proxy.connectedDevices.firstOrNull() ?: return false

        return runCatching {
            enableWatchScoAudio()
            val dialMethod = proxy.javaClass.getMethod(
                "dial",
                BluetoothDevice::class.java,
                String::class.java
            )
            dialMethod.invoke(proxy, device, phoneNumber) != null
        }.getOrElse { false }
    }

    private fun enableWatchScoAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        audioManager.isSpeakerphoneOn = true
    }

    private fun disableWatchScoAudio() {
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun release() {
        disableWatchScoAudio()
        headsetClientProxy?.let { adapter?.closeProfileProxy(PROFILE_HEADSET_CLIENT, it) }
        headsetClientProxy = null
    }
}

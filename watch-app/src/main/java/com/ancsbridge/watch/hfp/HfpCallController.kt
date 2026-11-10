package com.ancsbridge.watch.hfp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class HfpCallController(
    private val context: Context,
    private val adapter: BluetoothAdapter?
) {
    companion object {
        private const val PROFILE_HEADSET_CLIENT = 16
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private var headsetClientProxy: BluetoothProfile? = null
    private var targetDevice: BluetoothDevice? = null

    private val _isHfpConnected = MutableStateFlow(false)
    val isHfpConnected: StateFlow<Boolean> = _isHfpConnected.asStateFlow()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == PROFILE_HEADSET_CLIENT) {
                headsetClientProxy = proxy
                targetDevice?.let { tryConnectHfpIfNeeded(proxy, it) }
                refreshConnectionState()
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
        headsetClientProxy?.let { tryConnectHfpIfNeeded(it, device) }
        refreshConnectionState()
    }

    private fun tryConnectHfpIfNeeded(proxy: BluetoothProfile, device: BluetoothDevice) {
        runCatching {
            if (device.bondState == BluetoothDevice.BOND_BONDED &&
                proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED
            ) {
                val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                connectMethod.invoke(proxy, device)
            }
        }
    }

    fun refreshConnectionState() {
        val proxy = headsetClientProxy ?: return
        runCatching {
            val connectedDevices = proxy.connectedDevices
            val device = targetDevice
            _isHfpConnected.value = if (device != null) {
                proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED ||
                    connectedDevices.any { it.address == device.address }
            } else {
                connectedDevices.isNotEmpty()
            }
        }
    }

    fun answerIncomingCall(): Boolean {
        enableWatchScoAudio()
        val proxy = headsetClientProxy
        val device = targetDevice ?: proxy?.connectedDevices?.firstOrNull()
        if (proxy != null && device != null) {
            val acceptedViaHfp = runCatching {
                val m = proxy.javaClass.getMethod("acceptCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                m.invoke(proxy, device, 0) as? Boolean ?: true
            }.getOrDefault(false)
            if (acceptedViaHfp) return true
        }

        return runCatching {
            @Suppress("DEPRECATION")
            telecomManager.acceptRingingCall()
            true
        }.getOrElse {
            dispatchHeadsetHookKey()
        }
    }

    fun rejectOrHangupCall(): Boolean {
        val proxy = headsetClientProxy
        val device = targetDevice ?: proxy?.connectedDevices?.firstOrNull()
        if (proxy != null && device != null) {
            runCatching {
                val rejectMethod = proxy.javaClass.getMethod("rejectCall", BluetoothDevice::class.java)
                rejectMethod.invoke(proxy, device)
            }
        }

        return runCatching {
            @Suppress("DEPRECATION")
            val ended = telecomManager.endCall()
            disableWatchScoAudio()
            ended
        }.getOrElse {
            disableWatchScoAudio()
            false
        }
    }

    private fun dispatchHeadsetHookKey(): Boolean {
        return runCatching {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            true
        }.getOrDefault(false)
    }

    private fun enableWatchScoAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
    }

    private fun disableWatchScoAudio() {
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun release() {
        disableWatchScoAudio()
        headsetClientProxy?.let { adapter?.closeProfileProxy(PROFILE_HEADSET_CLIENT, it) }
        headsetClientProxy = null
    }
}

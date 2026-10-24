package com.ancsbridge.watch.pan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission", "PrivateApi")
class BluetoothPanController(
    private val context: Context,
    private val adapter: BluetoothAdapter?
) {
    companion object {
        private const val TAG = "BluetoothPanController"
        private const val PROFILE_PAN = 5
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var panProxy: BluetoothProfile? = null
    private var targetIphone: BluetoothDevice? = null

    private val _isPanConnected = MutableStateFlow(false)
    val isPanConnected: StateFlow<Boolean> = _isPanConnected.asStateFlow()

    private val _autoPanEnabled = MutableStateFlow(true)
    val autoPanEnabled: StateFlow<Boolean> = _autoPanEnabled.asStateFlow()

    val networkMode = MutableStateFlow(WatchNetworkMode.OFFLINE)

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == PROFILE_PAN) {
                panProxy = proxy
                refreshConnectionState()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PROFILE_PAN) {
                panProxy = null
                _isPanConnected.value = false
            }
        }
    }

    fun initialize() {
        adapter?.getProfileProxy(context, profileListener, PROFILE_PAN)
    }

    fun bindToIphone(device: BluetoothDevice) {
        targetIphone = device
        refreshConnectionState()
    }

    fun setAutoPan(enabled: Boolean) {
        _autoPanEnabled.value = enabled
    }

    fun refreshConnectionState() {
        val proxy = panProxy ?: return
        runCatching {
            val connected = proxy.connectedDevices
            val device = targetIphone
            _isPanConnected.value = if (device != null) {
                proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED ||
                    connected.any { it.address == device.address }
            } else {
                connected.isNotEmpty()
            }
        }
    }

    fun evaluateAndArbitrateNetwork() {
        // Stub for now
    }

    fun release() {
        panProxy?.let { adapter?.closeProfileProxy(PROFILE_PAN, it) }
        panProxy = null
    }
}

enum class WatchNetworkMode {
    WIFI,
    PAN,
    OFFLINE
}

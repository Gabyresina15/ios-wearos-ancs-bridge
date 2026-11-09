package com.ancsbridge.watch.pan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class BluetoothPanController(
    private val context: Context,
    private val adapter: BluetoothAdapter?
) {
    companion object {
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

    private val _networkMode = MutableStateFlow(WatchNetworkMode.OFFLINE)
    val networkMode: StateFlow<WatchNetworkMode> = _networkMode.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            evaluateAndArbitrateNetwork()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            evaluateAndArbitrateNetwork()
        }

        override fun onLost(network: Network) {
            evaluateAndArbitrateNetwork()
        }
    }

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
                evaluateAndArbitrateNetwork()
            }
        }
    }

    fun initialize() {
        adapter?.getProfileProxy(context, profileListener, PROFILE_PAN)
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
        evaluateAndArbitrateNetwork()
    }

    fun bindToIphone(device: BluetoothDevice) {
        targetIphone = device
        refreshConnectionState()
    }

    fun setAutoPan(enabled: Boolean) {
        _autoPanEnabled.value = enabled
        evaluateAndArbitrateNetwork()
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
        evaluateAndArbitrateNetwork()
    }

    fun evaluateAndArbitrateNetwork() {
        runCatching {
            val activeNet = connectivityManager.activeNetwork
            val caps = activeNet?.let { connectivityManager.getNetworkCapabilities(it) }
            _networkMode.value = when {
                caps == null -> if (_isPanConnected.value) WatchNetworkMode.PAN else WatchNetworkMode.OFFLINE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> WatchNetworkMode.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> WatchNetworkMode.PAN
                else -> if (_isPanConnected.value) WatchNetworkMode.PAN else WatchNetworkMode.OFFLINE
            }
        }
    }

    fun release() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        panProxy?.let { adapter?.closeProfileProxy(PROFILE_PAN, it) }
        panProxy = null
    }
}

enum class WatchNetworkMode {
    WIFI,
    PAN,
    OFFLINE
}

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

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == PROFILE_PAN) {
                panProxy = proxy
                Log.i(TAG, "Proxy BluetoothPan enlazado.")
                if (_autoPanEnabled.value) {
                    targetIphone?.let { connectInternetViaPan(it) }
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PROFILE_PAN) {
                panProxy = null
                _isPanConnected.value = false
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) {
                Log.i(TAG, "Red Bluetooth PAN activa.")
                connectivityManager.bindProcessToNetwork(network)
                _isPanConnected.value = true
            }
        }

        override fun onLost(network: Network) {
            val activeCaps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) != true) {
                connectivityManager.bindProcessToNetwork(null)
                refreshPanConnectionState()
            }
        }
    }

    fun initialize() {
        adapter?.getProfileProxy(context, profileListener, PROFILE_PAN)
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    fun setAutoPan(enabled: Boolean) {
        _autoPanEnabled.value = enabled
        val device = targetIphone ?: return
        if (enabled) connectInternetViaPan(device) else disconnectPan(device)
    }

    fun bindToIphone(device: BluetoothDevice) {
        targetIphone = device
        if (_autoPanEnabled.value) connectInternetViaPan(device) else refreshPanConnectionState()
    }

    fun connectInternetViaPan(device: BluetoothDevice): Boolean {
        val proxy = panProxy ?: return false
        targetIphone = device
        return runCatching {
            val currentState = proxy.getConnectionState(device)
            if (currentState == BluetoothProfile.STATE_CONNECTED) {
                _isPanConnected.value = true
                return true
            }
            val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val initiated = connectMethod.invoke(proxy, device) as? Boolean ?: false
            Log.i(TAG, "Conectando PAN: initiated=$initiated")
            refreshPanConnectionState()
            initiated
        }.getOrElse { err ->
            Log.e(TAG, "Error connect PAN", err)
            false
        }
    }

    fun disconnectPan(device: BluetoothDevice): Boolean {
        val proxy = panProxy ?: return false
        return runCatching {
            val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            val result = disconnectMethod.invoke(proxy, device) as? Boolean ?: false
            _isPanConnected.value = false
            connectivityManager.bindProcessToNetwork(null)
            result
        }.getOrDefault(false)
    }

    fun refreshPanConnectionState() {
        val proxy = panProxy ?: return
        val device = targetIphone ?: return
        runCatching {
            val state = proxy.getConnectionState(device)
            _isPanConnected.value = (state == BluetoothProfile.STATE_CONNECTED)
        }
    }

    fun release() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        connectivityManager.bindProcessToNetwork(null)
        panProxy?.let { adapter?.closeProfileProxy(PROFILE_PAN, it) }
        panProxy = null
    }
}

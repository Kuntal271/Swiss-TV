package com.swissdark.launcher.platform.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** "Wi-Fi · Connected" for headers and "Wi-Fi · <name>" for quick settings. */
class NetworkAdapter(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow("Network · Checking")
    private val _name = MutableStateFlow("Network · Checking")
    val status: StateFlow<String> = _status
    val name: StateFlow<String> = _name

    fun start() {
        update(cm.getNetworkCapabilities(cm.activeNetwork))
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
            override fun onLost(network: Network) = update(null)
        })
    }

    private fun update(caps: NetworkCapabilities?) {
        when {
            caps == null -> {
                _status.value = "Offline"; _name.value = "Not connected"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                _status.value = "Ethernet · Connected"; _name.value = "Ethernet · Wired"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                _status.value = "Wi-Fi · Connected"
                val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')
                    ?: @Suppress("DEPRECATION") context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
                _name.value = if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID.trim('<', '>') || ssid.startsWith("<")) "Wi-Fi · Connected" else "Wi-Fi · $ssid"
            }
            else -> {
                _status.value = "Network · Connected"; _name.value = "Network · Connected"
            }
        }
    }
}

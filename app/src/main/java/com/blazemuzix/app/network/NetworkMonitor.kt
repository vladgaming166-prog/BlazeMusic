package com.blazemuzix.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Observes connectivity on every Android version: NetworkCallback on API 21+,
 * the legacy CONNECTIVITY_ACTION broadcast on API 19/20.
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableLiveData<Boolean>()
    val online: LiveData<Boolean> get() = _online

    val isOnline: Boolean
        get() = _online.value ?: queryOnline()

    init {
        _online.value = queryOnline()
        start()
    }

    @Suppress("DEPRECATION")
    private fun queryOnline(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun post() {
        val now = queryOnline()
        if (_online.value != now) _online.postValue(now)
    }

    @Suppress("DEPRECATION")
    private fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = post()
                    override fun onLost(network: Network) = post()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
                })
            } catch (_: Exception) {
                registerLegacy()
            }
        } else {
            registerLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        appContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = post()
        }, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }
}

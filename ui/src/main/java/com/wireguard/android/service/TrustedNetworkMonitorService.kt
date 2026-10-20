/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.TrustedEndpointChecker
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches network changes and, per configured tunnel, disconnects when that
 * tunnel's trusted endpoint is reachable (and reconnects when it is not).
 */
class TrustedNetworkMonitorService : Service() {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var evaluateJob: Job? = null
    private val evaluateMutex = Mutex()
    private val lastTrustedByTunnel = mutableMapOf<String, Boolean>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        registerNetworkCallback()
        Log.i(TAG, "Trusted network monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REEVALUATE -> scheduleEvaluate()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        scheduleEvaluate()
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        evaluateJob?.cancel()
        Log.i(TAG, "Trusted network monitor stopped")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.trusted_network_notification_title))
            .setContentText(getString(R.string.trusted_network_notification_text))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trusted_network_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.trusted_network_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleEvaluate()
            override fun onLost(network: Network) = scheduleEvaluate()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleEvaluate()
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun scheduleEvaluate() {
        evaluateJob?.cancel()
        evaluateJob = applicationScope.launch {
            evaluateMutex.withLock { evaluateNow() }
        }
    }

    private suspend fun evaluateNow() {
        if (!UserKnobs.shouldRunTrustedMonitor()) {
            Log.d(TAG, "Trusted monitor idle; skipping")
            return
        }
        val cm = connectivityManager ?: return
        val wifiNetwork = findWifiNetwork(cm)
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels()
        val watched = resolveWatchedTunnels(tunnels)
        if (watched.isEmpty()) {
            Log.d(TAG, "No tunnels configured for auto-disconnect")
            return
        }

        val managed = UserKnobs.autoManagedTunnels.first().toMutableSet()
        var managedChanged = false

        for (tunnel in watched) {
            val endpoint = UserKnobs.getTunnelTrustedEndpoint(tunnel.name)
            val pinned = UserKnobs.getTunnelTrustedKey(tunnel.name)
            val trusted = when {
                wifiNetwork == null -> false
                endpoint.isBlank() || pinned.isBlank() -> false
                else -> TrustedEndpointChecker.isTrusted(wifiNetwork, endpoint, pinned)
            }
            val previous = lastTrustedByTunnel[tunnel.name]
            if (previous == trusted) continue
            lastTrustedByTunnel[tunnel.name] = trusted
            Log.i(TAG, "Tunnel ${tunnel.name}: trusted=$trusted (endpoint=${endpoint.ifBlank { "-" }})")

            if (trusted) {
                if (tunnel.state == Tunnel.State.UP) {
                    try {
                        manager.setTunnelState(tunnel, Tunnel.State.DOWN)
                        if (managed.add(tunnel.name)) managedChanged = true
                        Log.i(TAG, "Disconnected ${tunnel.name} on trusted network")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to disconnect ${tunnel.name}", e)
                    }
                }
            } else if (managed.contains(tunnel.name) && tunnel.state != Tunnel.State.UP) {
                try {
                    manager.setTunnelState(tunnel, Tunnel.State.UP)
                    if (managed.remove(tunnel.name)) managedChanged = true
                    Log.i(TAG, "Reconnected ${tunnel.name} after leaving trusted network")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to reconnect ${tunnel.name}", e)
                }
            }
        }

        // Drop trust state for tunnels that are no longer watched
        val watchedNames = watched.map { it.name }.toSet()
        lastTrustedByTunnel.keys.retainAll(watchedNames)

        if (managedChanged) UserKnobs.setAutoManagedTunnels(managed)
    }

    private suspend fun resolveWatchedTunnels(tunnels: Iterable<ObservableTunnel>): List<ObservableTunnel> {
        val enabled = UserKnobs.tunnelAutoDisconnectNames.first()
        if (enabled.isEmpty()) return emptyList()
        return tunnels.filter { enabled.contains(it.name) }
    }

    private fun findWifiNetwork(cm: ConnectivityManager): Network? {
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return network
            }
        }
        return null
    }

    companion object {
        private const val TAG = "WireGuard/TrustedNetMon"
        private const val CHANNEL_ID = "trusted_network_monitor"
        private const val NOTIFICATION_ID = 42
        const val ACTION_REEVALUATE = "de.godeb.wireguard.action.REEVALUATE_TRUST"
        const val ACTION_STOP = "de.godeb.wireguard.action.STOP_TRUST_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, TrustedNetworkMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrustedNetworkMonitorService::class.java))
        }

        fun reevaluate(context: Context) {
            applicationScope.launch {
                if (!UserKnobs.shouldRunTrustedMonitor()) return@launch
                val intent = Intent(context, TrustedNetworkMonitorService::class.java)
                    .setAction(ACTION_REEVALUATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun syncRunning(context: Context) {
            applicationScope.launch {
                if (UserKnobs.shouldRunTrustedMonitor()) start(context) else stop(context)
            }
        }
    }
}

/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.wireguard.android.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object UserKnobs {
    private val ENABLE_KERNEL_MODULE = booleanPreferencesKey("enable_kernel_module")
    val enableKernelModule: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ENABLE_KERNEL_MODULE] ?: false
        }

    suspend fun setEnableKernelModule(enable: Boolean?) {
        Application.getPreferencesDataStore().edit {
            if (enable == null)
                it.remove(ENABLE_KERNEL_MODULE)
            else
                it[ENABLE_KERNEL_MODULE] = enable
        }
    }

    private val MULTIPLE_TUNNELS = booleanPreferencesKey("multiple_tunnels")
    val multipleTunnels: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[MULTIPLE_TUNNELS] ?: false
        }

    private val DARK_THEME = booleanPreferencesKey("dark_theme")
    val darkTheme: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[DARK_THEME] ?: false
        }

    suspend fun setDarkTheme(on: Boolean) {
        Application.getPreferencesDataStore().edit {
            it[DARK_THEME] = on
        }
    }

    /** Theme mode: "light", "dark", or "system" (default). */
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    val themeMode: Flow<String>
        get() = Application.getPreferencesDataStore().data.map {
            it[THEME_MODE] ?: "system"
        }

    suspend fun setThemeMode(mode: String) {
        Application.getPreferencesDataStore().edit {
            it[THEME_MODE] = mode
        }
    }

    /** Tunnels disconnected by the trusted-network monitor (restored when leaving). */
    private val AUTO_MANAGED_TUNNELS = stringSetPreferencesKey("auto_managed_tunnels")
    val autoManagedTunnels: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map {
            it[AUTO_MANAGED_TUNNELS] ?: emptySet()
        }

    suspend fun setAutoManagedTunnels(names: Set<String>) {
        Application.getPreferencesDataStore().edit {
            if (names.isEmpty()) it.remove(AUTO_MANAGED_TUNNELS)
            else it[AUTO_MANAGED_TUNNELS] = names
        }
    }

    /** Tunnel names that have per-tunnel smart auto-disconnect enabled. */
    private val TUNNEL_AUTO_DISCONNECT = stringSetPreferencesKey("tunnel_auto_disconnect")
    val tunnelAutoDisconnectNames: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map {
            it[TUNNEL_AUTO_DISCONNECT] ?: emptySet()
        }

    suspend fun isTunnelAutoDisconnectEnabled(tunnelName: String): Boolean {
        return tunnelAutoDisconnectNames.first().contains(tunnelName)
    }

    suspend fun setTunnelAutoDisconnectEnabled(tunnelName: String, enabled: Boolean) {
        Application.getPreferencesDataStore().edit { prefs ->
            val current = prefs[TUNNEL_AUTO_DISCONNECT]?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.add(tunnelName) else current.remove(tunnelName)
            if (current.isEmpty()) prefs.remove(TUNNEL_AUTO_DISCONNECT)
            else prefs[TUNNEL_AUTO_DISCONNECT] = current
        }
    }

    private fun tunnelEndpointKey(tunnelName: String) =
        stringPreferencesKey("tunnel_trusted_endpoint_$tunnelName")

    suspend fun getTunnelTrustedEndpoint(tunnelName: String): String {
        val key = tunnelEndpointKey(tunnelName)
        return Application.getPreferencesDataStore().data.map { it[key] ?: "" }.first()
    }

    suspend fun setTunnelTrustedEndpoint(tunnelName: String, url: String) {
        val key = tunnelEndpointKey(tunnelName)
        Application.getPreferencesDataStore().edit {
            if (url.isBlank()) it.remove(key)
            else it[key] = url.trim()
        }
    }

    suspend fun renameTunnelTrustedPrefs(oldName: String, newName: String) {
        val enabled = isTunnelAutoDisconnectEnabled(oldName)
        val endpoint = getTunnelTrustedEndpoint(oldName)
        setTunnelAutoDisconnectEnabled(oldName, false)
        setTunnelTrustedEndpoint(oldName, "")
        if (enabled) setTunnelAutoDisconnectEnabled(newName, true)
        if (endpoint.isNotBlank()) setTunnelTrustedEndpoint(newName, endpoint)
        val managed = autoManagedTunnels.first().toMutableSet()
        if (managed.remove(oldName)) {
            managed.add(newName)
            setAutoManagedTunnels(managed)
        }
    }

    suspend fun clearTunnelTrustedPrefs(tunnelName: String) {
        setTunnelAutoDisconnectEnabled(tunnelName, false)
        setTunnelTrustedEndpoint(tunnelName, "")
        val managed = autoManagedTunnels.first().toMutableSet()
        if (managed.remove(tunnelName)) setAutoManagedTunnels(managed)
    }

    suspend fun shouldRunTrustedMonitor(): Boolean {
        return tunnelAutoDisconnectNames.first().isNotEmpty()
    }

    private val ALLOW_REMOTE_CONTROL_INTENTS = booleanPreferencesKey("allow_remote_control_intents")
    val allowRemoteControlIntents: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[ALLOW_REMOTE_CONTROL_INTENTS] ?: false
        }

    private val RESTORE_ON_BOOT = booleanPreferencesKey("restore_on_boot")
    val restoreOnBoot: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map {
            it[RESTORE_ON_BOOT] ?: false
        }

    private val LAST_USED_TUNNEL = stringPreferencesKey("last_used_tunnel")
    val lastUsedTunnel: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            it[LAST_USED_TUNNEL]
        }

    suspend fun setLastUsedTunnel(lastUsedTunnel: String?) {
        Application.getPreferencesDataStore().edit {
            if (lastUsedTunnel == null)
                it.remove(LAST_USED_TUNNEL)
            else
                it[LAST_USED_TUNNEL] = lastUsedTunnel
        }
    }

    private val RUNNING_TUNNELS = stringSetPreferencesKey("enabled_configs")
    val runningTunnels: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map {
            it[RUNNING_TUNNELS] ?: emptySet()
        }

    suspend fun setRunningTunnels(runningTunnels: Set<String>) {
        Application.getPreferencesDataStore().edit {
            if (runningTunnels.isEmpty())
                it.remove(RUNNING_TUNNELS)
            else
                it[RUNNING_TUNNELS] = runningTunnels
        }
    }

    private val UPDATER_NEWER_VERSION_SEEN = stringPreferencesKey("updater_newer_version_seen")
    val updaterNewerVersionSeen: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            it[UPDATER_NEWER_VERSION_SEEN]
        }

    suspend fun setUpdaterNewerVersionSeen(newerVersionSeen: String?) {
        Application.getPreferencesDataStore().edit {
            if (newerVersionSeen == null)
                it.remove(UPDATER_NEWER_VERSION_SEEN)
            else
                it[UPDATER_NEWER_VERSION_SEEN] = newerVersionSeen
        }
    }

    private val UPDATER_NEWER_VERSION_CONSENTED = stringPreferencesKey("updater_newer_version_consented")
    val updaterNewerVersionConsented: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map {
            it[UPDATER_NEWER_VERSION_CONSENTED]
        }

    suspend fun setUpdaterNewerVersionConsented(newerVersionConsented: String?) {
        Application.getPreferencesDataStore().edit {
            if (newerVersionConsented == null)
                it.remove(UPDATER_NEWER_VERSION_CONSENTED)
            else
                it[UPDATER_NEWER_VERSION_CONSENTED] = newerVersionConsented
        }
    }
}

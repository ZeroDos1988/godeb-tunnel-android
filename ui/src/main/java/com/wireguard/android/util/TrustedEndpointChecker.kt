/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validates that the current network can reach a predefined internal endpoint
 * (e.g. the GoDeb web UI). Requests are bound to the given [Network] so they
 * bypass an active VPN tunnel.
 */
object TrustedEndpointChecker {
    private const val TAG = "WireGuard/TrustedEndpoint"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000
    const val EXPECTED_HEADER = "X-GoDeb-Trusted"
    const val EXPECTED_HEADER_VALUE = "1"

    suspend fun isTrusted(
        network: Network,
        endpointUrl: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank()) return@withContext false
        val url = try {
            URL(endpointUrl.trim())
        } catch (e: Exception) {
            Log.w(TAG, "Invalid trusted endpoint URL: $endpointUrl", e)
            return@withContext false
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (network.openConnection(url) as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("User-Agent", "GoDeb-WireGuard")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Endpoint $endpointUrl returned HTTP $code")
                return@withContext false
            }
            val header = connection.getHeaderField(EXPECTED_HEADER)
            if (header != null && header.equals(EXPECTED_HEADER_VALUE, ignoreCase = true)) {
                return@withContext true
            }
            // Accept 200 OK with empty/short body marker as secondary signal for simple probes.
            val body = connection.inputStream.bufferedReader().use { it.readText().trim() }
            val trusted = body.equals("trusted", ignoreCase = true) ||
                body.equals("ok", ignoreCase = true) ||
                body.contains("\"trusted\":true", ignoreCase = true)
            if (!trusted) {
                Log.d(TAG, "Endpoint reachable but payload/header not trusted")
            }
            trusted
        } catch (e: IOException) {
            Log.d(TAG, "Endpoint unreachable: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Endpoint check failed", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    fun normalizeBssid(bssid: String?): String? {
        if (bssid.isNullOrBlank() || bssid == "02:00:00:00:00:00") return null
        return bssid.lowercase().trim()
    }
}

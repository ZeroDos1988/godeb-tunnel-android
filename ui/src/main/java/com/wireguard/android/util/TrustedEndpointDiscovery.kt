/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Scans the current Wi-Fi IPv4 subnet for hosts that answer the GoDeb
 * trusted-network probe (`GET /api/trusted` with `X-GoDeb-Trusted: 1`).
 */
object TrustedEndpointDiscovery {
    private const val TAG = "WireGuard/TrustedDiscover"
    private val PORTS = intArrayOf(8080, 8443, 80, 443)
    private const val MAX_CONCURRENCY = 48

    data class Candidate(
        val url: String,
        val host: String,
        val port: Int,
        val isGateway: Boolean
    )

    suspend fun scan(context: Context): List<Candidate> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@withContext emptyList()
        val wifi = findWifi(cm) ?: return@withContext emptyList()
        val props = cm.getLinkProperties(wifi) ?: return@withContext emptyList()
        val prefix = ipv4Prefix(props) ?: return@withContext emptyList()
        val gateway = props.routes
            .mapNotNull { it.gateway as? Inet4Address }
            .firstOrNull()
            ?.hostAddress

        val hosts = expandHosts(prefix.first, prefix.second).toMutableList()
        // Probe the gateway first for snappier UX.
        if (gateway != null) {
            hosts.remove(gateway)
            hosts.add(0, gateway)
        }

        val found = ConcurrentLinkedQueue<Candidate>()
        val limiter = Semaphore(MAX_CONCURRENCY)
        coroutineScope {
            hosts.flatMap { host ->
                PORTS.map { port ->
                    async {
                        limiter.withPermit {
                            val url = probeUrl(host, port)
                            if (TrustedEndpointChecker.isTrusted(wifi, url, connectTimeoutMs = 400, readTimeoutMs = 400)) {
                                found.add(
                                    Candidate(
                                        url = url,
                                        host = host,
                                        port = port,
                                        isGateway = host == gateway
                                    )
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        found.toList()
            .sortedWith(compareByDescending<Candidate> { it.isGateway }.thenBy { it.host }.thenBy { it.port })
            .also { Log.i(TAG, "Discovery finished: ${it.size} endpoint(s)") }
    }

    private fun findWifi(cm: ConnectivityManager): Network? {
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

    private fun ipv4Prefix(props: LinkProperties): Pair<String, Int>? {
        for (link in props.linkAddresses) {
            val addr = link.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val prefix = link.prefixLength
                if (prefix in 16..30) return addr.hostAddress!! to prefix
            }
        }
        return null
    }

    private fun expandHosts(address: String, prefixLen: Int): List<String> {
        val ip = ipv4ToLong(address)
        val mask = if (prefixLen == 0) 0L else (-1L shl (32 - prefixLen)) and 0xffffffffL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xffffffffL)
        val hosts = ArrayList<String>((broadcast - network - 1).toInt().coerceAtLeast(0).coerceAtMost(1024))
        // Cap large subnets (/16) to avoid scanning 65k hosts.
        val maxHosts = 1022L
        var count = 0L
        var host = network + 1
        while (host < broadcast && count < maxHosts) {
            hosts.add(longToIpv4(host))
            host++
            count++
        }
        return hosts
    }

    private fun ipv4ToLong(ip: String): Long {
        val p = ip.split('.')
        require(p.size == 4)
        return (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
    }

    private fun longToIpv4(value: Long): String {
        return "${(value shr 24) and 0xff}.${(value shr 16) and 0xff}.${(value shr 8) and 0xff}.${value and 0xff}"
    }

    private fun probeUrl(host: String, port: Int): String {
        val scheme = if (port == 443 || port == 8443) "https" else "http"
        val default = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (default) "$scheme://$host/api/trusted" else "$scheme://$host:$port/api/trusted"
    }
}

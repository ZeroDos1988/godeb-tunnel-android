/*
 * Copyright © 2017-2025 WireGuard LLC / GoDeb. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Persists GoDeb AllowedIPs base / excluded CIDR comments across Config parse
 * (which strips comments) and FileConfigStore save/load.
 */
object GodebConfigMeta {
    data class Meta(
        val allowedIpsBase: String = "",
        val excludedIps: String = "",
        val trustedEndpoint: String = "",
        val trustedKey: String = "",
    ) {
        fun isEmpty() = allowedIpsBase.isBlank() && excludedIps.isBlank() &&
            trustedEndpoint.isBlank() && trustedKey.isBlank()
    }

    private val byTunnel = ConcurrentHashMap<String, Meta>()

    /** Updates the AllowedIPs comments and keeps the trust lines of the tunnel. */
    fun put(tunnelName: String, allowedIpsBase: String, excludedIps: String) {
        val prev = byTunnel[tunnelName] ?: Meta()
        putMeta(tunnelName, prev.copy(allowedIpsBase = allowedIpsBase.trim(), excludedIps = excludedIps.trim()))
    }

    fun putMeta(tunnelName: String, meta: Meta) {
        if (meta.isEmpty()) byTunnel.remove(tunnelName) else byTunnel[tunnelName] = meta
    }

    fun get(tunnelName: String): Meta = byTunnel[tunnelName] ?: Meta()

    fun remove(tunnelName: String) {
        byTunnel.remove(tunnelName)
    }

    fun rename(oldName: String, newName: String) {
        val meta = byTunnel.remove(oldName) ?: return
        byTunnel[newName] = meta
    }

    fun parseFromConf(text: String): Meta {
        var base = ""
        var excluded = ""
        var endpoint = ""
        var key = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#")) continue
            commentValue(line, "GoDeb-AllowedIPs-Base")?.let { base = it }
            commentValue(line, "GoDeb-ExcludedIPs")?.let { excluded = it }
            // A hub may list several sites; the first pair wins.
            if (endpoint.isEmpty()) commentValue(line, "GoDeb-Trusted-Endpoint")?.let { endpoint = it }
            if (key.isEmpty()) commentValue(line, "GoDeb-Trusted-Key")?.let { key = it }
        }
        return Meta(base, excluded, endpoint, key)
    }

    fun prependComments(body: String, meta: Meta): String {
        if (meta.isEmpty()) return body
        val header = buildString {
            if (meta.trustedEndpoint.isNotBlank() && meta.trustedKey.isNotBlank()) {
                append("# GoDeb-Trusted-Endpoint: ")
                append(meta.trustedEndpoint.trim())
                append('\n')
                append("# GoDeb-Trusted-Key: ")
                append(meta.trustedKey.trim())
                append('\n')
            }
            if (meta.allowedIpsBase.isNotBlank()) {
                append("# GoDeb-AllowedIPs-Base: ")
                append(meta.allowedIpsBase.trim())
                append('\n')
            }
            if (meta.excludedIps.isNotBlank()) {
                append("# GoDeb-ExcludedIPs: ")
                append(meta.excludedIps.trim())
                append('\n')
            }
        }
        return header + body
    }

    private fun commentValue(line: String, key: String): String? {
        val body = line.trimStart('#', ';', ' ')
        val sep = body.indexOfFirst { it == '=' || it == ':' }
        if (sep < 0) return null
        if (!body.substring(0, sep).trim().equals(key, ignoreCase = true)) return null
        return body.substring(sep + 1).trim().ifEmpty { null }
    }
}

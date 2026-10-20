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
    )

    private val byTunnel = ConcurrentHashMap<String, Meta>()

    fun put(tunnelName: String, allowedIpsBase: String, excludedIps: String) {
        val base = allowedIpsBase.trim()
        val excluded = excludedIps.trim()
        if (base.isEmpty() && excluded.isEmpty()) {
            byTunnel.remove(tunnelName)
        } else {
            byTunnel[tunnelName] = Meta(base, excluded)
        }
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
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#")) continue
            commentValue(line, "GoDeb-AllowedIPs-Base")?.let { base = it }
            commentValue(line, "GoDeb-ExcludedIPs")?.let { excluded = it }
        }
        return Meta(base, excluded)
    }

    fun prependComments(body: String, meta: Meta): String {
        if (meta.allowedIpsBase.isBlank() && meta.excludedIps.isBlank()) return body
        val header = buildString {
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

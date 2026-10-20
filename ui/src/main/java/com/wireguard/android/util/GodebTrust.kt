/*
 * Copyright © 2025 GoDeb. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import com.wireguard.android.service.TrustedNetworkMonitorService

/**
 * Takes over the trusted endpoint a GoDeb hub wrote into an imported
 * configuration (# GoDeb-Trusted-Endpoint / # GoDeb-Trusted-Key) and turns
 * auto-disconnect on for that tunnel.
 */
object GodebTrust {
    suspend fun adoptFromConfig(context: Context, tunnelName: String) {
        val meta = GodebConfigMeta.get(tunnelName)
        if (meta.trustedEndpoint.isBlank() || TrustedEndpointChecker.parsePublicKey(meta.trustedKey) == null) return
        UserKnobs.setTunnelTrustedEndpoint(tunnelName, meta.trustedEndpoint)
        UserKnobs.setTunnelTrustedKey(tunnelName, meta.trustedKey)
        UserKnobs.setTunnelAutoDisconnectEnabled(tunnelName, true)
        TrustedNetworkMonitorService.syncRunning(context.applicationContext)
        TrustedNetworkMonitorService.reevaluate(context.applicationContext)
    }
}

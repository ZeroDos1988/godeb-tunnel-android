/*
 * Copyright © 2017-2025 WireGuard LLC / GoDeb. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.net.Network
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Checks the GoDeb trusted endpoint (protocol godeb-trust-v1). The request is
 * bound to the given Wi-Fi [Network], so it bypasses an active tunnel. The
 * network counts as trusted only if the endpoint signs our random nonce with
 * the key pinned in the tunnel configuration; reachability alone proves
 * nothing, any network could answer on the same address.
 */
object TrustedEndpointChecker {
    private const val TAG = "WireGuard/TrustedEndpoint"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000
    private const val MAX_BODY = 4096
    private const val MESSAGE_PREFIX = "godeb-trust-v1\n"
    private val random = SecureRandom()

    suspend fun isTrusted(
        network: Network,
        endpointUrl: String,
        publicKey: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank() || publicKey.isBlank()) return@withContext false
        val key = parsePublicKey(publicKey) ?: run {
            Log.w(TAG, "Pinned trusted key is not a valid EC public key")
            return@withContext false
        }
        val base = endpointUrl.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) return@withContext false
        val nonce = newNonce()
        val url = try {
            URL(base + (if (base.contains('?')) "&" else "?") + "nonce=" + nonce)
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Endpoint returned HTTP ${connection.responseCode}")
                return@withContext false
            }
            val body = readLimited(connection)
            val json = JSONObject(body)
            if (json.optInt("v") != 1 || json.optString("nonce") != nonce) {
                Log.d(TAG, "Endpoint answered with a foreign nonce or version")
                return@withContext false
            }
            val trusted = verify(key, nonce, json.optString("sig"))
            if (!trusted) Log.w(TAG, "Endpoint signature does not match the pinned key")
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

    fun parsePublicKey(b64: String): PublicKey? = try {
        val der = Base64.decode(b64.trim(), Base64.DEFAULT)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    } catch (e: Exception) {
        null
    }

    /** Same short form as the GoDeb web UI shows: first 8 bytes of SHA-256 over the base64 key. */
    fun fingerprint(b64: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(b64.trim().toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun verify(key: PublicKey, nonce: String, sigB64: String): Boolean {
        if (sigB64.isBlank()) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update((MESSAGE_PREFIX + nonce).toByteArray(StandardCharsets.UTF_8))
                verify(Base64.decode(sigB64, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun readLimited(connection: HttpURLConnection): String {
        val out = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buf = ByteArray(1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BODY) throw IOException("response too large")
                out.write(buf, 0, n)
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}

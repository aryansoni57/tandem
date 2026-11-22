// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.identity

import android.content.Context
import app.tandem.core.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Loopback TLS 1.2 handshake with our key on the server side. It proves that the platform's
 * TLS stack can sign with the Keystore key (plan section 2.3, risk 3 in section 7).
 */
object IdentitySelfTest {
    private const val TIMEOUT_MS = 5_000
    private val trustAll =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    fun run(identity: LocalIdentity): Boolean =
        try {
            val serverContext =
                SSLContext.getInstance("TLSv1.2").apply {
                    init(arrayOf(identity.material.keyManager), null, SecureRandom())
                }
            val clientContext =
                SSLContext.getInstance("TLSv1.2").apply {
                    init(null, arrayOf(trustAll), SecureRandom())
                }
            val loopback = InetAddress.getLoopbackAddress()
            val serverOk = AtomicBoolean(false)
            serverContext.serverSocketFactory.createServerSocket(0, 1, loopback).use { raw ->
                val server = raw as SSLServerSocket
                server.enabledProtocols = arrayOf("TLSv1.2")
                server.soTimeout = TIMEOUT_MS
                val serverThread =
                    thread {
                        try {
                            (server.accept() as SSLSocket).use { s ->
                                s.soTimeout = TIMEOUT_MS
                                s.startHandshake()
                                serverOk.set(true)
                            }
                        } catch (_: IOException) {
                            serverOk.set(false)
                        }
                    }
                clientContext.socketFactory.createSocket(loopback, server.localPort).use { c ->
                    val client = c as SSLSocket
                    client.enabledProtocols = arrayOf("TLSv1.2")
                    client.soTimeout = TIMEOUT_MS
                    client.startHandshake()
                }
                serverThread.join(TIMEOUT_MS.toLong())
            }
            serverOk.get()
        } catch (_: Exception) {
            false
        }
}

/**
 * Picks the identity backend once and caches the loaded identity for the process lifetime.
 * Keystore is tried first, then the wrapped software key if the self-test fails.
 */
@Singleton
class IdentityLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsStore,
    ) {
        private val mutex = Mutex()

        @Volatile
        private var cached: LocalIdentity? = null

        suspend fun load(): LocalIdentity =
            cached ?: mutex.withLock {
                cached ?: create().also { cached = it }
            }

        private suspend fun create(): LocalIdentity {
            val deviceId = settings.ensureDeviceId()
            val saved = settings.identityBackend()
            if (saved != IdentityBackend.WRAPPED_SOFTWARE) {
                val keystore = runCatching { KeystoreIdentity.loadOrCreate(deviceId) }.getOrNull()
                if (keystore != null && IdentitySelfTest.run(keystore)) {
                    settings.setIdentityBackend(IdentityBackend.KEYSTORE)
                    return keystore
                }
            }
            settings.setIdentityBackend(IdentityBackend.WRAPPED_SOFTWARE)
            return WrappedSoftwareIdentity.loadOrCreate(context, deviceId)
        }
    }

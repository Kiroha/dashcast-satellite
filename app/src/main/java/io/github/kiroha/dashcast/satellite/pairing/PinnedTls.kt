package io.github.kiroha.dashcast.satellite.pairing

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The imported installation certificate, rather than a public CA or IP SAN, is the identity. */
@SuppressLint("CustomX509TrustManager") // Protocol pins exact DER; mismatch/absence are rejected and TLS tested.
internal class CertificatePinTrustManager(fingerprint: String) : X509TrustManager {
    private val pin = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty() || !MessageDigest.isEqual(pin,
                MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))) {
            throw CertificateException("Receiver identity mismatch")
        }
        chain[0].checkValidity()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates unsupported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal object PinnedTls {
    private fun context(fingerprint: String): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(CertificatePinTrustManager(fingerprint)), null)
    }

    /** Java-WebSocket performs the TLS handshake before its HTTP upgrade and onOpen callback. */
    fun prepareSocket(raw: Socket, host: String, port: Int, fingerprint: String): SSLSocket {
        val tls = context(fingerprint).socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            tls.soTimeout = 5_000
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = null }
            return tls
        } catch (error: Exception) {
            try { tls.close() } catch (_: Exception) {}
            throw error
        }
    }
}

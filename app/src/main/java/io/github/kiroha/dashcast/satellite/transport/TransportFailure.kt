package io.github.kiroha.dashcast.satellite.transport

import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Only fixed local codes leave the transport; exception messages can contain private data. */
internal object TransportFailure {
    fun detail(error: Throwable): String {
        var current: Throwable? = error
        var tls = false
        var timeout = false
        repeat(12) {
            val cause = current ?: return@repeat
            if (cause is CertificateException || cause is SSLPeerUnverifiedException) return "certificate_rejected"
            tls = tls || cause is SSLException
            timeout = timeout || cause is SocketTimeoutException
            current = cause.cause.takeUnless { it === cause }
        }
        return when {
            timeout -> "connection_timeout"
            tls -> "tls_failed"
            else -> "connection_failed"
        }
    }

    fun state(detail: String): TransportState =
        if (detail == "pairing_rejected" || detail == "certificate_rejected") TransportState.PAIRING_REJECTED
        else TransportState.RECONNECTING
}

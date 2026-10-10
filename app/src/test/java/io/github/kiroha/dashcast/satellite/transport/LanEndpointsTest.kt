package io.github.kiroha.dashcast.satellite.transport

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class LanEndpointsTest {
    @Test fun `hotspot gateway and specific LAN routes precede cellular addresses`() {
        val gateway = ip("192.168.49.1")
        val routes = listOf(prefix("0.0.0.0", 0), prefix("192.168.49.0", 24))
        assertEquals(0, LanEndpointRank.rank(gateway, listOf(gateway), routes))
        assertEquals(2, LanEndpointRank.rank(ip("192.168.49.33"), listOf(gateway), routes))
        assertEquals(4, LanEndpointRank.rank(ip("10.233.44.55"), listOf(gateway), routes))
        assertEquals(4, LanEndpointRank.rank(ip("192.168.43.1"), listOf(gateway), routes))
    }

    @Test fun `IPv6 prefixes keep family and partial-byte boundaries without accepting a default route`() {
        val routes = listOf(prefix("::", 0), prefix("fd00:1234:5678:9000::", 60))
        assertEquals(3, LanEndpointRank.rank(ip("fd00:1234:5678:900f::2"), emptyList(), routes))
        assertEquals(5, LanEndpointRank.rank(ip("fd00:1234:5678:9010::2"), emptyList(), routes))
        assertEquals(4, LanEndpointRank.rank(ip("192.168.49.1"), emptyList(), routes))
    }

    @Test fun `all ranked endpoints are attempted before a retry cycle incurs another backoff`() {
        val cycle = LanEndpoints<String>()
        val endpoints = listOf(endpoint("wifi", "10.1.1.1", 4), endpoint("wifi", "192.168.49.1", 0),
            endpoint("ethernet", "192.168.1.2", 2))
        assertEquals("192.168.49.1", cycle.next(endpoints)!!.host)
        assertTrue(cycle.hasMoreInCycle)
        assertEquals("192.168.1.2", cycle.next(endpoints)!!.host)
        assertTrue(cycle.hasMoreInCycle)
        assertEquals("10.1.1.1", cycle.next(endpoints)!!.host)
        assertFalse(cycle.hasMoreInCycle)
        assertEquals("192.168.49.1", cycle.next(endpoints)!!.host)
    }

    @Test fun `reconnect first retries the authenticated endpoint and network loss removes it`() {
        val cycle = LanEndpoints<String>()
        val candidates = listOf(endpoint("wifi", "192.168.49.1", 0), endpoint("wifi", "192.168.49.9", 2))
        cycle.next(candidates)
        assertEquals("192.168.49.9", cycle.next(candidates)!!.host)
        cycle.connected()
        assertEquals("192.168.49.9", cycle.next(candidates)!!.host)
        assertEquals("192.168.49.1", cycle.next(candidates)!!.host)
        assertEquals("192.168.1.3", cycle.next(listOf(endpoint("ethernet", "192.168.1.3", 2)))!!.host)
        cycle.reset()
        assertEquals("192.168.49.1", cycle.next(candidates)!!.host)
    }

    @Test fun `candidate cycles deduplicate peers and are bounded`() {
        val cycle = LanEndpoints<String>()
        val candidates = List(100) { endpoint("wifi", "10.1.1.${it + 1}", 4) }
        val seen = mutableSetOf<String>()
        repeat(LanEndpoints.MAX_ENDPOINTS) { seen.add(cycle.next(candidates + candidates)!!.host) }
        assertEquals(64, seen.size)
        assertFalse(cycle.hasMoreInCycle)
        assertEquals("10.1.1.1", cycle.next(candidates)!!.host)
    }

    @Test fun `transient TLS errors are not reported as a rejected pairing`() {
        val tls = TransportFailure.detail(SSLException("Private exception contents"))
        assertEquals("tls_failed", tls)
        assertEquals(TransportState.RECONNECTING, TransportFailure.state(tls))
        assertEquals("connection_failed", TransportFailure.detail(SocketException("Private endpoint")))
        val timeout = SSLException("TLS read").apply { initCause(SocketTimeoutException("Private endpoint")) }
        assertEquals("connection_timeout", TransportFailure.detail(timeout))
    }

    @Test fun `certificate rejection stays a pairing error without leaking its exception text`() {
        val error = SSLHandshakeException("Private host").apply {
            initCause(CertificateException("Private certificate data"))
        }
        val detail = TransportFailure.detail(error)
        assertEquals("certificate_rejected", detail)
        assertEquals(TransportState.PAIRING_REJECTED, TransportFailure.state(detail))
        assertEquals(TransportState.PAIRING_REJECTED, TransportFailure.state("pairing_rejected"))
    }

    private fun endpoint(network: String, host: String, rank: Int) = LanEndpoints.Endpoint(network, host, rank)
    private fun ip(value: String) = InetAddress.getByName(value)
    private fun prefix(value: String, bits: Int) = LanEndpointRank.Prefix(ip(value), bits)
}

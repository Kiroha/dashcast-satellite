package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

class VideoPolicyTest {
    private val ipv4 = "candidate:1 1 udp 2122260223 192.168.49.1 12345 typ host generation 0"
    private val mdns = ipv4.replace("192.168.49.1", "a79bbea8-d881-4b09-8092-7b3992e1c96f.local")
    @Test fun `native media allows only selected LAN local UDP host candidates`() {
        assertTrue(VideoPolicy.localCandidate(ipv4, setOf("192.168.49.1")))
        assertFalse(VideoPolicy.localCandidate(ipv4, setOf("192.168.51.1")))
        for (bad in listOf(ipv4.replace("udp", "tcp"), ipv4.replace(" 1 udp", " 2 udp"),
            ipv4.replace("typ host", "typ relay"), ipv4.replace("typ host", "typ srflx"),
            ipv4.replace("192.168.49.1", "8.8.8.8"), ipv4.replace("192.168.49.1", "fe80::1"),
            ipv4.replace("12345", "0"), ipv4.replace("12345", "65536"), ipv4 + " raddr 8.8.8.8 rport 5")) {
            assertNull(bad, VideoPolicy.host(bad))
        }
    }
    @Test fun `canonical browser mDNS maps only to current authenticated TLS peer without DNS`() {
        assertEquals(ipv4, VideoPolicy.receiverCandidate(mdns, "192.168.49.1"))
        assertEquals(ipv4.replace("192.168.49.1", "192.168.51.1"), VideoPolicy.receiverCandidate(mdns, "192.168.51.1"))
        assertNull(VideoPolicy.receiverCandidate(mdns, "8.8.8.8"))
        assertNull(VideoPolicy.receiverCandidate(mdns.replace("a79bbea8-d881-4b09-8092-7b3992e1c96f", "arbitrary-name"), "192.168.49.1"))
        assertNull(VideoPolicy.receiverCandidate(mdns.replace("typ host", "typ relay"), "192.168.49.1"))
        assertNull(VideoPolicy.receiverCandidate(ipv4.replace("192.168.49.1", "192.168.51.1"), "192.168.49.1"))
    }
    @Test fun `SDP preserves trust parameters but removes every unrelated address`() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 8.8.8.8\r\na=ice-ufrag:keep\r\na=ice-pwd:keep\r\na=fingerprint:sha-256 keep\r\na=$mdns\r\na=${ipv4.replace("192.168.49.1", "8.8.8.8")}\r\n"
        val filtered = VideoPolicy.sdp(sdp, receiverHost = "192.168.49.1")
        assertTrue(filtered.contains("a=$ipv4"))
        assertFalse(filtered.contains("8.8.8.8")); assertFalse(filtered.contains(".local"))
        assertTrue(filtered.contains("a=ice-pwd:keep")); assertTrue(filtered.contains("a=fingerprint:sha-256 keep"))
        assertTrue(filtered.contains("c=IN IP4 0.0.0.0"))
        assertThrows(IllegalArgumentException::class.java) { VideoPolicy.sdp(sdp + "m=audio 9 UDP/TLS/RTP/SAVPF 0\r\n") }
    }
    @Test fun `single consent dimensions preserve portrait landscape and small source aspect`() {
        assertEquals(960 to 540, ProjectionTextureCapturer.dimensions(1920, 1080))
        assertEquals(304 to 540, ProjectionTextureCapturer.dimensions(1080, 1920))
        assertEquals(480 to 480, ProjectionTextureCapturer.dimensions(480, 480))
        assertEquals(960 to 360, ProjectionTextureCapturer.dimensions(2560, 960))
    }
    @Test fun `outbound ICE waits for actual offer send and inbound waits for answer`() {
        val owner = VideoNegotiation("first")
        val sent = mutableListOf<String>(); val applied = mutableListOf<String>()
        assertTrue(owner.localIce("one", sent::add)); assertTrue(owner.remoteIce("remote", applied::add))
        assertTrue(sent.isEmpty()); assertTrue(applied.isEmpty())
        owner.offerSent(sent::add); assertEquals(listOf("one"), sent)
        owner.answerApplied(applied::add); assertEquals(listOf("remote"), applied)
        owner.close()
        assertFalse(owner.localIce("late", sent::add)); assertFalse(owner.remoteIce("late", applied::add))
        owner.offerSent(sent::add); owner.answerApplied(applied::add)
        assertEquals(listOf("one"), sent); assertEquals(listOf("remote"), applied)
    }
    @Test fun `both ICE queues are bounded including after SDP completes`() {
        for (outbound in listOf(true, false)) {
            val owner = VideoNegotiation("bounded")
            repeat(VideoPolicy.MAX_ICE) {
                assertTrue(if (outbound) owner.localIce("$it") {} else owner.remoteIce("$it") {})
            }
            assertFalse(if (outbound) owner.localIce("overflow") {} else owner.remoteIce("overflow") {})
        }
    }
}

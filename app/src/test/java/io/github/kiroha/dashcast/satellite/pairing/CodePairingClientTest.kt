package io.github.kiroha.dashcast.satellite.pairing

import android.app.Application
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.ProxyInfo
import android.net.RouteInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CodePairingClientTest {
    @Test fun `authenticated PAKE transfers profile without exposing PIN or token on the wire`() {
        val peer = PeerSocket()
        CodePairingClient { listOf(candidate { peer }) }.use { client ->
            val profile = client.pair("123-456", null)
            assertEquals("192.168.49.1", profile.hosts.single())
            assertEquals(TOKEN, profile.token)
        }
        assertEquals(3, peer.clientFrames.size)
        assertEquals(4, peer.serverFrames.size)
        for (frame in peer.clientFrames + peer.serverFrames) {
            assertFalse(frame.contains("\"$CODE\""))
            assertFalse(frame.contains(TOKEN))
        }
        assertTrue(peer.wasClosed)
    }

    @Test fun `wrong PIN cannot receive profile and next candidate begins a fresh exchange`() {
        val wrong = PeerSocket(serverCode = "654321")
        val valid = PeerSocket()
        CodePairingClient { listOf(candidate { wrong }, candidate { valid }) }.use {
            assertEquals(TOKEN, it.pair(CODE, null).token)
        }
        assertEquals(3, wrong.serverFrames.size)
        assertEquals(2, wrong.clientFrames.size)
        assertNotEquals(wrong.clientFrames.first(), valid.clientFrames.first())
        assertTrue(wrong.wasClosed)
        assertTrue(valid.wasClosed)
    }

    @Test fun `replaying an earlier successful server transcript cannot pair a fresh client`() {
        val original = PeerSocket()
        CodePairingClient { listOf(candidate { original }) }.use { it.pair(CODE, null) }
        val replay = RecordingSocket(original.serverFrames.fold(ByteArray(0)) { bytes, message ->
            bytes + frame(message.toByteArray(Charsets.UTF_8))
        })
        CodePairingClient { listOf(candidate { replay }) }.use { client ->
            assertEquals(CodePairingClient.Failure.UNAVAILABLE,
                assertThrows(CodePairingClient.PairingException::class.java) { client.pair(CODE, null) }.failure)
        }
        assertTrue(replay.wasClosed)
    }

    @Test fun `zero negative oversized truncated malformed and invalid UTF8 frames are refused`() {
        val invalid = listOf(
            ByteBuffer.allocate(4).putInt(0).array(),
            ByteBuffer.allocate(4).putInt(-1).array(),
            ByteBuffer.allocate(4).putInt(SatellitePairingCode.MAX_FRAME_BYTES + 1).array(),
            byteArrayOf(0, 0, 0),
            ByteBuffer.allocate(4).putInt(10).array() + byteArrayOf(1, 2),
            frame(byteArrayOf(0xff.toByte())),
            frame("{}".toByteArray()),
        )
        for (response in invalid) {
            val wire = RecordingSocket(response)
            CodePairingClient { listOf(candidate { wire }) }.use { client ->
                assertEquals(CodePairingClient.Failure.UNAVAILABLE,
                    assertThrows(CodePairingClient.PairingException::class.java) { client.pair(CODE, null) }.failure)
            }
            assertTrue(wire.wasClosed)
        }
    }

    @Test fun `even an authenticated peer cannot provide an invalid pairing profile`() {
        val peer = PeerSocket(profile = "{}")
        CodePairingClient { listOf(candidate { peer }) }.use { client ->
            assertEquals(CodePairingClient.Failure.UNAVAILABLE,
                assertThrows(CodePairingClient.PairingException::class.java) { client.pair(CODE, null) }.failure)
        }
    }

    @Test fun `manual DNS public IP and missing LAN never create sockets`() {
        var queried = false
        CodePairingClient { queried = true; emptyList() }.use { client ->
            for (host in listOf("car.local", "8.8.8.8", "192.168.1.1:47833")) {
                assertThrows(IllegalArgumentException::class.java) { client.pair(CODE, host) }
            }
            assertFalse(queried)
            assertEquals(CodePairingClient.Failure.NO_LAN,
                assertThrows(CodePairingClient.PairingException::class.java) { client.pair(CODE, null) }.failure)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun `car gateway uses unvalidated Wi-Fi while ignoring cellular VPN and proxy`() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val wifi = ShadowNetwork.newInstance(101)
        val cellular = ShadowNetwork.newInstance(102)
        val vpn = ShadowNetwork.newInstance(103)
        val peer = PeerSocket()
        for ((network, transport) in listOf(wifi to NetworkCapabilities.TRANSPORT_WIFI,
            cellular to NetworkCapabilities.TRANSPORT_CELLULAR, vpn to NetworkCapabilities.TRANSPORT_VPN)) {
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true)
            shadow.addNetwork(network, info)
            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).clearCapabilities()
            shadowOf(capabilities).addTransportType(transport)
            shadow.setNetworkCapabilities(network, capabilities)
            shadow.setLinkProperties(network, LinkProperties().apply {
                interfaceName = "wlan0"
                // IPv6 appears first, but hotspot pairing should prefer its IPv4 gateway.
                addRoute(route("::", "fd00::1", 0))
                addRoute(route("0.0.0.0", "192.168.49.1", 0))
            })
            shadowOf(network).setSocketFactory(candidate {
                assertEquals(wifi, network)
                peer
            }.factory)
        }
        shadow.setProxyForNetwork(wifi, ProxyInfo.buildDirectProxy("127.0.0.1", 8888))
        CodePairingClient(app).use { assertEquals(TOKEN, it.pair(CODE, null).token) }
        assertEquals("192.168.49.1", (peer.connectedAddress as InetSocketAddress).address.hostAddress)
        assertEquals(47833, (peer.connectedAddress as InetSocketAddress).port)
        assertNull(manager.boundNetworkForProcess)
    }

    @Test fun `only two candidate addresses can be attempted`() {
        var attempts = 0
        CodePairingClient { List(20) { candidate {
            attempts++
            throw IOException("Unavailable")
        } } }.use {
            assertThrows(CodePairingClient.PairingException::class.java) { it.pair(CODE, null) }
        }
        assertEquals(2, attempts)
    }

    @Test fun `cancel during socket creation closes the late socket without connecting`() {
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wire = RecordingSocket(ByteArray(0))
        val client = CodePairingClient { listOf(candidate {
            created.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            wire
        }) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<CodePairingClient.Failure> {
                try { client.pair(CODE, null); error("Unexpected pairing") }
                catch (error: CodePairingClient.PairingException) { error.failure }
            }
            assertTrue(created.await(5, TimeUnit.SECONDS))
            client.close()
            release.countDown()
            assertEquals(CodePairingClient.Failure.CANCELLED, result.get(5, TimeUnit.SECONDS))
            assertTrue(wire.wasClosed)
            assertFalse(wire.wasConnected)
        } finally { release.countDown(); client.close(); executor.shutdownNow() }
    }

    @Test fun `cancel closes an in-flight blocked read`() {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val wire = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(closed.await(5, TimeUnit.SECONDS))
                    throw IOException("Closed")
                }
            }
            override fun close() { closed.countDown() }
        }
        val client = CodePairingClient { listOf(candidate { wire }) }
        val failure = AtomicReference<CodePairingClient.Failure>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit {
                try { client.pair(CODE, null); fail("Unexpected pairing") }
                catch (error: CodePairingClient.PairingException) { failure.set(error.failure) }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            client.close()
            task.get(5, TimeUnit.SECONDS)
            assertEquals(CodePairingClient.Failure.CANCELLED, failure.get())
        } finally { client.close(); executor.shutdownNow() }
    }

    /** Real PAKE server with duplex streams, behind the same socket seam as Android's LAN network. */
    private class PeerSocket(private val profile: String = PROFILE, private val serverCode: String = CODE) : Socket() {
        val clientFrames = CopyOnWriteArrayList<String>()
        val serverFrames = CopyOnWriteArrayList<String>()
        @Volatile var wasClosed = false
        var connectedAddress: SocketAddress? = null
        private val clientInput = PipedInputStream(32_768)
        private val serverOutput = PipedOutputStream(clientInput)
        private val serverInput = PipedInputStream(32_768)
        private val clientOutput = PipedOutputStream(serverInput)
        private val peer = Thread({
            try {
                val input = DataInputStream(serverInput)
                val output = DataOutputStream(serverOutput)
                SatellitePairingExchange.server(profile, serverCode, send = { message ->
                    serverFrames.add(message)
                    val bytes = message.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }, receive = {
                    val length = input.readInt()
                    require(length in 1..SatellitePairingCode.MAX_FRAME_BYTES)
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    String(bytes, Charsets.UTF_8).also(clientFrames::add)
                })
            } catch (_: Exception) {
                // Wrong-PIN/cancellation cases deliberately cause the peer to stop before a profile.
            } finally { runCatching { serverOutput.close() } }
        }, "pairing-test-peer").apply { isDaemon = true }

        override fun connect(endpoint: SocketAddress?, timeout: Int) { connectedAddress = endpoint; peer.start() }
        override fun getOutputStream() = clientOutput
        override fun getInputStream() = clientInput
        override fun close() {
            wasClosed = true
            runCatching { clientOutput.close() }
            runCatching { serverOutput.close() }
            runCatching { clientInput.close() }
            runCatching { serverInput.close() }
            peer.join(2_000)
        }
    }

    private class RecordingSocket(private val response: ByteArray) : Socket() {
        val sent = ByteArrayOutputStream()
        var wasClosed = false
        var wasConnected = false
        override fun connect(endpoint: SocketAddress?, timeout: Int) { wasConnected = true }
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun close() { wasClosed = true }
    }

    private fun candidate(create: () -> Socket) = CodePairingClient.Candidate(object : SocketFactory() {
        override fun createSocket() = create()
        override fun createSocket(host: String?, port: Int): Socket = error("Unexpected DNS")
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = error("Unexpected DNS")
        override fun createSocket(host: InetAddress?, port: Int): Socket = error("Unexpected unbound socket")
        override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = error("Unexpected unbound socket")
    }, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 47833))

    private fun frame(bytes: ByteArray): ByteArray = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()

    private fun route(prefix: String, gateway: String, prefixLength: Int): RouteInfo =
        ReflectionHelpers.callConstructor(RouteInfo::class.java,
            ClassParameter.from(IpPrefix::class.java, IpPrefix(InetAddress.getByName(prefix), prefixLength)),
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName(gateway)),
            ClassParameter.from(String::class.java, "wlan0"))

    private companion object {
        const val CODE = "123456"
        val TOKEN: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
        val PROFILE: String get() = JSONObject().put("version", 1).put("hosts", JSONArray(listOf("192.168.49.1")))
            .put("port", 47832).put("path", "/satellite/v1").put("certificateSha256", "a".repeat(64))
            .put("token", TOKEN).toString()
    }
}

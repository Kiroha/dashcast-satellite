package io.github.kiroha.dashcast.satellite.transport

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkAddress
import io.github.kiroha.dashcast.satellite.capture.VideoControlBridge
import io.github.kiroha.dashcast.satellite.capture.VideoSignallingQueue
import io.github.kiroha.dashcast.satellite.capture.VideoLink
import io.github.kiroha.dashcast.satellite.capture.VideoDiagnosticsTransfer
import io.github.kiroha.dashcast.satellite.capture.VideoDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import io.github.kiroha.dashcast.satellite.navigation.Observation
import io.github.kiroha.dashcast.satellite.navigation.Guidance
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import io.github.kiroha.dashcast.satellite.pairing.PairingProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SatelliteTransportTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var providers = emptyList<Pair<Int, Provider>>()

    @Before fun isolateJvmTls() {
        providers = Security.getProviders().mapIndexedNotNull { index, provider ->
            if (provider.javaClass.name.contains("conscrypt", true)) index to provider else null
        }
        providers.forEach { Security.removeProvider(it.second.name) }
        // Android disables this third-party SSLEngine assertion. The native TLS implementation
        // is unchanged; test the same library behavior instead of Java -ea's startup assertion.
        WebSocketServer::class.java.classLoader!!.setClassAssertionStatus("org.java_websocket.SSLSocketChannel2", false)
    }

    @After fun restoreJvmTls() {
        providers.forEach { Security.insertProviderAt(it.second, it.first + 1) }
    }

    @Test fun `production LAN pinned TLS and ordinary WebSocket upgrade reach welcome without a subprotocol`() {
        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<TransportStatus>()
        val attempted = CopyOnWriteArrayList<String>()
        configureLan {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    attempted.add((endpoint as InetSocketAddress).address.hostAddress!!)
                    super.connect(endpoint, timeout)
                }
            }
        }
        val server = TestReceiver(sendWelcome = true)
        val transport = SatelliteTransport(app, { status ->
            statuses.add(status)
            if (status.state == TransportState.CONNECTED) connected.countDown()
            if (status.state == TransportState.STOPPED) stopped.countDown()
        }, { 100L })
        try {
            server.startAndAwait()
            transport.start(profile(server.port, listOf("10.233.44.55", "127.0.0.1")))
            val welcomed = connected.await(5, TimeUnit.SECONDS)
            assertTrue("No welcome through production transport: $statuses; TLS peer error: ${server.errorType.get()}; " +
                "server upgrade: ${server.requestedSubprotocol.get()}", welcomed)
            assertEquals(listOf("127.0.0.1"), attempted.toList())
            assertEquals("", server.requestedSubprotocol.get())
            val hello = JSONObject(server.hello.get()!!)
            assertEquals("hello", hello.getString("type"))
            assertEquals(TOKEN, hello.getString("token"))
            assertTrue(hello.getJSONObject("device").getString("id").matches(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
            assertEquals("127.0.0.1", statuses.last().endpointHost)
            assertNull(statuses.last().detail)
            assertNull(app.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
        } finally {
            transport.stop()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            server.stopQuietly()
        }
    }

    @Test fun `legacy mixed profile only attempts IPv4 on the selected LAN`() {
        val attempted = CopyOnWriteArrayList<String>()
        val triedIpv6 = CountDownLatch(1)
        val failedIpv4 = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val now = AtomicLong(100)
        configureLan {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    val host = (endpoint as InetSocketAddress).address.hostAddress!!
                    attempted.add(host)
                    if (endpoint.address.address.size == 16) triedIpv6.countDown()
                    throw SocketException("Test-only unreachable endpoint")
                }
            }
        }
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.STOPPED) stopped.countDown()
            if (it.state == TransportState.RECONNECTING) failedIpv4.countDown()
        }, now::get)
        try {
            transport.start(profile(47832, listOf("fd00::1", "127.0.0.1")))
            assertTrue(failedIpv4.await(3, TimeUnit.SECONDS))
            now.set(350)
            triedIpv6.await(500, TimeUnit.MILLISECONDS)
            assertEquals(listOf("127.0.0.1"), attempted.toList())
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)) }
    }

    @Test fun `authenticated welcome capabilities are strict and reset on every new connection`() {
        configureLan { Socket() }
        val connected = LinkedBlockingQueue<TransportStatus>()
        val stopped = CountDownLatch(1)
        val server = TestReceiver(sendWelcome = true)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.CONNECTED) connected.offer(it)
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, { 135L })
        try {
            server.startAndAwait()
            val pairing = profile(server.port, listOf("127.0.0.1"))
            // The first capable session must not leak its capability into the next connection.
            for (capability in listOf(true, null, "true", 1, JSONObject.NULL, false, true)) {
                server.genericCapability.set(capability)
                transport.start(pairing)
                assertNotNull("No authenticated connection", connected.poll(5, TimeUnit.SECONDS))
                transport.offer(Observation.Valid(Guidance("roundabout_generic_ccw", 40), 100))
                val frame = server.navigation.poll(3, TimeUnit.SECONDS)!!
                assertEquals(if (capability == true) "navigation.update" else "navigation.stop", frame.getString("type"))
                assertEquals(0L, frame.getLong("seq"))
                assertEquals(35L, frame.getLong("ageMs"))
                assertFalse(frame.has("exit"))
                transport.offer(Observation.Valid(Guidance("left", 130), 100))
                val following = server.navigation.poll(3, TimeUnit.SECONDS)!!
                assertEquals("left", following.getString("maneuver"))
                assertEquals(1L, following.getLong("seq"))
            }
        } finally {
            transport.stop()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            server.stopQuietly()
        }
    }

    @Test fun `current IPv4 hotspot gateway reconnects under the saved pin after an address change`() {
        configureLan { Socket() }
        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val server = TestReceiver(sendWelcome = true)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.CONNECTED) connected.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, { 100L })
        try {
            server.startAndAwait()
            transport.start(profile(server.port, listOf("192.168.51.1")))
            assertTrue("Saved profile omitted the current LAN gateway", connected.await(3, TimeUnit.SECONDS))
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly() }
    }

    @Test fun `a new hotspot gateway cannot authenticate with a different certificate`() {
        configureLan { Socket() }
        val rejected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<TransportStatus>()
        val server = TestReceiver(sendWelcome = true)
        val transport = SatelliteTransport(app, {
            statuses.add(it)
            if (it.detail == "certificate_rejected") rejected.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, { 100L })
        try {
            server.startAndAwait()
            val original = profile(server.port, listOf("192.168.51.1"))
            val wrongPin = PairingProfile.parse(JSONObject(original.encoded())
                .put("certificateSha256", "ab".repeat(32)).toString())
            transport.start(wrongPin)
            assertTrue(rejected.await(3, TimeUnit.SECONDS))
            assertFalse(statuses.any { it.state == TransportState.CONNECTED })
            assertNull(server.hello.get())
            assertEquals("127.0.0.1", statuses.last().endpointHost)
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly() }
    }

    @Test fun `legacy IPv6-only profile without an IPv4 gateway reports a fixed actionable state`() {
        configureLan { error("No IPv4 candidate must not create a socket") }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager.allNetworks.single()
        shadowOf(manager).setLinkProperties(network, LinkProperties())
        val unavailable = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.WAITING_FOR_LAN && it.detail == "ipv4_unavailable") unavailable.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, { 100L })
        try {
            transport.start(profile(47832, listOf("fe80::1")))
            assertTrue(unavailable.await(3, TimeUnit.SECONDS))
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)) }
    }

    @Test fun `hotspot capabilities arriving after onAvailable recover a missing initial LAN`() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val waiting = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val server = TestReceiver(sendWelcome = true)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.WAITING_FOR_LAN) waiting.countDown()
            if (it.state == TransportState.CONNECTED) connected.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, { 100L })
        try {
            server.startAndAwait()
            transport.start(profile(server.port, listOf("192.168.51.1")))
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            val callback = shadow.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(301)
            assertNull(manager.getNetworkCapabilities(network))
            callback.onAvailable(network)
            ReflectionHelpers.getField<ExecutorService>(transport, "worker").submit {}.get(3, TimeUnit.SECONDS)
            configureLan { Socket() }
            callback.onCapabilitiesChanged(network, manager.getNetworkCapabilities(network)!!)
            callback.onLinkPropertiesChanged(network, manager.getLinkProperties(network)!!)
            assertTrue("A late Wi-Fi capability was never added to the LAN set", connected.await(3, TimeUnit.SECONDS))
            assertNull(manager.boundNetworkForProcess)
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly() }
    }

    @Test fun `connection deadline closes a stalled LAN socket then retains diagnosis for the next host`() {
        val now = AtomicLong(100)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = CountDownLatch(1)
        val secondAttempt = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<TransportStatus>()
        configureLan {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    if ((endpoint as InetSocketAddress).address.hostAddress == "127.0.0.1") {
                        entered.countDown()
                        check(closed.await(5, TimeUnit.SECONDS))
                        throw SocketException("Cancelled")
                    }
                    // Remain in CONNECTING long enough to observe its retained fixed diagnosis.
                    check(closed.await(5, TimeUnit.SECONDS))
                    throw SocketException("Unavailable")
                }
                override fun close() { closed.countDown(); super.close() }
            }
        }
        val transport = SatelliteTransport(app, { status ->
            statuses.add(status)
            if (status.detail == "connection_timeout" && status.state == TransportState.RECONNECTING) failure.countDown()
            if (status.state == TransportState.CONNECTING && status.endpointHost == "10.233.44.55") secondAttempt.countDown()
            if (status.state == TransportState.STOPPED) stopped.countDown()
        }, now::get)
        try {
            transport.start(profile(47832, listOf("10.233.44.55", "127.0.0.1")))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            now.set(10_100)
            assertTrue(failure.await(3, TimeUnit.SECONDS))
            assertEquals(0L, closed.count)
            now.set(10_350)
            assertTrue(secondAttempt.await(3, TimeUnit.SECONDS))
            assertEquals("connection_timeout", statuses.first {
                it.state == TransportState.CONNECTING && it.endpointHost == "10.233.44.55"
            }.detail)
        } finally {
            transport.stop()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `receiver that upgrades but never welcomes has a separate authentication deadline`() {
        configureLan { Socket() }
        val now = AtomicLong(100)
        val authenticating = CountDownLatch(1)
        val expired = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val server = TestReceiver(sendWelcome = false)
        val transport = SatelliteTransport(app, { status ->
            if (status.state == TransportState.AUTHENTICATING) authenticating.countDown()
            if (status.detail == "authentication_timeout") expired.countDown()
            if (status.state == TransportState.STOPPED) stopped.countDown()
        }, now::get)
        try {
            server.startAndAwait()
            transport.start(profile(server.port, listOf("127.0.0.1")))
            assertTrue(authenticating.await(5, TimeUnit.SECONDS))
            now.set(5_100)
            assertTrue(expired.await(3, TimeUnit.SECONDS))
        } finally {
            transport.stop()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            server.stopQuietly()
        }
    }


    @Test fun `manual diagnostics use only an opted in pinned control socket and commit ack without a viewer`() {
        for (capability in listOf<Any?>(null, true, "1", 2, 1)) {
            configureLan { Socket() }
            val connected = CountDownLatch(1); val stopped = CountDownLatch(1)
            val server = TestReceiver(true)
            server.diagnosticsCapability.set(capability)
            val transport = SatelliteTransport(app, {
                if (it.state == TransportState.CONNECTED) connected.countDown()
                if (it.state == TransportState.STOPPED) stopped.countDown()
            }, { 100L })
            try {
                server.startAndAwait(); transport.start(profile(server.port, listOf("127.0.0.1")))
                assertTrue(connected.await(5, TimeUnit.SECONDS))
                ReflectionHelpers.getField<ExecutorService>(transport, "worker").submit {}.get(3, TimeUnit.SECONDS)
                assertNull("Diagnostics do not require a visible viewer or usable video LAN", VideoControlBridge.link)
                assertEquals(capability == 1, VideoDiagnosticsTransfer.send(app))
                if (capability == 1) {
                    val snapshot = server.diagnostics.poll(3, TimeUnit.SECONDS)
                    assertNotNull(snapshot)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (VideoDiagnosticsTransfer.result == VideoDiagnosticsTransfer.Result.SENDING && System.nanoTime() < deadline) Thread.sleep(10)
                    assertEquals(VideoDiagnosticsTransfer.Result.RECEIVED, VideoDiagnosticsTransfer.result)
                } else {
                    Thread.sleep(150)
                    assertFalse(server.received.any { JSONObject(it).optString("type").startsWith("diagnostics.") })
                }
                transport.offer(Observation.Stop(100L))
                assertNotNull("Diagnostics must preserve guidance", server.navigation.poll(3, TimeUnit.SECONDS))
            } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly() }
            assertEquals(VideoDiagnosticsTransfer.Availability.DISCONNECTED, VideoDiagnosticsTransfer.availability)
        }
    }

    @Test fun `video cancellation before offer transmission preserves authenticated guidance`() {
        configureLan { Socket() }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val properties = manager.getLinkProperties(manager.allNetworks.single())!!
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName("127.0.0.1")),
            ClassParameter.from(Int::class.javaPrimitiveType, 8))
        ReflectionHelpers.callInstanceMethod<Boolean>(properties, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address))
        shadowOf(manager).setLinkProperties(manager.allNetworks.single(), properties)
        val connected = CountDownLatch(1); val stopped = CountDownLatch(1)
        val now = AtomicLong(100)
        val server = TestReceiver(true)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.CONNECTED) connected.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, now::get)
        try {
            server.startAndAwait(); transport.start(profile(server.port, listOf("127.0.0.1")))
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val worker = ReflectionHelpers.getField<ExecutorService>(transport, "worker")
            worker.submit {}.get(3, TimeUnit.SECONDS)
            val link = VideoControlBridge.link!!
            worker.submit {
                val queue = ReflectionHelpers.getField<VideoSignallingQueue>(transport, "videoQueue")
                queue.offer(JSONObject().put("type", "video.offer").put("negotiation", "never-offered")
                    .put("sdp", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")) {}
                queue.offer(JSONObject().put("type", "video.ice").put("negotiation", "never-offered")
                    .put("candidate", "candidate:1 1 udp 1 192.168.49.2 1234 typ host")
                    .put("sdpMid", "0").put("sdpMLineIndex", 0)) {}
                queue.cancel("never-offered") // Same serial worker turn, before the next tick.
            }.get(3, TimeUnit.SECONDS)
            Thread.sleep(200)
            assertFalse(server.received.any { JSONObject(it).optString("type").startsWith("video.") })
            transport.offer(Observation.Stop(now.get()))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (server.received.none { JSONObject(it).optString("type") == "navigation.stop" } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(server.received.any { JSONObject(it).optString("type") == "navigation.stop" })
            assertEquals("CONNECTED", ReflectionHelpers.getField<Any>(transport, "lastStatus").let {
                (it as TransportStatus).state.name })
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly() }
    }

    @Test fun `late legacy viewer rejection and old scoped error never clear newer authenticated readiness`() {
        configureLan { Socket() }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager.allNetworks.single()
        val properties = manager.getLinkProperties(network)!!
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName("127.0.0.1")),
            ClassParameter.from(Int::class.javaPrimitiveType, 8))
        ReflectionHelpers.callInstanceMethod<Boolean>(properties, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address))
        shadowOf(manager).setLinkProperties(network, properties)
        val connected = CountDownLatch(1); val stopped = CountDownLatch(1)
        val markers = LinkedBlockingQueue<Boolean>()
        val server = TestReceiver(true)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.CONNECTED) connected.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, { 100L })
        val subscription = VideoControlBridge.subscribe(object : VideoControlBridge.Listener {
            override fun linkChanged(link: VideoLink?) = Unit
            override fun signal(link: VideoLink, message: JSONObject) {
                if (message.optString("type") == "video.ice") markers.offer(link.viewerReady)
            }
        })
        try {
            server.startAndAwait(); transport.start(profile(server.port, listOf("127.0.0.1")))
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            ReflectionHelpers.getField<ExecutorService>(transport, "worker").submit {}.get(3, TimeUnit.SECONDS)
            val owner = VideoControlBridge.link!!
            val connection = server.connections.single()
            fun marker(): Boolean? {
                connection.send("""{"type":"video.ice","negotiation":"old","candidate":"candidate:test","sdpMid":"0","sdpMLineIndex":0}""")
                return markers.poll(3, TimeUnit.SECONDS)
            }
            connection.send("""{"type":"video.ready"}""")
            connection.send("""{"type":"error","code":"viewer_unavailable"}""")
            assertEquals(true, marker())
            assertTrue(owner.viewerReady)
            connection.send("""{"type":"video.closed"}""")
            connection.send("""{"type":"error","code":"viewer_unavailable"}""")
            assertEquals(false, marker())
            connection.send("""{"type":"video.ready"}""")
            connection.send("""{"type":"video.error","negotiation":"obsolete","code":"video_timeout"}""")
            assertEquals(true, marker())
            assertSame(owner, VideoControlBridge.link)
            transport.offer(Observation.Stop(100))
            assertNotNull("Video errors must preserve authenticated guidance", server.navigation.poll(3, TimeUnit.SECONDS))
            assertTrue(VideoDiagnostics.summary().contains("legacy_viewer_error="))
        } finally {
            subscription.close(); transport.stop()
            assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly()
        }
    }

    @Test fun `video offer advances alongside continuous guidance and later stop names a sent offer`() {
        configureLan { Socket() }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val properties = manager.getLinkProperties(manager.allNetworks.single())!!
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName("127.0.0.1")),
            ClassParameter.from(Int::class.javaPrimitiveType, 8))
        ReflectionHelpers.callInstanceMethod<Boolean>(properties, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address))
        shadowOf(manager).setLinkProperties(manager.allNetworks.single(), properties)
        val connected = CountDownLatch(1); val stopped = CountDownLatch(1); val sent = CountDownLatch(1)
        val now = AtomicLong(100)
        val server = TestReceiver(true)
        val transport = SatelliteTransport(app, {
            if (it.state == TransportState.CONNECTED) connected.countDown()
            if (it.state == TransportState.STOPPED) stopped.countDown()
        }, now::get)
        try {
            server.startAndAwait(); transport.start(profile(server.port, listOf("127.0.0.1")))
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val worker = ReflectionHelpers.getField<ExecutorService>(transport, "worker")
            worker.submit {}.get(3, TimeUnit.SECONDS)
            val link = VideoControlBridge.link!!
            val flooding = AtomicBoolean(true)
            val flood = Thread {
                while (flooding.get()) { transport.offer(Observation.Stop(now.get())); Thread.sleep(5) }
            }.apply { start() }
            try {
                val started = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (server.received.none { JSONObject(it).optString("type") == "navigation.stop" } && System.nanoTime() < started) Thread.sleep(10)
                assertTrue(server.received.any { JSONObject(it).optString("type") == "navigation.stop" })
                link.send(JSONObject().put("type", "video.offer").put("negotiation", "offer-1")
                    .put("sdp", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")) { if(it) sent.countDown() }
                assertTrue("Video offer starved behind continuous guidance", sent.await(2, TimeUnit.SECONDS))
                assertTrue("Producer stopped before assertion", flood.isAlive && flooding.get())
            } finally { flooding.set(false); flood.join() }
            link.endNegotiation("offer-1")
            worker.submit {}.get(3, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (server.received.none { JSONObject(it).optString("type") == "video.stop" } && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(server.received.any { JSONObject(it).optString("type") == "navigation.stop" })
            assertTrue(server.received.any { JSONObject(it).optString("type") == "video.stop" })
        } finally { transport.stop(); assertTrue(stopped.await(3, TimeUnit.SECONDS)); server.stopQuietly() }
    }

    @Suppress("DEPRECATION")
    private fun configureLan(create: () -> Socket) {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(301)
        shadow.addNetwork(network, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).clearCapabilities()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.setNetworkCapabilities(network, capabilities)
        shadow.setLinkProperties(network, LinkProperties().apply {
            interfaceName = "lo"
            addRoute(ReflectionHelpers.callConstructor(RouteInfo::class.java,
                ClassParameter.from(IpPrefix::class.java, IpPrefix(InetAddress.getByName("0.0.0.0"), 0)),
                ClassParameter.from(InetAddress::class.java, InetAddress.getByName("127.0.0.1")),
                ClassParameter.from(String::class.java, "lo")))
        })
        shadowOf(network).setSocketFactory(object : SocketFactory() {
            override fun createSocket() = create()
            override fun createSocket(host: String, port: Int): Socket = error("Unexpected DNS")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected DNS")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected unbound socket")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected unbound socket")
        })
    }

    private inner class TestReceiver(private val sendWelcome: Boolean) :
        WebSocketServer(InetSocketAddress("127.0.0.1", 0), 1) {
        private val started = CountDownLatch(1)
        val hello = AtomicReference<String>()
        val received = CopyOnWriteArrayList<String>()
        val requestedSubprotocol = AtomicReference<String>()
        val errorType = AtomicReference<String>()
        val genericCapability = AtomicReference<Any?>()
        val navigation = LinkedBlockingQueue<JSONObject>()
        val diagnosticsCapability = AtomicReference<Any?>()
        val diagnostics = LinkedBlockingQueue<JSONObject>()
        init {
            setWebSocketFactory(DefaultSSLWebSocketServerFactory(serverContext()))
            isDaemon = true
            connectionLostTimeout = 0
        }
        fun startAndAwait() { start(); assertTrue(started.await(3, TimeUnit.SECONDS)) }
        override fun onStart() { started.countDown() }
        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            requestedSubprotocol.set(handshake.getFieldValue("Sec-WebSocket-Protocol"))
        }
        override fun onMessage(connection: WebSocket, message: String) {
            received.add(message)
            if (JSONObject(message).getString("type") == "hello") {
                hello.set(message)
                if (sendWelcome) {
                    val welcome = JSONObject("""{"type":"welcome","version":1,"session":"test-session","navigationTimeoutMs":6000,"remoteGuidance":true,"videoTransport":"webrtc"}""")
                    genericCapability.get()?.let { welcome.put("genericRoundabouts", it) }
                    diagnosticsCapability.get()?.let { welcome.put("videoDiagnostics", it) }
                    connection.send(welcome.toString())
                }
            } else if (JSONObject(message).getString("type") == "diagnostics.video") {
                val snapshot = JSONObject(message)
                io.github.kiroha.dashcast.satellite.capture.VideoDiagnosticsWire.validate(snapshot)
                diagnostics.offer(snapshot)
                connection.send(JSONObject().put("type", "diagnostics.video.ack").put("schema", 1)
                    .put("request", snapshot.get("request")).put("status", "accepted").toString())
            } else if (JSONObject(message).getString("type") in setOf("navigation.update", "navigation.stop")) {
                navigation.offer(JSONObject(message))
            }
        }
        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
        override fun onError(connection: WebSocket?, error: Exception) { errorType.set(error.javaClass.simpleName) }
        fun stopQuietly() {
            try { stop(1_000) } catch (_: java.nio.channels.ClosedSelectorException) { }
        }
    }

    private fun profile(port: Int, hosts: List<String>): PairingProfile {
        val cert = javaClass.getResourceAsStream("/tls/receiver.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val pin = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PairingProfile.parse(JSONObject().put("version", 1).put("hosts", JSONArray(hosts))
            .put("port", port).put("path", "/satellite/v1").put("certificateSha256", pin)
            .put("token", TOKEN).toString())
    }

    private fun serverContext(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply {
            this@SatelliteTransportTest.javaClass.getResourceAsStream("/tls/test-only-server.p12")!!.use {
                load(it, "test-only".toCharArray())
            }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        return SSLContext.getInstance("TLSv1.2", "SunJSSE").apply { init(keys.keyManagers, null, null) }
    }

    private companion object {
        val TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
    }
}

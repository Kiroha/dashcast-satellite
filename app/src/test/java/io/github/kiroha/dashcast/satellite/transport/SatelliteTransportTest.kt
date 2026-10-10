package io.github.kiroha.dashcast.satellite.transport

import android.app.Application
import android.net.ConnectivityManager
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
        val requestedSubprotocol = AtomicReference<String>()
        val errorType = AtomicReference<String>()
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
            if (JSONObject(message).getString("type") == "hello") {
                hello.set(message)
                if (sendWelcome) connection.send("""{"type":"welcome","version":1,"session":"test-session","navigationTimeoutMs":6000,"remoteGuidance":true,"videoTransport":"webrtc"}""")
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

package io.github.kiroha.dashcast.satellite.pairing

import io.github.kiroha.dashcast.satellite.transport.CancellableSocketFactory
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.URI
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters

class PinnedTlsTest {
    private var removedProviders = emptyList<Pair<Int, Provider>>()

    @Before fun useJvmTlsProviders() {
        // Robolectric leaves its Android Conscrypt provider installed in this JVM. Isolate real
        // JVM socket tests from that provider's Java-21 reflection/EC incompatibilities, restoring
        // it afterwards for Android tests. Production provider selection is unchanged.
        removedProviders = Security.getProviders().mapIndexedNotNull { index, provider ->
            if (provider.javaClass.name.contains("conscrypt", ignoreCase = true)) index to provider else null
        }
        removedProviders.forEach { (_, provider) -> Security.removeProvider(provider.name) }
    }

    @After fun restoreProviders() {
        removedProviders.forEach { (index, provider) -> Security.insertProviderAt(provider, index + 1) }
    }

    private fun certificate(): X509Certificate = javaClass.getResourceAsStream("/tls/receiver.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun `self signed receiver without IP SAN is trusted only by exact DER certificate pin`() {
        val cert = certificate()
        assertNull(cert.subjectAlternativeNames)
        CertificatePinTrustManager(sha256(cert.encoded)).checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test fun `a public key SPKI pin or wrong certificate pin cannot authenticate the receiver`() {
        val cert = certificate()
        for (pin in listOf(sha256(cert.publicKey.encoded), "00".repeat(32))) {
            try { CertificatePinTrustManager(pin).checkServerTrusted(arrayOf(cert), "RSA"); fail("Invalid identity accepted") }
            catch (error: CertificateException) { assertEquals("Receiver identity mismatch", error.message) }
        }
    }

    @Test fun `missing server certificates and all client certificate requests are rejected`() {
        val manager = CertificatePinTrustManager(sha256(certificate().encoded))
        for (chain in listOf(null, emptyArray<X509Certificate>())) {
            try { manager.checkServerTrusted(chain, "RSA"); fail("Missing server identity accepted") }
            catch (_: CertificateException) {}
        }
        try { manager.checkClientTrusted(arrayOf(certificate()), "RSA"); fail("Client identity accepted") }
        catch (_: CertificateException) {}
    }

    @Test fun `real TLS socket transmits hello only after matching receiver identity`() {
        assertEquals("hello-test-token", exchange(sha256(certificate().encoded), true))
    }

    @Test fun `real TLS mismatch closes without exposing authentication token`() {
        assertNull(exchange("00".repeat(32), false))
    }

    @Test fun `actual Java WebSocket WSS exchanges hello welcome using pinned TLS12 factory`() {
        val started = CountDownLatch(1)
        val welcomed = CountDownLatch(1)
        val received = AtomicReference<String?>()
        val failure = AtomicReference<Exception?>()
        val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0), 1) {
            override fun onStart() { started.countDown() }
            override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
                if (handshake.resourceDescriptor != "/satellite/v1") connection.close(1008)
            }
            override fun onMessage(connection: WebSocket, message: String) {
                received.set(message)
                connection.send("{\"type\":\"welcome\",\"version\":1}")
            }
            override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
            override fun onError(connection: WebSocket?, error: Exception) { failure.set(error) }
        }
        server.setWebSocketFactory(DefaultSSLWebSocketServerFactory(serverContext()))
        server.isDaemon = true
        server.start()
        var client: WebSocketClient? = null
        try {
            assertTrue("Receiver did not start", started.await(5, TimeUnit.SECONDS))
            val port = server.port
            val sender = object : WebSocketClient(URI("wss://127.0.0.1:$port/satellite/v1")) {
                override fun onSetSSLParameters(parameters: SSLParameters) { parameters.endpointIdentificationAlgorithm = null }
                override fun onOpen(handshake: ServerHandshake) { send("{\"type\":\"hello\",\"version\":1,\"token\":\"test-only\"}") }
                override fun onMessage(message: String) { if (message.contains("welcome")) welcomed.countDown() }
                override fun onClose(code: Int, reason: String, remote: Boolean) = Unit
                override fun onError(error: Exception) { failure.set(error) }
            }
            sender.setSocketFactory(object : SocketFactory() {
                override fun createSocket(): Socket {
                    val raw = Socket("127.0.0.1", port)
                    return PinnedTls.prepareSocket(raw, "127.0.0.1", port, sha256(certificate().encoded))
                        .apply { enabledProtocols = arrayOf("TLSv1.2") }
                }
                override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
                override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
            })
            sender.isTcpNoDelay = true
            sender.connectionLostTimeout = 0
            sender.isDaemon = true
            client = sender
            assertTrue("WSS handshake failed: ${failure.get()}", sender.connectBlocking(8, TimeUnit.SECONDS))
            assertEquals("TLSv1.2", sender.sslSession.protocol)
            assertTrue("No welcome: ${failure.get()}", welcomed.await(5, TimeUnit.SECONDS))
            assertEquals("{\"type\":\"hello\",\"version\":1,\"token\":\"test-only\"}", received.get())
            assertNull(failure.get())
        } finally {
            client?.closeConnection(1000, "test done")
            client?.socket?.close()
            try { server.stop(1_000) } catch (_: java.nio.channels.ClosedSelectorException) {
                // Java-WebSocket may already have closed its selector during client teardown.
            }
        }
    }

    @Test fun `cancelling actual WebSocket during TLS socket creation leaves no late connection`() {
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptThread = AtomicReference<Thread>()
        val rawSocket = AtomicReference<Socket>()
        val tlsSocket = AtomicReference<SSLSocket>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5_000
            val factory = CancellableSocketFactory(
                createRawSocket = { Socket("127.0.0.1", server.localPort).also { rawSocket.set(it) } },
                prepareSocket = { raw ->
                    PinnedTls.prepareSocket(raw, "127.0.0.1", server.localPort, sha256(certificate().encoded)).also {
                        tlsSocket.set(it)
                        attemptThread.set(Thread.currentThread())
                        prepared.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            val client = object : WebSocketClient(URI("wss://127.0.0.1:${server.localPort}/satellite/v1")) {
                override fun onOpen(handshake: ServerHandshake) = fail("Cancelled connection opened")
                override fun onMessage(message: String) = Unit
                override fun onClose(code: Int, reason: String, remote: Boolean) = Unit
                override fun onError(error: Exception) = Unit // Cancellation fails socket creation.
            }
            client.setSocketFactory(factory)
            client.isDaemon = true
            client.connectionLostTimeout = 0
            try {
                client.connect()
                assertTrue(prepared.await(5, TimeUnit.SECONDS))
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    assertNull("Library has not yet acquired the factory socket", client.socket)
                    factory.close()
                    client.closeConnection(1000, "session ended")
                    assertTrue(rawSocket.get().isClosed)
                    release.countDown()
                    attemptThread.get().join(5_000)
                    assertFalse("Connect thread survived cancellation", attemptThread.get().isAlive)
                    assertTrue(tlsSocket.get().isClosed)
                    assertNull("Factory must not hand a cancelled socket to a new writer", client.socket)
                    assertEquals("No TLS or HTTP bytes may be sent after cancellation", -1, peer.getInputStream().read())
                }
            } finally {
                release.countDown()
                factory.close()
                client.closeConnection(1000, "test done")
                client.socket?.close()
                attemptThread.get()?.join(5_000)
            }
        }
    }

    private fun exchange(pin: String, trusted: Boolean): String? {
        val serverContext = serverContext()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val address = InetAddress.getByName("127.0.0.1")
            (serverContext.serverSocketFactory.createServerSocket(0, 1, address) as SSLServerSocket).use { server ->
                server.soTimeout = 5_000
                val received = executor.submit<String?> {
                    try {
                        (server.accept() as SSLSocket).use { connection ->
                            connection.soTimeout = 5_000
                            connection.startHandshake()
                            connection.inputStream.bufferedReader().readLine()
                        }
                    } catch (error: IOException) {
                        if (trusted) throw error
                        null
                    }
                }
                try {
                    Socket(address, server.localPort).use { raw ->
                        PinnedTls.prepareSocket(raw, "127.0.0.1", server.localPort, pin).use { secured ->
                            secured.startHandshake()
                            secured.outputStream.write("hello-test-token\n".toByteArray())
                            secured.outputStream.flush()
                        }
                    }
                } catch (error: SSLException) {
                    if (trusted) throw error
                    // Expected mismatch: application write is never reached.
                }
                return received.get(7, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }

    private fun serverContext(): SSLContext {
        // This committed identity is public test material, never an application signing key.
        val store = KeyStore.getInstance("PKCS12").apply {
            this@PinnedTlsTest.javaClass.getResourceAsStream("/tls/test-only-server.p12")!!.use { load(it, "test-only".toCharArray()) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only".toCharArray())
        }
        // Robolectric installs Conscrypt globally; its JVM server socket needs reflective module
        // access on Java 21. Use the JDK server provider, retaining the production client provider.
        return SSLContext.getInstance("TLSv1.2", "SunJSSE").apply { init(keys.keyManagers, null, null) }
    }
}

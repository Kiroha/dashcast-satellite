package io.github.kiroha.dashcast.satellite.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import io.github.kiroha.dashcast.satellite.navigation.Observation
import io.github.kiroha.dashcast.satellite.pairing.PairingProfile
import io.github.kiroha.dashcast.satellite.pairing.PinnedTls
import io.github.kiroha.dashcast.satellite.pairing.SatelliteDeviceIdentity
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLParameters

enum class TransportState { STOPPED, WAITING_FOR_LAN, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, PAIRING_REJECTED }

data class TransportStatus(
    val state: TransportState,
    val remoteGuidance: Boolean = false,
    /** Fixed local code only, never server messages, exception text or route data. */
    val detail: String? = null,
    /** Numeric LAN address of the current or most recent attempt; never a URL or credentials. */
    val endpointHost: String? = null,
)

/** Single WSS connection. All state changes run on one worker; source ingress occupies one slot. */
class SatelliteTransport internal constructor(
    context: Context,
    private val onStatus: (TransportStatus) -> Unit,
    private val elapsedRealtime: () -> Long,
) {
    constructor(context: Context, onStatus: (TransportStatus) -> Unit) :
        this(context, onStatus, SystemClock::elapsedRealtime)

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private var deviceIdentity: SatelliteDeviceIdentity? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "satellite-control").apply { isDaemon = true }
    }
    private val latest = LatestGuidance()
    private val backoff = ReconnectBackoff()
    private val endpoints = LanEndpoints<Network>()
    private val networks = LinkedHashSet<Network>()
    private var profile: PairingProfile? = null
    private var socket: WebSocketClient? = null
    private var socketFactory: LanTlsSocketFactory? = null
    private var selectedNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var authenticated = false
    private var remoteGuidance = false
    private var deadline = 0L
    private var connectedAt = 0L
    private var lastInbound = 0L
    private var lastPing = 0L
    private var bufferedSince = 0L
    private var nextAttempt = 0L
    private var endpointHost: String? = null
    private var lastFailureDetail: String? = null
    private var lastStatus: TransportStatus? = null
    @Volatile private var running = false
    @Volatile private var disposed = false

    init {
        worker.scheduleWithFixedDelay({
            try { tick() } catch (_: Exception) { fail("transport_failure") }
        }, 0, 100, TimeUnit.MILLISECONDS)
    }

    fun start(profile: PairingProfile) {
        check(!disposed) { "Transport already stopped" }
        running = true
        worker.execute {
            if (!running) return@execute
            disconnect()
            latest.clear()
            deviceIdentity = runCatching { SatelliteDeviceIdentity.load(appContext) }.getOrNull()
            if (!running) return@execute
            this.profile = profile
            endpoints.reset()
            endpointHost = null
            lastFailureDetail = null
            nextAttempt = 0
            backoff.reset()
            observeNetworks()
            publish(TransportState.WAITING_FOR_LAN)
        }
    }

    fun offer(observation: Observation) {
        if (running && !disposed) latest.offer(observation)
    }

    /** Final lifecycle operation: construct a new transport if the service starts again. */
    fun stop() {
        if (disposed) return
        disposed = true
        running = false
        worker.execute {
            try {
                val current = socket
                if (authenticated && current?.isOpen == true && !current.hasBufferedData()) {
                    latest.offer(Observation.Stop(elapsedRealtime()))
                    latest.next(elapsedRealtime())?.let { current.send(it) }
                }
            } catch (_: Exception) { /* Disconnect clears receiver state even if stop cannot be sent. */ }
            finally {
                disconnect()
                latest.clear()
                networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {} }
                networkCallback = null
                profile = null
                publish(TransportState.STOPPED)
                worker.shutdown()
            }
        }
    }

    private fun observeNetworks() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = dispatch {
                networks.add(network)
                if (socket == null) nextAttempt = 0
            }
            override fun onLost(network: Network) = dispatch {
                networks.remove(network)
                if (network == selectedNetwork) fail("lan_lost")
            }
        }
        connectivity.allNetworks.filterTo(networks) { network ->
            connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback)
        networkCallback = callback
    }

    private fun tick() {
        if (!running) return
        val now = elapsedRealtime()
        val current = socket
        if (current == null) {
            if (networks.isEmpty()) { publish(TransportState.WAITING_FOR_LAN); return }
            if (now >= nextAttempt) connect(now)
            return
        }
        if (!authenticated) {
            if (now >= deadline) fail(if (current.isOpen) "authentication_timeout" else "connection_timeout")
            return
        }
        if (now - lastInbound >= 7_000) { fail("heartbeat_timeout"); return }
        if (now - connectedAt >= 20_000) backoff.reset()
        if (current.hasBufferedData()) {
            if (bufferedSince == 0L) bufferedSince = now
            if (now - bufferedSince >= 1_000) fail("slow_connection")
            return
        }
        bufferedSince = 0
        // Heartbeat has a deadline even when a noisy source keeps replacing the pending value.
        if (now - lastPing >= 2_000) {
            current.send("{\"type\":\"ping\"}")
            lastPing = now
            return
        }
        // A single pending value replaces older unsent observations. Source age is computed here.
        latest.next(now)?.let { current.send(it) }
    }

    private fun connect(now: Long) {
        val pairing = profile ?: return
        val choices = networks.take(LanEndpoints.MAX_LAN_NETWORKS)
        if (choices.isEmpty()) return
        val candidates = choices.flatMap { network ->
            val properties = connectivity.getLinkProperties(network)
            val gateways = properties?.routes?.mapNotNull { it.gateway }.orEmpty()
            val prefixes = properties?.routes?.map {
                LanEndpointRank.Prefix(it.destination.address, it.destination.prefixLength)
            }.orEmpty()
            pairing.hosts.mapNotNull { host ->
                PairingProfile.numericLocalAddress(host)?.let { address ->
                    LanEndpoints.Endpoint(network, host, LanEndpointRank.rank(address, gateways, prefixes))
                }
            }
        }
        val endpoint = endpoints.next(candidates) ?: return
        val host = endpoint.host
        val network = endpoint.network
        val client = object : WebSocketClient(pairing.uri(host),
            Draft_6455(emptyList(), NavigationWire.MAX_BYTES), emptyMap(), 5_000) {
            override fun onSetSSLParameters(parameters: SSLParameters) {
                // The reviewed profile pins this self-signed installation certificate without IP SANs.
                // Trust remains enforced by CertificatePinTrustManager during the TLS handshake.
                parameters.endpointIdentificationAlgorithm = null
            }
            override fun onOpen(handshake: ServerHandshake) = dispatch {
                if (this@SatelliteTransport.socket !== this) return@dispatch
                // Java-WebSocket has now completed pinned TLS and HTTP upgrade exactly once.
                this.socket.soTimeout = 0
                deadline = elapsedRealtime() + 5_000
                publish(TransportState.AUTHENTICATING, lastFailureDetail)
                val hello = JSONObject().put("type", "hello").put("version", 1).put("token", pairing.token)
                deviceIdentity?.let { hello.put("device", it.toJson()) }
                send(hello.toString())
            }
            override fun onMessage(message: String) = dispatch {
                if (this@SatelliteTransport.socket === this) receive(message)
            }
            override fun onMessage(bytes: ByteBuffer) = dispatch {
                if (this@SatelliteTransport.socket === this) fail("invalid_server_message")
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) = dispatch {
                if (this@SatelliteTransport.socket === this) fail(if (code == 1008 &&
                    (reason == "authentication failed" || reason == "disabled or revoked")) "pairing_rejected" else "connection_closed")
            }
            override fun onError(error: Exception) = dispatch {
                if (this@SatelliteTransport.socket === this) fail(TransportFailure.detail(error))
            }
        }
        val factory = LanTlsSocketFactory(network, connectivity, host, pairing)
        client.setSocketFactory(factory)
        client.isTcpNoDelay = true
        client.connectionLostTimeout = 0 // One explicit bounded heartbeat policy, no second scheduler.
        client.isDaemon = true
        selectedNetwork = network
        endpointHost = host
        socketFactory = factory
        socket = client
        authenticated = false
        remoteGuidance = false
        latest.newSession()
        bufferedSince = 0
        deadline = now + 10_000
        publish(TransportState.CONNECTING, lastFailureDetail)
        client.connect()
    }

    private fun receive(message: String) {
        try {
            require(message.toByteArray(Charsets.UTF_8).size <= NavigationWire.MAX_BYTES)
            val json = JSONObject(message)
            val type = json.get("type")
            require(type is String)
            val now = elapsedRealtime()
            if (!authenticated) {
                require(type == "welcome")
                NavigationWire.integer(json, "version", 1, 1)
                NavigationWire.integer(json, "navigationTimeoutMs", 6_000, 6_000)
                require(json.get("remoteGuidance") is Boolean)
                require(json.get("session") is String && json.getString("session").length in 1..128)
                require(json.get("videoTransport") == "webrtc")
                authenticated = true
                remoteGuidance = json.getBoolean("remoteGuidance")
                connectedAt = now
                lastPing = 0
                endpoints.connected()
                lastFailureDetail = null
                publish(TransportState.CONNECTED)
            } else when (type) {
                "pong", "video.ready", "video.closed" -> Unit // Video capture is a separate milestone.
                "error" -> {
                    when (json.get("code")) {
                        "guidance_disabled" -> {
                            remoteGuidance = false
                            publish(TransportState.CONNECTED, "guidance_disabled")
                        }
                        "stale_sequence", "invalid_message" -> { fail("protocol_rejected"); return }
                        else -> Unit
                    }
                }
                else -> Unit // Additive future server events do not tear down guidance.
            }
            lastInbound = now
        } catch (_: Exception) { fail("invalid_server_message") }
    }

    private fun fail(detail: String) {
        if (!running) return
        val wasAuthenticated = authenticated
        disconnect()
        nextAttempt = elapsedRealtime() + if (!wasAuthenticated && endpoints.hasMoreInCycle)
            LanEndpoints.NEXT_ENDPOINT_DELAY_MS else backoff.nextDelayMs()
        lastFailureDetail = detail
        publish(TransportFailure.state(detail), detail)
    }

    private fun disconnect() {
        val old = socket
        val oldFactory = socketFactory
        socket = null
        socketFactory = null
        authenticated = false
        remoteGuidance = false
        selectedNetwork = null
        // The factory also owns raw/TLS sockets before Java-WebSocket has assigned its socket.
        oldFactory?.close()
        try { old?.closeConnection(1000, "session ended"); old?.socket?.close() } catch (_: Exception) {}
    }

    private fun dispatch(action: () -> Unit) {
        if (!disposed) try { worker.execute { if (running) action() } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun publish(state: TransportState, detail: String? = null) {
        val status = TransportStatus(state, authenticated && remoteGuidance, detail,
            if (state == TransportState.STOPPED || state == TransportState.WAITING_FOR_LAN) null else endpointHost)
        if (status != lastStatus) {
            lastStatus = status
            try { onStatus(status) } catch (_: Exception) { /* UI callbacks cannot kill network recovery. */ }
        }
    }
}

/** Only creates sockets on the selected LAN Network. It never changes process/default routing. */
private class LanTlsSocketFactory(
    network: Network,
    connectivity: ConnectivityManager,
    host: String,
    pairing: PairingProfile,
) : CancellableSocketFactory(
    createRawSocket = { network.socketFactory.createSocket() },
    prepareSocket = { raw ->
        val address = PairingProfile.numericLocalAddress(host) ?: error("Invalid LAN address")
        val scoped = if (address is Inet6Address && address.isLinkLocalAddress) {
            val interfaceName = connectivity.getLinkProperties(network)?.interfaceName ?: error("Missing LAN interface")
            Inet6Address.getByAddress(null, address.address, NetworkInterface.getByName(interfaceName))
        } else address
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(scoped, pairing.port), 4_000)
        PinnedTls.prepareSocket(raw, host, pairing.port, pairing.certificateSha256)
    },
)

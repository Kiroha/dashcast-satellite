package io.github.kiroha.dashcast.satellite.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Timer
import java.util.TimerTask
import javax.net.SocketFactory

/** A temporary, code-authenticated exchange on the car's LAN; the code never leaves this device. */
internal class CodePairingClient(
    private val candidates: (String?) -> List<Candidate>,
) : Closeable {
    constructor(context: Context) : this({ host -> lanCandidates(context, host) })

    internal class Candidate(val factory: SocketFactory, val address: InetSocketAddress)
    enum class Failure { NO_LAN, UNAVAILABLE, CANCELLED }
    class PairingException(val failure: Failure) : IOException("Code pairing unavailable")

    private val lock = Any()
    private var closed = false
    private var socket: Socket? = null

    fun pair(rawCode: String, manualHost: String?): PairingProfile {
        ensureActive()
        val code = SatellitePairingCode.normalize(rawCode)
        if (manualHost != null) require(PairingProfile.numericLocalAddress(manualHost) != null)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000L
        val available = candidates(manualHost).take(MAX_CANDIDATES)
        if (available.isEmpty()) throw PairingException(Failure.NO_LAN)
        for (candidate in available) {
            ensureActive()
            if (System.nanoTime() >= deadline) break
            try {
                val profile = PairingProfile.parse(exchange(candidate, code,
                    minOf(deadline, System.nanoTime() + ATTEMPT_TIMEOUT_MS * 1_000_000L)))
                ensureActive()
                return profile
            } catch (_: Exception) {
                // Exceptions can contain response data. Keep both logs and UI free of their messages.
                ensureActive()
            }
        }
        throw PairingException(Failure.UNAVAILABLE)
    }

    private fun exchange(candidate: Candidate, code: String, deadline: Long): String {
        // Socket creation can race cancellation. Adopt under the same lock used by close().
        val created = candidate.factory.createSocket()
        synchronized(lock) {
            if (closed) {
                runCatching { created.close() }
                throw PairingException(Failure.CANCELLED)
            }
            socket = created
        }
        val timeout = Timer("pairing-timeout", true)
        try {
            // SO_TIMEOUT covers reads only. Closing at the deadline also interrupts a stalled write.
            timeout.schedule(object : TimerTask() {
                override fun run() { runCatching { created.close() } }
            }, remainingMillis(deadline).toLong())
            created.tcpNoDelay = true
            created.connect(candidate.address, minOf(CONNECT_TIMEOUT_MS, remainingMillis(deadline)))
            ensureActive()
            val input = created.getInputStream()
            val output = DataOutputStream(created.getOutputStream())
            return SatellitePairingExchange.client(code, send = { message ->
                remainingMillis(deadline)
                val bytes = message.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..SatellitePairingCode.MAX_FRAME_BYTES)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }, receive = {
                fun readFully(bytes: ByteArray) {
                    var count = 0
                    while (count < bytes.size) {
                        created.soTimeout = remainingMillis(deadline)
                        val read = input.read(bytes, count, bytes.size - count)
                        require(read > 0)
                        count += read
                    }
                }
                val prefix = ByteArray(4).also(::readFully)
                val length = ByteBuffer.wrap(prefix).int
                require(length in 1..SatellitePairingCode.MAX_FRAME_BYTES)
                val bytes = ByteArray(length).also(::readFully)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }).also { remainingMillis(deadline) }
        } finally {
            timeout.cancel()
            synchronized(lock) { if (socket === created) socket = null }
            runCatching { created.close() }
        }
    }

    override fun close() {
        val current = synchronized(lock) {
            closed = true
            socket.also { socket = null }
        }
        runCatching { current?.close() }
    }

    private fun ensureActive() = synchronized(lock) {
        if (closed) throw PairingException(Failure.CANCELLED)
    }

    private fun remainingMillis(deadline: Long): Int {
        ensureActive()
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) throw IOException("Pairing timed out")
        return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(ATTEMPT_TIMEOUT_MS).toInt()
    }

    companion object {
        private const val MAX_CANDIDATES = 2
        private const val ATTEMPT_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val TOTAL_TIMEOUT_MS = 30_000L

        /** Wi-Fi without validated Internet is expected when connected to the car's hotspot. */
        @Suppress("DEPRECATION")
        private fun lanCandidates(context: Context, manualHost: String?): List<Candidate> {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val manualAddress = manualHost?.let { PairingProfile.numericLocalAddress(it) }
            val result = mutableListOf<Candidate>()
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue
                val properties = manager.getLinkProperties(network) ?: continue
                val addresses = if (manualAddress != null) listOf(manualAddress) else
                    properties.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway }
                for (address in addresses.distinct()) {
                    val literal = address.hostAddress?.substringBefore('%') ?: continue
                    if (PairingProfile.numericLocalAddress(literal) == null) continue
                    val scoped = scope(address, properties.interfaceName) ?: continue
                    result.add(Candidate(network.socketFactory, InetSocketAddress(scoped, SatellitePairingCode.PORT)))
                }
            }
            // Hotspots commonly expose an IPv4 gateway even when IPv6 is present. Try it first.
            return result.sortedBy { if (it.address.address is Inet6Address) 1 else 0 }.take(MAX_CANDIDATES)
        }

        private fun scope(address: InetAddress, interfaceName: String?): InetAddress? = try {
            if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
                val iface = interfaceName?.let(NetworkInterface::getByName) ?: return null
                Inet6Address.getByAddress(null, address.address, iface)
            } else address
        } catch (_: Exception) { null }
    }
}

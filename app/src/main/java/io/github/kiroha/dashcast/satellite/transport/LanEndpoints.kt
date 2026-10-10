package io.github.kiroha.dashcast.satellite.transport

import java.net.InetAddress

/** Ranking inputs come from the selected LAN's actual link properties, never default routing. */
internal object LanEndpointRank {
    class Prefix(val address: InetAddress, val length: Int)

    fun rank(address: InetAddress, gateways: List<InetAddress>, prefixes: List<Prefix>): Int {
        val location = when {
            gateways.any { !it.isAnyLocalAddress && it == address } -> 0
            prefixes.any { matches(address, it) } -> 1
            else -> 2
        }
        return location * 2 + if (address.address.size == 4) 0 else 1
    }

    private fun matches(address: InetAddress, prefix: Prefix): Boolean {
        val candidate = address.address
        val network = prefix.address.address
        // A default route reaches everything and provides no evidence that a peer is on this LAN.
        if (candidate.size != network.size || prefix.length !in 1..(network.size * 8)) return false
        for (index in candidate.indices) {
            val bits = (prefix.length - index * 8).coerceIn(0, 8)
            if (bits == 0) break
            val mask = (0xff shl (8 - bits)) and 0xff
            if ((candidate[index].toInt() and mask) != (network[index].toInt() and mask)) return false
        }
        return true
    }
}

/** One bounded cycle over LAN/host pairs; retry backoff advances only after a complete cycle. */
internal class LanEndpoints<N> {
    class Endpoint<N>(val network: N, val host: String, val rank: Int)
    private var ordered = emptyList<Endpoint<N>>()
    private var next = 0
    private var attempted: Endpoint<N>? = null
    private var preferred: Endpoint<N>? = null

    fun next(candidates: List<Endpoint<N>>): Endpoint<N>? {
        val unique = candidates.distinctBy { it.network to it.host }
            .sortedBy { if (same(it, preferred)) -1 else it.rank }.take(MAX_ENDPOINTS)
        if (ordered.size != unique.size || ordered.indices.any { !same(ordered[it], unique[it]) }) {
            ordered = unique
            next = 0
        }
        if (ordered.isEmpty()) return null
        if (next == ordered.size) next = 0
        return ordered[next++].also { attempted = it }
    }

    val hasMoreInCycle: Boolean get() = next < ordered.size

    fun connected() {
        preferred = attempted
        ordered = emptyList()
        next = 0
    }

    fun reset() {
        ordered = emptyList()
        next = 0
        attempted = null
        preferred = null
    }

    private fun same(first: Endpoint<N>, second: Endpoint<N>?): Boolean =
        second != null && first.network == second.network && first.host == second.host

    companion object {
        const val MAX_LAN_NETWORKS = 4
        const val MAX_ENDPOINTS = 64 // Four LAN networks times the profile's sixteen-host bound.
        const val NEXT_ENDPOINT_DELAY_MS = 250L
    }
}

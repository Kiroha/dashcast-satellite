package io.github.kiroha.dashcast.satellite.capture

import io.github.kiroha.dashcast.satellite.pairing.PairingProfile
import org.json.JSONObject
import java.util.ArrayDeque

/** No DNS/STUN/TURN/IPv6/cellular fallbacks. Canonical browser mDNS names are mapped only
 * to the peer address of this authenticated TLS socket, preserving the candidate UDP port. */
object VideoPolicy {
    const val WIDTH = 960
    const val HEIGHT = 540
    const val FPS = 15
    const val BITRATE = 1_500_000
    const val MAX_ICE = 128
    fun host(candidate: String): String? {
        if (candidate.length !in 1..2048) return null
        val fields = candidate.trim().split(Regex("\\s+"))
        if (fields.size < 8 || !fields[0].startsWith("candidate:") || fields[1] != "1" ||
            !fields[2].equals("udp", true) || fields[6] != "typ" || fields[7] != "host" ||
            fields[5].toIntOrNull() !in 1..65535) return null
        val address = PairingProfile.numericLocalIpv4Address(fields[4]) ?: return null
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return null
        // A host candidate must not smuggle a related public/cellular address.
        if (fields.drop(8).any { it == "raddr" || it == "rport" }) return null
        return fields[4]
    }
    fun localCandidate(candidate: String, addresses: Set<String>) = host(candidate) in addresses
    fun remoteCandidate(candidate: String) = host(candidate) != null
    fun receiverCandidate(candidate: String, receiverHost: String): String? {
        if (PairingProfile.numericLocalIpv4Address(receiverHost) == null) return null
        val fields = candidate.trim().split(Regex("\\s+")).toMutableList()
        if (fields.size < 8) return null
        val name = fields[4]
        if (name.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.local", RegexOption.IGNORE_CASE))) {
            fields[4] = receiverHost
        }
        val value = fields.joinToString(" ")
        // Restrict every remote media attempt to the authenticated car's actual LAN socket peer.
        return value.takeIf { host(it) == receiverHost }
    }
    fun sdp(value: String, localAddresses: Set<String>? = null, receiverHost: String? = null): String {
        require(value.length in 1..60_000)
        val lines = value.split(Regex("\\r?\\n"))
        require(lines.count { it.startsWith("m=") } == 1 && lines.any { it.startsWith("m=video ") })
        return lines.mapNotNull { line ->
            when {
                line.startsWith("a=candidate:") -> {
                    val candidate = line.removePrefix("a=")
                    when {
                        localAddresses != null -> line.takeIf { localCandidate(candidate, localAddresses) }
                        receiverHost != null -> receiverCandidate(candidate, receiverHost)?.let { "a=$it" }
                        else -> { require(remoteCandidate(candidate)); line }
                    }
                }
                line.startsWith("c=") -> {
                    // ICE candidates carry the actual peer. A neutral connection address avoids
                    // an incidental cellular/default route address in either SDP direction.
                    require(line.startsWith("c=IN IP4 ") || line.startsWith("c=IN IP6 "))
                    "c=IN IP4 0.0.0.0"
                }
                else -> line
            }
        }.joinToString("\r\n")
    }
    fun validWire(message: JSONObject): Boolean = runCatching {
        require(message.toString().toByteArray(Charsets.UTF_8).size <= 65_536)
        require(message.getString("negotiation").matches(Regex("[A-Za-z0-9_-]{1,64}")))
        when (message.getString("type")) {
            "video.offer" -> sdp(message.getString("sdp"))
            "video.ice" -> {
                require(remoteCandidate(message.getString("candidate")))
                require(message.getString("sdpMid").length <= 64)
                val index = message.get("sdpMLineIndex")
                require(index is Int && index in 0..8)
            }
            "video.stop" -> Unit
            else -> error("unsupported_video_message")
        }
    }.isSuccess
}

/** Fence both directions and bound ICE across asynchronous SDP callbacks. */
internal class VideoNegotiation(val id: String) {
    private val outbound = ArrayDeque<String>()
    private val inbound = ArrayDeque<String>()
    private var sent = false
    private var answered = false
    private var localCount = 0
    private var remoteCount = 0
    var closed = false
        private set
    fun localIce(candidate: String, deliver: (String) -> Unit): Boolean {
        if (closed || ++localCount > VideoPolicy.MAX_ICE) return false
        if (sent) deliver(candidate) else outbound.add(candidate)
        return true
    }
    fun remoteIce(candidate: String, deliver: (String) -> Unit): Boolean {
        if (closed || ++remoteCount > VideoPolicy.MAX_ICE) return false
        if (answered) deliver(candidate) else inbound.add(candidate)
        return true
    }
    fun offerSent(deliver: (String) -> Unit) { if (!closed) { sent = true; while(outbound.isNotEmpty()) deliver(outbound.remove()) } }
    fun answerApplied(deliver: (String) -> Unit) { if (!closed) { answered = true; while(inbound.isNotEmpty()) deliver(inbound.remove()) } }
    fun close() { closed = true; outbound.clear(); inbound.clear() }
}

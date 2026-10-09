package io.github.kiroha.dashcast.satellite.pairing

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.InetAddress
import java.net.URI
import java.util.Base64

/** Credentials are deliberately excluded from generated data-class/debug representations. */
class PairingProfile private constructor(
    val hosts: List<String>,
    val port: Int,
    val path: String,
    val certificateSha256: String,
    val token: String,
) {
    fun uri(host: String): URI {
        require(host in hosts)
        return URI("wss", null, host, port, path, null, null)
    }

    internal fun encoded(): String = JSONObject().put("version", 1)
        .put("hosts", JSONArray(hosts)).put("port", port).put("path", path)
        .put("certificateSha256", certificateSha256).put("token", token).toString()

    override fun toString(): String = "PairingProfile([redacted])"

    companion object {
        const val MAX_BYTES = 16_384

        fun parse(text: String): PairingProfile {
            // Do not propagate JSON exceptions: their messages may include profile contents.
            try {
                require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
                val parser = JSONTokener(text)
                val json = parser.nextValue() as? JSONObject ?: error("Expected object")
                require(parser.nextClean() == '\u0000')
                require(integer(json, "version") == 1L)
                val hosts = json.getJSONArray("hosts")
                require(hosts.length() in 1..16)
                val values = (0 until hosts.length()).map {
                    val host = hosts.get(it)
                    require(host is String && numericLocalAddress(host) != null)
                    host
                }.distinct()
                val port = integer(json, "port")
                require(port in 1..65535)
                require(json.get("path") == "/satellite/v1")
                val fingerprint = json.get("certificateSha256")
                require(fingerprint is String && fingerprint.matches(Regex("[0-9a-f]{64}")))
                val token = json.get("token")
                require(token is String && token.matches(Regex("[A-Za-z0-9_-]{43}")))
                val tokenBytes = Base64.getUrlDecoder().decode(token)
                require(tokenBytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes) == token)
                return PairingProfile(values, port.toInt(), "/satellite/v1", fingerprint, token)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid pairing profile")
            }
        }

        private fun integer(json: JSONObject, name: String): Long {
            val value = json.get(name)
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }

        /** Never resolve user-provided DNS names or accept userinfo, paths, scopes or brackets. */
        internal fun numericLocalAddress(host: String): InetAddress? = try {
            require(host.length in 2..45)
            if (host.contains(':')) {
                require(host.matches(Regex("[0-9a-fA-F:.]+")))
            } else {
                val parts = host.split('.')
                require(parts.size == 4)
                require(parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 })
            }
            InetAddress.getByName(host).takeIf {
                !it.isAnyLocalAddress && !it.isMulticastAddress &&
                    (it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress ||
                        (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc))
            }
        } catch (_: Exception) { null }
    }
}

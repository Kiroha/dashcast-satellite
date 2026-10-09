package io.github.kiroha.dashcast.satellite.pairing

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PairingProfileTest {
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private fun profile() = JSONObject().put("version", 1).put("hosts", JSONArray(listOf("192.168.43.1")))
        .put("port", 47832).put("path", "/satellite/v1").put("certificateSha256", "ab".repeat(32)).put("token", secret)

    @Test fun `accepts only numeric local endpoints without DNS resolution`() {
        for (host in listOf("192.168.43.1", "10.0.0.1", "172.16.1.2", "169.254.1.2", "127.0.0.1", "::1", "fd01::1", "fe80::1")) {
            assertEquals(host, PairingProfile.parse(profile().put("hosts", JSONArray(listOf(host))).toString()).hosts.single())
        }
        for (host in listOf("example.com", "localhost", "0.0.0.0", "8.8.8.8", "224.0.0.1", "172.32.0.1", "::", "ff02::1", "2001:4860::1", "192.168.001.1", "[::1]", "fe80::1%wlan0", "192.168.1.1/path", "user@192.168.1.1")) {
            rejects(profile().put("hosts", JSONArray(listOf(host))).toString())
        }
    }

    @Test fun `types token length scheme path and pin cannot be coerced`() {
        for ((key, value) in listOf("version" to "1", "version" to 2, "port" to "47832", "port" to 0,
            "port" to 65536, "token" to secret.dropLast(1), "token" to "$secret=", "path" to "/other",
            "certificateSha256" to "AB".repeat(32), "hosts" to JSONArray())) {
            rejects(profile().put(key, value).toString())
        }
        rejects(profile().toString().replace("\"version\":1", "\"version\":1.0"))
        rejects(profile().toString() + " trailing input")
        rejects("[]")
        rejects("x".repeat(PairingProfile.MAX_BYTES + 1))
    }

    @Test fun `URI brackets IPv6 and never contains credentials`() {
        val parsed = PairingProfile.parse(profile().put("hosts", JSONArray(listOf("fd01::1"))).toString())
        assertEquals("wss://[fd01::1]:47832/satellite/v1", parsed.uri("fd01::1").toString())
        assertFalse(parsed.toString().contains(secret))
        assertFalse(parsed.toString().contains(parsed.certificateSha256))
        assertNull(parsed.uri("fd01::1").userInfo)
        assertNull(parsed.uri("fd01::1").query)
    }

    private fun rejects(text: String) {
        try { PairingProfile.parse(text); fail("Invalid profile accepted") }
        catch (error: IllegalArgumentException) {
            assertEquals("Invalid pairing profile", error.message)
            assertNull(error.cause)
        }
    }
}

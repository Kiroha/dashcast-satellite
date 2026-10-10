package io.github.kiroha.dashcast.satellite.pairing

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedReceiverTest {
    @Test fun `legacy profile provides a receiver display without copying authentication material`() {
        val token = "A".repeat(43)
        val pin = "0123456789abcdef".repeat(4)
        val profile = PairingProfile.parse(JSONObject().put("version", 1)
            .put("hosts", JSONArray(listOf("192.168.49.1", "10.0.0.1")))
            .put("port", 47832).put("path", "/satellite/v1")
            .put("certificateSha256", pin).put("token", token).toString())
        val display = SavedReceiver.from(profile)
        assertEquals("0123-4567-89AB-CDEF", display.displayId)
        assertEquals(listOf("192.168.49.1", "10.0.0.1"), display.hosts)
        assertFalse(display.toString().contains(token))
        assertFalse(display.toString().contains(pin))
    }
}

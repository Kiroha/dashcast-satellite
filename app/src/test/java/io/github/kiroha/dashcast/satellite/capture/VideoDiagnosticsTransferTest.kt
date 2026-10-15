package io.github.kiroha.dashcast.satellite.capture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoDiagnosticsTransferTest {
    private fun fixtures() = JSONObject(File("../protocol/v1/diagnostics-video-fixtures.json").readText())
    private fun message(request: Long = 1) = fixtures().getJSONArray("accepted").getJSONObject(0).put("request", request)
    private fun ack(request: Any = 1, status: String = "accepted") = JSONObject().put("type", "diagnostics.video.ack")
        .put("schema", 1).put("request", request).put("status", status)
    @Test fun `shared receiver fixtures reject content unbounded histories and scalar coercion`() {
        val cases = fixtures()
        val good = cases.getJSONArray("accepted")
        repeat(good.length()) { VideoDiagnosticsWire.validate(good.getJSONObject(it)) }
        val bad = cases.getJSONArray("rejected")
        repeat(bad.length()) { index ->
            try { VideoDiagnosticsWire.validate(bad.getJSONObject(index)); fail("Rejected fixture accepted: $index") }
            catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
        }
    }
    @Test fun `only exact versioned integer capability allows sending`() {
        for (value in listOf(null, JSONObject.NULL, true, false, "1", 1.5, 0, 2)) {
            val welcome = JSONObject(); value?.let { welcome.put("videoDiagnostics", it) }
            assertFalse(VideoDiagnosticsWire.supported(welcome))
        }
        assertTrue(VideoDiagnosticsWire.supported(JSONObject().put("videoDiagnostics", 1)))
        assertTrue(VideoDiagnosticsWire.supported(JSONObject().put("videoDiagnostics", 1L)))
    }
    @Test fun `one latest slot accumulates real queue age and accepts only matching sent generation ack`() {
        var now = 100L
        val transfer = VideoDiagnosticsTransfer.Session { now }
        assertFalse(transfer.send { message(it) })
        transfer.connected(10, false); assertFalse(transfer.send { message(it) })
        transfer.connected(11, true)
        repeat(100) { assertTrue(transfer.send { message(it) }) }
        transfer.acknowledge(11, ack(100)) // Enqueued is not received.
        assertEquals(VideoDiagnosticsTransfer.Result.SENDING, transfer.result)
        now = 600
        assertNull(transfer.next(10))
        val payload = JSONObject(transfer.next(11)!!)
        assertEquals(100, payload.getInt("request")); assertEquals(500, payload.getInt("snapshotAgeMs"))
        assertNull(transfer.next(11))
        transfer.acknowledge(10, ack(100)); transfer.acknowledge(11, ack(99)); transfer.acknowledge(11, ack("100"))
        assertEquals(VideoDiagnosticsTransfer.Result.SENDING, transfer.result)
        transfer.acknowledge(11, ack(100))
        assertEquals(VideoDiagnosticsTransfer.Result.RECEIVED, transfer.result)
        transfer.disconnected(11)
        assertEquals(VideoDiagnosticsTransfer.Result.RECEIVED, transfer.result) // Explicitly last transfer, historical.
    }
    @Test fun `timeout cancellation reconnect and late acknowledgement never retry or confirm another snapshot`() {
        var now = 100L
        val transfer = VideoDiagnosticsTransfer.Session { now }
        transfer.connected(1, true); transfer.send { message(it) }
        now += 5000; transfer.expire(1)
        assertEquals(VideoDiagnosticsTransfer.Result.TIMEOUT, transfer.result)
        assertNull(transfer.next(1)); transfer.acknowledge(1, ack())
        assertEquals(VideoDiagnosticsTransfer.Result.TIMEOUT, transfer.result)
        transfer.send { message(it) }; transfer.next(1)
        transfer.connected(2, true)
        assertNull(transfer.next(2)); transfer.send { message(it) }; transfer.next(2)
        transfer.acknowledge(1, ack()); transfer.disconnected(1)
        assertEquals(VideoDiagnosticsTransfer.Result.SENDING, transfer.result)
        transfer.acknowledge(2, ack(status = "rejected"))
        assertEquals(VideoDiagnosticsTransfer.Result.REJECTED, transfer.result)
        transfer.send { message(it) }; transfer.disconnected(2)
        assertEquals(VideoDiagnosticsTransfer.Result.FAILED, transfer.result)
        assertNull(transfer.next(2))
    }
    @Test fun `ack deadline is enforced without a timer callback and exhaustion is finite`() {
        var now = 100L
        val transfer = VideoDiagnosticsTransfer.Session { now }
        transfer.connected(1, true); transfer.send { message(it) }; transfer.next(1)
        now = 5100; transfer.acknowledge(1, ack())
        assertEquals(VideoDiagnosticsTransfer.Result.TIMEOUT, transfer.result)
        org.robolectric.util.ReflectionHelpers.setField(transfer, "request", VideoDiagnosticsWire.MAX_ORDINAL)
        assertFalse(transfer.send { message(it) })
        assertEquals(VideoDiagnosticsTransfer.Result.FAILED, transfer.result)
        transfer.connected(2, true); assertTrue(transfer.send { message(it) })
    }
    @Config(sdk = [26])
    @Test fun `manual export reads installed version on the oldest supported companion API`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val expected = context.packageManager.getPackageInfo(context.packageName, 0).versionCode.coerceAtLeast(1)
        VideoDiagnosticsTransfer.connected(678, true)
        try {
            assertTrue(VideoDiagnosticsTransfer.send(context))
            assertEquals(expected, JSONObject(VideoDiagnosticsTransfer.next(678)!!).getInt("appVersionCode"))
        } finally { VideoDiagnosticsTransfer.disconnected(678) }
    }
    @Test fun `typed export clamps ages ordinals and strips unknown states without exporting labels or errors`() {
        var now = 0L
        val timeline = VideoDiagnostics.Timeline { now }
        repeat(100) { timeline.record(VideoDiagnostics.Event.SHARE_STARTED) }
        timeline.record(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, VideoDiagnostics.Cause.SECURITY)
        now = Long.MAX_VALUE
        val payload = timeline.snapshot(1, "private_state_text", true, 12)
        VideoDiagnosticsWire.validate(payload)
        assertEquals(32, payload.getJSONArray("recent").length())
        assertEquals("other_failure", payload.getString("state"))
        assertFalse(payload.toString().contains("private_state_text"))
        assertFalse(payload.toString().contains("Screen sharing"))
        assertTrue(payload.toString().toByteArray().size < 16384)
        assertEquals(86400000L, payload.getJSONArray("recent").getJSONObject(0).getLong("ageMs"))
    }
}

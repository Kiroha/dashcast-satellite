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
    @Test fun `optional details require both exact capabilities and never leak across authenticated generations`() {
        for (value in listOf(JSONObject.NULL,true,"1",1.0,0,2)) {
            assertFalse(VideoDiagnosticsWire.detailsSupported(JSONObject().put("videoDiagnostics",1).put("videoDiagnosticsDetails",value)))
        }
        assertFalse(VideoDiagnosticsWire.detailsSupported(JSONObject().put("videoDiagnosticsDetails",1)))
        assertTrue(VideoDiagnosticsWire.detailsSupported(JSONObject().put("videoDiagnostics",1).put("videoDiagnosticsDetails",1)))
        val transfer=VideoDiagnosticsTransfer.Session { 0L }
        val detail=fixtures().getJSONArray("accepted").getJSONObject(1).getJSONObject("details")
        transfer.connected(1,true,false)
        assertTrue(transfer.send(details={detail}) { message(it) });assertFalse(JSONObject(transfer.next(1)!!).has("details"))
        transfer.connected(2,true,true)
        assertTrue(transfer.send(details={detail}) { message(it) })
        detail.getJSONArray("attempts").getJSONObject(0).put("framesEncoded",999)
        assertEquals(30,JSONObject(transfer.next(2)!!).getJSONObject("details").getJSONArray("attempts").getJSONObject(0).getInt("framesEncoded"))
        transfer.send(details={detail}) { message(it) };transfer.disconnected(2);transfer.connected(3,true)
        assertNull(transfer.next(3));assertTrue(transfer.send(details={detail}) { message(it) })
        assertFalse(JSONObject(transfer.next(3)!!).has("details"))
    }

    @Test fun `maximum typed history and base snapshot fit the whole UTF8 message budget`() {
        var now=0L;val history=VideoSenderDiagnostics { now };val timeline=VideoDiagnostics.Timeline { now }
        timeline.record(VideoDiagnostics.Event.SHARE_STARTED)
        VideoDiagnostics.Event.entries.forEach { timeline.record(it,sharingSession=1) }
        repeat(4) {
            val attempt=history.begin(1)
            history.sample(attempt,VideoSenderDiagnostics.counterNames.associateWith { 9_000_000_000_000L },"video/H264")
            repeat(4) { history.encoded(attempt, EncodedH264Diagnostics.Observation(0,true,8192,8192,"annex_b","complete",true,true,true,255,255,255)) }
            history.end(attempt)
        }
        now=86_400_000L
        val message=timeline.snapshot(1,"capture_paused",true,14).put("details",history.snapshot(true))
        VideoDiagnosticsWire.validate(message)
        assertTrue(message.toString().toByteArray(Charsets.UTF_8).size<VideoDiagnosticsWire.MAX_BYTES)
        assertEquals(4,message.getJSONObject("details").getJSONArray("attempts").length())
    }

    @Test fun `exact H264 opt in requires both old capabilities and reconnect restores byte compatible schema one`() {
        for(value in listOf(JSONObject.NULL,true,false,"1",1.0,0,2)){
            assertFalse(VideoDiagnosticsWire.h264Supported(JSONObject().put("videoDiagnostics",1).put("videoDiagnosticsDetails",1).put("videoDiagnosticsH264",value)))
        }
        assertFalse(VideoDiagnosticsWire.h264Supported(JSONObject().put("videoDiagnosticsH264",1)))
        assertFalse(VideoDiagnosticsWire.h264Supported(JSONObject().put("videoDiagnostics",1).put("videoDiagnosticsH264",1)))
        assertTrue(VideoDiagnosticsWire.h264Supported(JSONObject().put("videoDiagnostics",1L).put("videoDiagnosticsDetails",1L).put("videoDiagnosticsH264",1L)))
        val transfer=VideoDiagnosticsTransfer.Session{0}
        val old=fixtures().getJSONArray("accepted").getJSONObject(1).getJSONObject("details")
        val current=JSONObject(old.toString()).put("schema",2)
        val rows=current.getJSONArray("attempts");repeat(rows.length()){rows.getJSONObject(it).put("h264",org.json.JSONArray())}
        transfer.connected(1,true,true,true)
        assertTrue(transfer.send(details={old},h264Details={current}){message(it)})
        assertEquals(2,JSONObject(transfer.next(1)!!).getJSONObject("details").getInt("schema"))
        transfer.send(details={old},h264Details={current}){message(it)};transfer.disconnected(1);transfer.connected(2,true,true)
        assertNull(transfer.next(1));assertNull(transfer.next(2))
        assertTrue(transfer.send(details={old},h264Details={error("new details invoked for old receiver")}){message(it)})
        assertEquals(old.toString(),JSONObject(transfer.next(2)!!).getJSONObject("details").toString())
        transfer.connected(3,true,false,true)
        assertTrue(transfer.send(details={error("details not opted in")},h264Details={error("H264 not opted in")}){message(it)})
        assertFalse(JSONObject(transfer.next(3)!!).has("details"))
    }

}

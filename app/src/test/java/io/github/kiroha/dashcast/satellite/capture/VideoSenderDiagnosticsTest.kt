package io.github.kiroha.dashcast.satellite.capture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoSenderDiagnosticsTest {
    @Test fun `four attempts retain original sample ages missing fields and zero without raw native data`() {
        var now=0L
        val history=VideoSenderDiagnostics { now }
        val first=history.begin(1)
        now=1000
        history.sample(first,mapOf("framesEncoded" to 5L,"keyFramesEncoded" to 1L,"pliCount" to 0L,
            "nackCount" to "private SDP","firCount" to -1,"bytesSent" to 1.5,"trackId" to "private"),"video/H264")
        now=2000;history.end(first)
        now=5000
        history.sample(first,mapOf("framesEncoded" to 999L),"video/VP8")
        var row=history.snapshot().getJSONArray("attempts").getJSONObject(0)
        assertEquals(4000,row.getInt("sampleAgeMs"));assertTrue(row.getBoolean("ended"))
        assertEquals(5,row.getInt("framesEncoded"));assertEquals(0,row.getInt("pliCount"))
        assertTrue(row.isNull("nackCount"));assertTrue(row.isNull("firCount"));assertTrue(row.isNull("bytesSent"))
        assertFalse(row.toString().contains("private"))
        repeat(6){history.begin(2)}
        val rows=history.snapshot().getJSONArray("attempts");assertEquals(4,rows.length())
        row=rows.getJSONObject(0);assertTrue(row.isNull("sampleAgeMs"));assertTrue(row.isNull("framesEncoded"))
        assertFalse(row.getBoolean("ended"));assertTrue(rows.getJSONObject(1).getBoolean("ended"))
    }
    @Test fun `stale callback from replaced peer and malformed keyframe relationship cannot contaminate new attempt`() {
        val history=VideoSenderDiagnostics { 100L };val old=history.begin(1);val current=history.begin(2)
        history.sample(old,mapOf("framesEncoded" to 900L),"video/H264")
        history.sample(current,mapOf("framesEncoded" to 2L,"keyFramesEncoded" to 3L,"firCount" to true),"private")
        val row=history.snapshot().getJSONArray("attempts").getJSONObject(0)
        assertEquals(2,row.getInt("framesEncoded"));assertTrue(row.isNull("keyFramesEncoded"));assertTrue(row.isNull("codec"))
        assertTrue(row.isNull("firCount"))
    }
    @Test fun `encoded observation ages are independent of stats age frozen manually and reject stale row updates`() {
        var now=100L;val history=VideoSenderDiagnostics{now};val old=history.begin(1)
        fun observation(at:Long)=EncodedH264Diagnostics.Observation(at,true,960,540,"annex_b","complete",true,true,true,66,224,31)
        now=200;history.encoded(old,observation(now));now=1000
        val frozen=history.snapshot(true);var row=frozen.getJSONArray("attempts").getJSONObject(0)
        assertTrue(row.isNull("sampleAgeMs"));assertEquals(800,row.getJSONArray("h264").getJSONObject(0).getInt("ageMs"))
        history.sample(old,mapOf("framesEncoded" to 5),"video/H264");history.end(old)
        history.encoded(old,observation(now));now=1200;val current=history.begin(2)
        history.encoded(old,observation(now));history.encoded(current,observation(now))
        row=history.snapshot(true).getJSONArray("attempts").getJSONObject(1)
        assertEquals(200,row.getInt("sampleAgeMs"));assertEquals(1000,row.getJSONArray("h264").getJSONObject(0).getInt("ageMs"))
        assertEquals(1,row.getJSONArray("h264").length());assertEquals(800,frozen.getJSONArray("attempts").getJSONObject(0).getJSONArray("h264").getJSONObject(0).getInt("ageMs"))
        val legacy=history.snapshot();assertEquals(1,legacy.getInt("schema"));assertFalse(legacy.getJSONArray("attempts").getJSONObject(0).has("h264"))
    }


    @Test fun `reference attempt mode is immutable and never mislabeled in legacy details`() {
        val history = VideoSenderDiagnostics { 100 }
        history.begin(1); history.begin(2, true)
        assertEquals(1, history.snapshot().getJSONArray("attempts").length())
        assertEquals(1, history.snapshot(true).getJSONArray("attempts").length())
        val rows = history.snapshot(true, true).getJSONArray("attempts")
        assertEquals("reference", rows.getJSONObject(0).getString("sourceMode"))
        assertEquals("capture", rows.getJSONObject(1).getString("sourceMode"))
        assertTrue(rows.getJSONObject(1).getBoolean("ended"))
    }

}

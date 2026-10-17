package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*
import java.nio.ByteBuffer

class EncodedH264DiagnosticsTest {
    private fun bytes(vararg values: Int) = ByteBuffer.allocateDirect(values.size).apply { values.forEach { put(it.toByte()) }; flip() }
    private fun image(buffer: ByteBuffer, key: Boolean = true) = EncodedImage.builder().setBuffer(buffer) {}
        .setEncodedWidth(960).setEncodedHeight(540).setFrameType(if (key) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta)
        .setCaptureTimeNs(1).createEncodedImage()
    private fun annex() = bytes(0,0,0,1,0x67,66,224,31,0x80,0,0,1,0x68,0x80,0,0,1,0x65,0x80)
    @Test fun `Annex B direct read-only window records headers without changing position limit mark content or refcount`() {
        val writable = ByteBuffer.allocateDirect(64).apply { put(99); put(annex()); put(88); flip(); position(1); limit(limit()-1); mark() }
        val buffer = writable.asReadOnlyBuffer(); buffer.mark()
        val before = ByteArray(writable.capacity()) { writable.get(it.coerceAtMost(writable.limit()-1)) }
        val row = EncodedH264Diagnostics.inspectBuffer(buffer, 1000, 123, true, 960, 540)
        assertEquals("annex_b",row.framing); assertEquals("complete",row.inspection)
        assertEquals(true,row.sps); assertEquals(true,row.pps); assertEquals(true,row.idr)
        assertEquals(66,row.profileIdc); assertEquals(224,row.constraintFlags); assertEquals(31,row.levelIdc)
        assertEquals(1,buffer.position()); assertEquals(writable.limit(),buffer.limit()); buffer.reset(); assertEquals(1,buffer.position())
        assertArrayEquals(before,ByteArray(writable.capacity()) { writable.get(it.coerceAtMost(writable.limit()-1)) })
    }
    @Test fun `unknown AVCC and truncated malformed empty or forbidden headers never prove absence or valid SPS metadata`() {
        val avcc=EncodedH264Diagnostics.inspectBuffer(bytes(0,0,0,5,0x67,66,224,31,0x80),100,0,true,null,null)
        assertEquals("unknown",avcc.framing); assertNull(avcc.sps);assertNull(avcc.pps);assertNull(avcc.profileIdc)
        for (buffer in listOf(bytes(0,0,1),bytes(0,0,1,0x67,66),bytes(0,0,1,0xe7,66,224,31,0x80),bytes(0,0,1,0x65),bytes(0,0,1,0,0x80))) {
            val row=EncodedH264Diagnostics.inspectBuffer(buffer,100,0,true,null,null)
            assertEquals("truncated",row.inspection);assertNull(row.sps);assertNull(row.pps);assertNull(row.idr);assertNull(row.profileIdc)
        }
        val row=EncodedH264Diagnostics.inspectBuffer(annex(),9,0,true,null,null)
        assertEquals(true,row.sps);assertNull(row.pps);assertNull(row.idr);assertEquals("truncated",row.inspection)
        val empty=EncodedH264Diagnostics.inspectBuffer(bytes(),100,0,false,null,null);assertNull(empty.sps)
    }
    @Test fun `complete recognized window can record absent headers and first observed SPS only`() {
        val row=EncodedH264Diagnostics.inspectBuffer(bytes(0,0,1,0x61,0x80),100,0,false,960,540)
        assertEquals(false,row.sps);assertEquals(false,row.pps);assertEquals(false,row.idr)
        val row2=EncodedH264Diagnostics.inspectBuffer(bytes(0,0,1,0x67,66,224,31,0x80,0,0,1,0x67,100,0,40,0x80),100,0,true,960,540)
        assertEquals(66,row2.profileIdc);assertEquals(31,row2.levelIdc)
    }
    @Test fun `first output and three later keys only with immutable old owner and no renewed budget on replacement`() {
        var now=0L;var eligible=true;val rows=mutableListOf<EncodedH264Diagnostics.Observation>()
        val probe=EncodedH264Diagnostics({eligible},rows::add){now};val owner=Any();assertTrue(probe.bind(owner))
        probe.inspect(owner,image(annex(),false));repeat(10){probe.inspect(owner,image(annex(),false))}
        repeat(5){now++;probe.inspect(owner,image(annex()))};assertEquals(4,rows.size);assertFalse(rows[0].declaredKey)
        assertFalse(probe.bind(Any()));probe.inspect(owner,image(annex()));assertEquals(4,rows.size)
        val other=EncodedH264Diagnostics({eligible},rows::add){now};val otherOwner=Any();assertTrue(other.bind(otherOwner))
        eligible=false;other.inspect(otherOwner,image(annex()));eligible=true;other.inspect(otherOwner,image(annex()));assertEquals(4,rows.size)
    }
    @Test fun `total callback byte and time budgets stop permanently without timers or frame retention`() {
        var now=0L
        fun bounded(run:(EncodedH264Diagnostics,Any,MutableList<EncodedH264Diagnostics.Observation>)->Unit){
            val rows=mutableListOf<EncodedH264Diagnostics.Observation>();val probe=EncodedH264Diagnostics({true},rows::add){now};val owner=Any();probe.bind(owner);run(probe,owner,rows)
        }
        bounded { probe,owner,rows -> probe.inspect(owner,image(annex(),false));repeat(179){probe.inspect(owner,image(annex(),false))};probe.inspect(owner,image(annex()));assertEquals(1,rows.size) }
        bounded { probe,owner,rows -> probe.inspect(owner,image(annex()));now=12_000;probe.inspect(owner,image(annex()));now=0;probe.inspect(owner,image(annex()));assertEquals(1,rows.size) }
        bounded { probe,owner,rows ->
            val huge=ByteBuffer.allocateDirect(200*1024).apply { put(annex()); position(0) }
            repeat(5){probe.inspect(owner,image(huge))};assertEquals(3,rows.size);assertTrue(rows.all { it.inspection=="truncated" });assertEquals(0,huge.position())
        }
    }
    @Test fun `retire close or in-progress invalidation cannot publish to a replacement attempt`() {
        val rows=mutableListOf<EncodedH264Diagnostics.Observation>();val owner=Any();var reads=0
        val probe=EncodedH264Diagnostics({++reads < 3},rows::add){0};probe.bind(owner);probe.inspect(owner,image(annex()));assertTrue(rows.isEmpty())
        val other=EncodedH264Diagnostics({true},rows::add){0};other.bind(owner);other.retire(owner);other.inspect(owner,image(annex()));assertFalse(other.bind(Any()));assertTrue(rows.isEmpty())
        val closed=EncodedH264Diagnostics({true},rows::add){0};closed.bind(owner);closed.close();closed.inspect(owner,image(annex()));assertTrue(rows.isEmpty())
    }
    @Test fun `leading and trailing zero padding is bounded while adjacent empty NAL remains truncated`() {
        val padded=bytes(0,0,0,0,0,1,0x67,66,224,31,0x80,0,0,1,0x65,0x80,0,0,0)
        val row=EncodedH264Diagnostics.inspectBuffer(padded,100,0,true,null,null)
        assertEquals("annex_b",row.framing);assertEquals("complete",row.inspection);assertEquals(true,row.sps);assertEquals(true,row.idr)
        val empty=EncodedH264Diagnostics.inspectBuffer(bytes(0,0,1,0,0,1,0x61,0x80),100,0,true,null,null)
        assertEquals("truncated",empty.inspection);assertNull(empty.sps);assertNull(empty.idr)
    }

    @Test fun `retirement during final source predicate fences an already parsed observation`() {
        val rows=mutableListOf<EncodedH264Diagnostics.Observation>();val owner=Any();var checks=0
        lateinit var probe:EncodedH264Diagnostics
        probe=EncodedH264Diagnostics({ if(++checks==5) probe.retire(owner);true },rows::add){0}
        probe.bind(owner);probe.inspect(owner,image(annex()));assertTrue(rows.isEmpty())
    }

}

package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test

class StartupKeyframePolicyTest {
    @Test fun `three extra opportunities start only at first connection and never refresh their deadline`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}) {now};val encoder=Any();assertTrue(ticket.bind(encoder))
        now=20_000_000_000L;assertFalse(ticket.request(encoder,false,true));ticket.connected()
        val emitted=mutableListOf<Long>()
        for (millis in 0L..13000L step 100) {now=20_000_000_000L+millis*1_000_000;ticket.connected();if(ticket.request(encoder,false,true))emitted.add(millis)}
        assertEquals(listOf(2000L,5000L,9000L),emitted)
    }
    @Test fun `native KEY consumes a due slot and frame gaps cannot create catchup bursts`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}){now};val encoder=Any();ticket.bind(encoder);ticket.connected()
        now=2_000_000_000;assertFalse(ticket.request(encoder,true,true))
        now=8_000_000_000;assertTrue(ticket.request(encoder,false,true))
        now=9_000_000_000;assertFalse(ticket.request(encoder,false,true)) // Spacing consumes rather than delays the slot.
        now=11_000_000_000;assertFalse(ticket.request(encoder,false,true))
    }
    @Test fun `late first frame cutoff empty layers and regressing clock never force a frame`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}){now};val encoder=Any();ticket.bind(encoder);ticket.connected()
        now=2_000_000_000;assertFalse(ticket.request(encoder,false,false))
        now=12_000_000_000;assertFalse(ticket.request(encoder,false,true))
        now=2_100_000_000;assertFalse(ticket.request(encoder,false,true))
        val second=StartupKeyframePolicy({true}){now};val owner=Any();second.bind(owner);second.connected()
        now=2_000_000_000;assertFalse(second.request(owner,false,true))
    }
    @Test fun `retired encoder replacement and closed peer cannot reset budget or adopt another ticket`() {
        var now=0L;var active=true;val first=StartupKeyframePolicy({active}){now};val encoder=Any();first.bind(encoder);first.connected()
        first.retire(encoder);assertFalse(first.bind(Any()));now=2_000_000_000;assertFalse(first.request(encoder,false,true))
        val second=StartupKeyframePolicy({active}){now};val current=Any();second.bind(current);second.connected()
        first.close();now=4_000_000_000;assertTrue(second.request(current,false,true));assertFalse(first.request(encoder,false,true))
        active=false;now=7_000_000_000;assertFalse(second.request(current,false,true))
    }
    @Test fun `source and viewer false true before worker drain invalidate old peer authority`() {
        val callbacks=ArrayDeque<() ->Unit>();val source=CaptureAvailability{callbacks.add{}}
        source.texture(100);val epoch=source.readyEpoch
        var viewerEpoch=0L;var ready=true;val original=viewerEpoch;var now=0L
        val ticket=StartupKeyframePolicy({source.owns(epoch)&&ready&&viewerEpoch==original}){now}
        val encoder=Any();ticket.bind(encoder);ticket.connected()
        source.visibility(false);source.visibility(true);source.texture(101)
        assertTrue(source.ready);now=2_000_000_000;assertFalse(ticket.request(encoder,false,true))
        ready=false;viewerEpoch++;ready=true;assertFalse(ticket.ownsSource());assertTrue(callbacks.isNotEmpty())
    }
    @Test fun `native key just before scheduled slot prevents a duplicate sync burst`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}){now};val owner=Any();ticket.bind(owner);ticket.connected()
        now=1_900_000_000L;assertFalse(ticket.request(owner,true,true))
        now=2_000_000_000L;assertFalse(ticket.request(owner,false,true))
        now=5_000_000_000L;assertTrue(ticket.request(owner,false,true))
    }

}

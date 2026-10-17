package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoRecoveryEligibilityTest {
    private fun link(generation:Long)=VideoLink(generation,VideoLan(ShadowNetwork.newInstance(301),"wlan0",setOf("192.168.49.2"),true,"192.168.49.1")){_,_->}
    @Test fun `actual ready closed ready burst fences old ticket before a queued worker pause can run`() {
        var now=0L;var ending=false;val owner=link(2001);VideoControlBridge.connected(owner)
        try {
            VideoControlBridge.receive(owner,JSONObject().put("type","video.ready"))
            val epoch=owner.viewerReadyEpoch
            VideoControlBridge.receive(owner,JSONObject().put("type","video.ready"))
            assertEquals(epoch,owner.viewerReadyEpoch)
            val ticket=StartupKeyframePolicy({!ending&&owner.ownsViewer(epoch)&&VideoControlBridge.link===owner}){now}
            val encoder=Any();ticket.bind(encoder);ticket.connected()
            VideoControlBridge.receive(owner,JSONObject().put("type","video.closed"))
            VideoControlBridge.receive(owner,JSONObject().put("type","video.ready"))
            assertTrue(owner.viewerReady);assertFalse(owner.ownsViewer(epoch))
            val watchdog=EncodedFrameWatchdog(0)
            var terminated=false
            if(owner.ownsViewer(epoch))watchdog.failure(60_000_000_000L)?.let{terminated=true}
            assertFalse(terminated) // The same actual viewer predicate gates the sender's expired watchdog.
            now=2_000_000_000L;assertFalse(ticket.request(encoder,false,true))
            ending=true;assertFalse(ticket.ownsSource())
        } finally {VideoControlBridge.disconnected(owner.generation)}
    }
    @Test fun `terminal active guard and WSS replacement invalidate lease without a worker callback`() {
        var now=0L;var active=true;val owner=link(2002);owner.viewerReady=true;VideoControlBridge.connected(owner)
        try {
            val epoch=owner.viewerReadyEpoch
            val ticket=StartupKeyframePolicy({active&&owner.ownsViewer(epoch)&&VideoControlBridge.link===owner}){now}
            val encoder=Any();ticket.bind(encoder);ticket.connected();active=false
            now=2_000_000_000L;assertFalse(ticket.request(encoder,false,true))
            active=true;VideoControlBridge.connected(link(2003));assertFalse(ticket.ownsSource())
        } finally {VideoControlBridge.link?.let{VideoControlBridge.disconnected(it.generation)}}
    }
}

package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

class CaptureObserverRelayTest {
    private class Observer : CapturerObserver {
        val events=mutableListOf<String>();var frame:VideoFrame?=null;var stopThrows=false
        override fun onCapturerStarted(success:Boolean){events.add("started:$success")}
        override fun onCapturerStopped(){events.add("stopped");if(stopThrows)error("stop")}
        override fun onFrameCaptured(frame:VideoFrame){events.add("frame");this.frame=frame}
    }
    private val frame=VideoFrame(object:VideoFrame.Buffer {
        override fun getWidth()=16;override fun getHeight()=16
        override fun toI420():VideoFrame.I420Buffer=error("No pixel conversion")
        override fun cropAndScale(x:Int,y:Int,w:Int,h:Int,sw:Int,sh:Int):VideoFrame.Buffer=error("No scaling")
        override fun retain()=error("Must not retain");override fun release()=error("Must not release")
    },0,0)
    @Test fun `one running capture replays size and started to each sequential source without retaining frames`() {
        val relay=CaptureObserverRelay();relay.resized(960,540);relay.onCapturerStarted(true)
        val old=Observer();val sizes=mutableListOf<Pair<Int,Int>>()
        relay.bind(old,{w,h->sizes.add(w to h)},{true});relay.onFrameCaptured(frame)
        assertSame(frame,old.frame);assertEquals(listOf(960 to 540),sizes)
        relay.unbind();relay.onFrameCaptured(frame);assertEquals(listOf("started:true","frame","stopped"),old.events)
        val current=Observer();relay.bind(current,{w,h->sizes.add(w to h)},{true});relay.onFrameCaptured(frame)
        assertSame(frame,current.frame);assertEquals(listOf(960 to 540,960 to 540),sizes)
    }
    @Test fun `source epoch fences old forwarding before worker unbind and stopped delegate failure cannot remain attached`() {
        val source=CaptureAvailability{};source.texture(100);val epoch=source.readyEpoch
        val relay=CaptureObserverRelay();relay.onCapturerStarted(true);val old=Observer()
        relay.bind(old,{_,_->},{source.owns(epoch)});source.visibility(false);source.visibility(true);source.texture(101)
        relay.onFrameCaptured(frame);assertNull(old.frame)
        old.stopThrows=true;try{relay.unbind();fail()}catch(_:IllegalStateException){}
        val current=Observer();relay.bind(current,{_,_->},{true});relay.onFrameCaptured(frame)
        assertSame(frame,current.frame);assertNull(old.frame)
    }
}

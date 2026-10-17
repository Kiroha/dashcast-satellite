package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

class StartupKeyframeEncoderTest {
    private class Buffer : VideoFrame.Buffer {
        var retained=0
        override fun getWidth()=16
        override fun getHeight()=16
        override fun toI420(): VideoFrame.I420Buffer = error("No pixel conversion")
        override fun cropAndScale(x:Int,y:Int,w:Int,h:Int,sw:Int,sh:Int):VideoFrame.Buffer=error("No scaling")
        override fun retain(){retained++}
        override fun release(){retained--}
    }
    private class Encoder : VideoEncoder {
        var info:VideoEncoder.EncodeInfo?=null;var frame:VideoFrame?=null
        var settings:VideoEncoder.Settings?=null;var callback:VideoEncoder.Callback?=null
        var allocation:VideoEncoder.BitrateAllocation?=null;var rates:VideoEncoder.RateControlParameters?=null
        var nativeEnvironment=0L;var releases=0
        val scaling=VideoEncoder.ScalingSettings.OFF
        val limits=arrayOf(VideoEncoder.ResolutionBitrateLimits(256,100,50,200))
        val metadata=VideoEncoder.EncoderInfo(16,true)
        override fun initEncode(s:VideoEncoder.Settings,c:VideoEncoder.Callback):VideoCodecStatus {settings=s;callback=c;return VideoCodecStatus.OK}
        override fun encode(f:VideoFrame,i:VideoEncoder.EncodeInfo):VideoCodecStatus {frame=f;info=i;return VideoCodecStatus.NO_OUTPUT}
        override fun release():VideoCodecStatus {releases++;return VideoCodecStatus.OK}
        override fun createNative(env:Long):Long {nativeEnvironment=env;return 123}
        override fun isHardwareEncoder()=false // Distinct from the SDK default: must be delegated exactly.
        override fun setRateAllocation(a:VideoEncoder.BitrateAllocation,f:Int):VideoCodecStatus {allocation=a;return VideoCodecStatus.ERR_PARAMETER}
        override fun setRates(p:VideoEncoder.RateControlParameters):VideoCodecStatus {rates=p;return VideoCodecStatus.NO_OUTPUT}
        override fun getScalingSettings()=scaling
        override fun getResolutionBitrateLimits()=limits
        override fun getImplementationName()="delegate"
        override fun getEncoderInfo()=metadata
    }
    private val settings=VideoEncoder.Settings(1,16,16,100,15,1,false)
    private val callback=VideoEncoder.Callback { _,_-> }
    private fun delta()=VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))

    @Test fun `all public encoder entry points preserve delegate arguments identities metadata and return statuses`() {
        val delegate=Encoder();val ticket=StartupKeyframePolicy({true}){0L};val encoder=StartupKeyframeEncoder(delegate,ticket)
        assertEquals(VideoCodecStatus.OK,encoder.initEncode(settings,callback));assertSame(settings,delegate.settings);assertSame(callback,delegate.callback)
        assertEquals(123L,encoder.createNative(99));assertEquals(99L,delegate.nativeEnvironment);assertFalse(encoder.isHardwareEncoder())
        val allocation=VideoEncoder.BitrateAllocation(arrayOf(intArrayOf(100)))
        val rates=VideoEncoder.RateControlParameters(allocation,14.5)
        assertEquals(VideoCodecStatus.ERR_PARAMETER,encoder.setRateAllocation(allocation,15));assertSame(allocation,delegate.allocation)
        assertEquals(VideoCodecStatus.NO_OUTPUT,encoder.setRates(rates));assertSame(rates,delegate.rates)
        assertSame(delegate.scaling,encoder.scalingSettings);assertSame(delegate.limits,encoder.resolutionBitrateLimits)
        assertSame(delegate.metadata,encoder.encoderInfo);assertEquals("delegate",encoder.implementationName)
        assertEquals(VideoCodecStatus.OK,encoder.release());assertEquals(1,delegate.releases)
    }
    @Test fun `H264 recovery changes only the due encode hint with no frame copy and preserves native KEY EMPTY and drop requests`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}){now};val delegate=Encoder();val encoder=StartupKeyframeEncoder(delegate,ticket)
        encoder.initEncode(settings,callback);ticket.connected()
        val buffer=Buffer();val frame=VideoFrame(buffer,0,0);val original=delta()
        encoder.encode(frame,original);assertSame(original,delegate.info)
        now=2_000_000_000L
        for(types in listOf(emptyArray(),arrayOf(EncodedImage.FrameType.EmptyFrame))){val empty=VideoEncoder.EncodeInfo(types);encoder.encode(frame,empty);assertSame(empty,delegate.info)}
        assertEquals(VideoCodecStatus.NO_OUTPUT,encoder.encode(frame,original));assertSame(frame,delegate.frame)
        assertEquals(EncodedImage.FrameType.VideoFrameKey,delegate.info!!.frameTypes[0])
        assertEquals(EncodedImage.FrameType.VideoFrameDelta,original.frameTypes[0]);assertEquals(0,buffer.retained)
        now=5_000_000_000L;val native=VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey));encoder.encode(frame,native);assertSame(native,delegate.info)
        now=5_100_000_000L;encoder.encode(frame,original);assertSame(original,delegate.info)
    }
    @Test fun `encoder release replacement and reinitialization cannot acquire a fresh startup budget`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}){now};val delegate=Encoder();val original=StartupKeyframeEncoder(delegate,ticket)
        original.initEncode(settings,callback);ticket.connected();original.release()
        val replacementDelegate=Encoder();val replacement=StartupKeyframeEncoder(replacementDelegate,ticket);replacement.initEncode(settings,callback)
        now=5_000_000_000L;val hint=delta();val frame=VideoFrame(Buffer(),0,0)
        original.initEncode(settings,callback);original.encode(frame,hint);replacement.encode(frame,hint)
        assertSame(hint,delegate.info);assertSame(hint,replacementDelegate.info)
    }
    @Test fun `factory delegates codec metadata selectors and other codecs and a delayed old creation keeps its old ticket`() {
        var now=0L
        val support=arrayOf(VideoCodecInfo("H264",emptyMap(),emptyList()))
        val implementations=arrayOf(VideoCodecInfo("VP8",emptyMap(),emptyList()))
        val selector=object:VideoEncoderFactory.VideoEncoderSelector {
            override fun onCurrentEncoder(codec:VideoCodecInfo){}
            override fun onAvailableBitrate(bitrate:Int):VideoCodecInfo?=null
            override fun onEncoderBroken():VideoCodecInfo?=null
        }
        val encoders=mutableListOf<Encoder>();var requested:VideoCodecInfo?=null
        val delegate=object:VideoEncoderFactory {
            override fun createEncoder(info:VideoCodecInfo):VideoEncoder {requested=info;return Encoder().also{encoders.add(it)}}
            override fun getSupportedCodecs()=support
            override fun getImplementations()=implementations
            override fun getEncoderSelector()=selector
        }
        val oldTicket=StartupKeyframePolicy({true}){now};val oldFactory=StartupKeyframeEncoderFactory(delegate,oldTicket)
        assertSame(support,oldFactory.supportedCodecs);assertSame(implementations,oldFactory.implementations);assertSame(selector,oldFactory.encoderSelector)
        val vp8=implementations[0];assertSame(oldFactory.createEncoder(vp8),encoders.last());assertSame(vp8,requested)
        val old=oldFactory.createEncoder(support[0])!!;old.initEncode(settings,callback);oldTicket.connected()
        oldTicket.close();assertNull(oldFactory.createEncoder(support[0]));assertEquals(2,encoders.size)
        val currentTicket=StartupKeyframePolicy({true}){now};val currentFactory=StartupKeyframeEncoderFactory(delegate,currentTicket)
        val current=currentFactory.createEncoder(support[0])!!;current.initEncode(settings,callback);currentTicket.connected()
        now=2_000_000_000L;val hint=delta();val frame=VideoFrame(Buffer(),0,0)
        old.encode(frame,hint);assertSame(hint,encoders[1].info)
        current.encode(frame,hint);assertEquals(EncodedImage.FrameType.VideoFrameKey,encoders[2].info!!.frameTypes[0])
    }
    @Test fun `mixed native KEY EMPTY hint keeps identity and consumes cooldown rather than forcing next delta`() {
        var now=0L;val ticket=StartupKeyframePolicy({true}){now};val delegate=Encoder();val encoder=StartupKeyframeEncoder(delegate,ticket)
        encoder.initEncode(settings,callback);ticket.connected();val frame=VideoFrame(Buffer(),0,0)
        now=2_000_000_000L
        val mixed=VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey,EncodedImage.FrameType.EmptyFrame))
        encoder.encode(frame,mixed);assertSame(mixed,delegate.info)
        now=2_100_000_000L;val hint=delta();encoder.encode(frame,hint);assertSame(hint,delegate.info)
    }
    @Test fun `ownership invalidation during native allocation releases new encoder and refuses return`() {
        var eligible=true;val ticket=StartupKeyframePolicy({eligible}){0L};val delegate=Encoder()
        val factory=StartupKeyframeEncoderFactory(object:VideoEncoderFactory {
            override fun getSupportedCodecs()=emptyArray<VideoCodecInfo>()
            override fun createEncoder(info:VideoCodecInfo):VideoEncoder {eligible=false;return delegate}
        },ticket)
        assertNull(factory.createEncoder(VideoCodecInfo("H264",emptyMap(),emptyList())))
        assertEquals(1,delegate.releases)
    }

}

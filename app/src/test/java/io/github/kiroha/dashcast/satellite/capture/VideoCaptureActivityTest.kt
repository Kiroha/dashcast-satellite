package io.github.kiroha.dashcast.satellite.capture

import android.app.Activity
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import io.github.kiroha.dashcast.satellite.R
import io.github.kiroha.dashcast.satellite.SatelliteState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoCaptureActivityTest {
    @After fun reset() {
        VideoControlBridge.link?.let { VideoControlBridge.disconnected(it.generation) }
        VideoState.status = "stopped"; VideoState.captureBusy = false
    }
    @Test fun `consent cancellation leaves control and guidance state intact`() {
        val original = SatelliteState.transport
        val activity = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup().get()
        val owner = link(101)
        owner.viewerReady = true; VideoControlBridge.connected(owner)
        result(activity, Activity.RESULT_CANCELED, null)
        assertSame(original, SatelliteState.transport)
        assertEquals("stopped", VideoState.status)
        assertTrue(owner.viewerReady)
        activity.finish()
    }
    @Test fun `consent returned after WSS generation replacement cannot start capture`() {
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "pendingGeneration", 101L)
        VideoControlBridge.connected(link(102).apply { viewerReady = true })
        result(activity, Activity.RESULT_OK, Intent())
        assertNull(shadowOf(activity).nextStartedService)
        assertEquals("stopped", VideoState.status)
        controller.pause().stop().destroy()
    }
    @Test fun `obsolete socket disconnect does not close a replacement video link`() {
        val old = link(101); val current = link(102)
        VideoControlBridge.connected(old); VideoControlBridge.connected(current)
        VideoControlBridge.disconnected(old.generation)
        assertSame(current, VideoControlBridge.link)
        VideoControlBridge.receive(old, org.json.JSONObject().put("type", "video.ready"))
        assertFalse(current.viewerReady)
    }
    private fun result(activity: VideoCaptureActivity, code: Int, intent: Intent?) {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "onActivityResult",
            ClassParameter.from(Int::class.javaPrimitiveType, 10),
            ClassParameter.from(Int::class.javaPrimitiveType, code), ClassParameter.from(Intent::class.java, intent))
    }
    private fun link(generation: Long) = VideoLink(generation, VideoLan(ShadowNetwork.newInstance(301),
        "wlan0", setOf("192.168.49.2"), true, "192.168.49.1")) { _, done -> done(true) }
}

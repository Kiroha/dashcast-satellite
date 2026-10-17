package io.github.kiroha.dashcast.satellite.capture

import android.app.Activity
import android.content.Intent
import android.content.ClipboardManager
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.time.Duration
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
        VideoState.status = "stopped"; VideoState.captureBusy = false; VideoState.reference = false
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
    @Test fun `authenticated connection enables Start without any visible viewer`() {
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get()
        VideoControlBridge.connected(link(101))
        ReflectionHelpers.setField(activity, "chosen", activity.packageName)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "render")
        assertTrue(activity.findViewById<Button>(R.id.video_start).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.video_stop).isEnabled)
        controller.pause().stop().destroy()
    }
    @Test fun `consent while viewer absent starts service and opens selected app from waiting state`() {
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get()
        VideoControlBridge.connected(link(101))
        ReflectionHelpers.setField(activity, "pendingGeneration", 101L)
        ReflectionHelpers.setField(activity, "chosen", activity.packageName)
        result(activity, Activity.RESULT_OK, Intent())
        assertEquals(VideoCaptureService::class.java.name, shadowOf(activity).nextStartedService.component?.className)
        VideoState.status = "waiting_viewer"; VideoState.captureBusy = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(activity.findViewById<Button>(R.id.video_stop).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.video_start).isEnabled)
        assertTrue(activity.findViewById<TextView>(R.id.video_status).text.toString().contains("waiting for DashCast"))
        assertNotNull("Waiting capture must launch the chosen app before any video.ready", shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }
    @Test fun `manual transfer has its own capability gate and retains acknowledgement after reopening`() {
        VideoDiagnosticsTransfer.connected(909, false)
        val first = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        assertFalse(first.get().findViewById<Button>(R.id.video_send_diagnostics).isEnabled)
        assertTrue(first.get().findViewById<TextView>(R.id.video_diagnostics_transfer).text.contains("cannot receive"))
        VideoDiagnosticsTransfer.connected(909, true)
        ReflectionHelpers.callInstanceMethod<Void>(first.get(), "render")
        assertTrue(first.get().findViewById<Button>(R.id.video_send_diagnostics).isEnabled)
        assertNull(VideoControlBridge.link)
        first.get().findViewById<Button>(R.id.video_send_diagnostics).performClick()
        assertFalse(first.get().findViewById<Button>(R.id.video_send_diagnostics).isEnabled)
        assertTrue(first.get().findViewById<TextView>(R.id.video_diagnostics_transfer).text.contains("Sending"))
        val snapshot = org.json.JSONObject(VideoDiagnosticsTransfer.next(909)!!)
        VideoDiagnosticsTransfer.acknowledge(909, org.json.JSONObject().put("type", "diagnostics.video.ack")
            .put("schema", 1).put("request", snapshot.get("request")).put("status", "accepted"))
        first.pause().stop().destroy()
        VideoDiagnosticsTransfer.disconnected(909)
        val second = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val text = second.get().findViewById<TextView>(R.id.video_diagnostics_transfer).text.toString()
        assertTrue(text.contains("Last transfer")); assertTrue(text.contains("received by DashCast"))
        assertTrue(text.contains("Connect DashSat"))
        assertFalse(second.get().findViewById<Button>(R.id.video_send_diagnostics).isEnabled)
        second.pause().stop().destroy()
    }
    @Test fun `historical encoding remains readable and copyable after reopening without claiming current image`() {
        VideoDiagnostics.record(VideoDiagnostics.Event.FRAMES_ENCODED)
        VideoDiagnostics.record(VideoDiagnostics.Event.VIEWER_CLOSED)
        VideoState.status = "waiting_viewer"; VideoState.captureBusy = true
        val first = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        first.pause().stop().destroy()
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.findViewById<TextView>(R.id.video_status).text.toString().contains("waiting for DashCast"))
        assertTrue(activity.findViewById<TextView>(R.id.video_history).text.toString().contains("image not confirmed"))
        activity.findViewById<Button>(R.id.video_copy_diagnostics).performClick()
        val text = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text.contains("frames_encoded="))
        assertTrue(text.contains("Earlier encoding does not confirm the current receiver image"))
        controller.pause().stop().destroy()
    }
    @Test fun `hidden source is a resumable active capture with Stop and Open enabled`() {
        VideoControlBridge.connected(link(101))
        VideoState.status = "capture_paused"; VideoState.captureBusy = true
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "chosen", activity.packageName)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "render")
        assertTrue(activity.findViewById<Button>(R.id.video_stop).isEnabled)
        assertTrue(activity.findViewById<Button>(R.id.video_open_app).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.video_start).isEnabled)
        val text = activity.findViewById<TextView>(R.id.video_status).text.toString()
        assertTrue(text.contains("open the shared app"))
        assertFalse(text.contains("new consent"))
        VideoDiagnosticsWire.validate(VideoDiagnostics.snapshot(1, 13))
        assertEquals("capture_paused", VideoDiagnostics.snapshot(1, 13).getString("state"))
        controller.pause().stop().destroy()
    }

    @Test fun `incompatible H264 status survives cleanup and a new Start requires consent while control remains connected`() {
        val owner = link(707); VideoControlBridge.connected(owner)
        val guidance = SatelliteState.transport
        VideoState.status = "h264_profile_unsupported"; VideoState.captureBusy = true
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "chosen", activity.packageName)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "render")
        val text = activity.findViewById<TextView>(R.id.video_status).text.toString()
        assertTrue(text.contains("compatible H.264 video")); assertTrue(text.contains("Send diagnostics to DashCast"))
        assertFalse(VideoState.active); assertFalse(activity.findViewById<Button>(R.id.video_start).isEnabled)
        VideoState.captureBusy = false
        ReflectionHelpers.callInstanceMethod<Void>(activity, "render")
        assertEquals(text, activity.findViewById<TextView>(R.id.video_status).text.toString())
        assertTrue(activity.findViewById<Button>(R.id.video_start).isEnabled)
        activity.findViewById<Button>(R.id.video_start).performClick()
        assertTrue(ReflectionHelpers.getField(activity, "consentPending"))
        assertNull(shadowOf(activity).nextStartedService)
        assertSame(owner, VideoControlBridge.link); assertSame(guidance, SatelliteState.transport)
        val snapshot = VideoDiagnostics.snapshot(1, 17)
        VideoDiagnosticsWire.validate(snapshot); assertEquals("other_failure", snapshot.getString("state"))
        controller.pause().stop().destroy()
    }

    private fun result(activity: VideoCaptureActivity, code: Int, intent: Intent?) {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "onActivityResult",
            ClassParameter.from(Int::class.javaPrimitiveType, 10),
            ClassParameter.from(Int::class.javaPrimitiveType, code), ClassParameter.from(Intent::class.java, intent))
    }
    private fun link(generation: Long) = VideoLink(generation, VideoLan(ShadowNetwork.newInstance(301),
        "wlan0", setOf("192.168.49.2"), true, "192.168.49.1")) { _, done -> done(true) }

    @Test fun `reference button is capability gated and cannot overlap normal sharing`() {
        val controller = Robolectric.buildActivity(VideoCaptureActivity::class.java).setup()
        val activity = controller.get(); ReflectionHelpers.setField(activity,"chosen",activity.packageName)
        VideoControlBridge.connected(link(101)); ReflectionHelpers.callInstanceMethod<Void>(activity,"render")
        assertFalse(activity.findViewById<Button>(R.id.video_reference).isEnabled)
        val normal = link(102)
        VideoControlBridge.connected(VideoLink(normal.generation,normal.lan,referenceSupported=true) { _,complete -> complete(true) })
        ReflectionHelpers.callInstanceMethod<Void>(activity,"render")
        assertTrue(activity.findViewById<Button>(R.id.video_reference).isEnabled)
        activity.findViewById<Button>(R.id.video_reference).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(org.robolectric.Shadows.shadowOf(dialog).message.toString().contains("No captured app images"))
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.findViewById<Button>(R.id.video_start).isEnabled)
        assertTrue(activity.findViewById<TextView>(R.id.video_status).text.contains("TEST PATTERN"))
        result(activity,Activity.RESULT_OK,Intent())
        val service = shadowOf(activity).nextStartedService
        assertTrue(service.getBooleanExtra(VideoCaptureService.REFERENCE,false))
        assertTrue(service.hasExtra(VideoCaptureService.CONSENT_AT))
        VideoState.status="reference_complete"; VideoState.captureBusy=false
        ReflectionHelpers.callInstanceMethod<Void>(activity,"render")
        assertTrue(activity.findViewById<Button>(R.id.video_start).isEnabled)
        assertTrue(activity.findViewById<TextView>(R.id.video_status).text.contains("new Android consent"))
        controller.pause().stop().destroy()
    }

}

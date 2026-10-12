package io.github.kiroha.dashcast.satellite.capture

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** The production Service is exercised; only platform grant and native codec engine are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [VideoCaptureServiceTest.ProjectionShadow::class])
class VideoCaptureServiceTest {
    private lateinit var controller: ServiceController<VideoCaptureService>
    private lateinit var service: VideoCaptureService
    private lateinit var engine: FakeEngine
    private lateinit var projection: MediaProjection
    private lateinit var grant: ProjectionShadow
    private lateinit var owner: VideoLink
    private var grants = 0
    @Before fun setup() {
        VideoState.status = "stopped"; VideoState.captureBusy = false
        controller = Robolectric.buildService(VideoCaptureService::class.java).create()
        service = controller.get()
        shadowOf(ReflectionHelpers.getField<Handler>(service, "worker").looper).pause()
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        projection = Shadow.newInstanceOf(MediaProjection::class.java)
        grant = Shadow.extract(projection)
        engine = FakeEngine()
        service.createProjection = { grants++; projection }
        service.createEngine = { _, _, _, _ -> engine }
        owner = link(501); VideoControlBridge.connected(owner)
    }
    @After fun cleanup() {
        destroyAndJoin()
        VideoControlBridge.link?.let { VideoControlBridge.disconnected(it.generation) }
        VideoState.status = "stopped"; VideoState.captureBusy = false
    }
    @Test fun `consent without viewer waits with Stop and resumes same projection across viewer closure`() {
        start(); flushWorker()
        assertEquals(1, grants); assertEquals(1, grant.callbacks.size)
        assertEquals(1, engine.starts); assertEquals("waiting_viewer", VideoState.status)
        assertTrue(VideoState.captureBusy); assertTrue(VideoState.active)
        val notification = shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(2)
        assertEquals("Screen sharing active · waiting for DashCast", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(1, notification.actions.size)
        signal("video.ready"); signal("video.ready")
        assertEquals(1, engine.resumes)
        signal("video.closed")
        assertEquals("waiting_viewer", VideoState.status); assertEquals(0, engine.closes)
        signal("video.ready")
        assertEquals(2, engine.resumes); assertEquals(1, engine.starts); assertEquals(1, grants)
        service.onStartCommand(Intent().setAction(VideoCaptureService.STOP), 0, 2)
        assertEquals("stopped", VideoState.status)
    }
    @Test fun `revoked initial waiting grant cannot restart on later ready`() {
        start(); flushWorker()
        grant.revoke(); flushWorker()
        assertEquals("capture_revoked", VideoState.status)
        signal("video.ready")
        assertEquals(0, engine.resumes); assertEquals(1, grants)
    }
    @Test fun `closed ready burst before worker drain pauses old peer and resumes fresh peer`() {
        start(); flushWorker(); signal("video.ready")
        VideoControlBridge.receive(owner, JSONObject().put("type", "video.closed"))
        VideoControlBridge.receive(owner, JSONObject().put("type", "video.ready"))
        flushWorker()
        assertEquals(2, engine.resumes); assertEquals(1, engine.starts)
    }
    @Test fun `new control generation terminates waiting capture and later ready cannot revive it`() {
        start(); flushWorker()
        val replacement = link(502)
        VideoControlBridge.connected(replacement); flushWorker()
        assertEquals("control_unavailable", VideoState.status)
        VideoControlBridge.receive(replacement, JSONObject().put("type", "video.ready")); flushWorker()
        assertEquals(0, engine.resumes); assertEquals(1, grants)
    }
    @Test fun `locked device cannot resume even before screen off broadcast`() {
        start(); flushWorker()
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        signal("video.ready")
        assertEquals("capture_locked", VideoState.status); assertEquals(0, engine.resumes)
    }
    @Test fun `screen off ends waiting and cleanup releases grant and engine`() {
        start(); flushWorker()
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle(); flushWorker()
        assertEquals("capture_locked", VideoState.status)
        destroyAndJoin()
        assertEquals(1, engine.closes); assertTrue(grant.stops >= 1)
        assertFalse(VideoState.captureBusy)
        // Avoid calling destroy twice in cleanup.
        controller = Robolectric.buildService(VideoCaptureService::class.java).create()
        service = controller.get()
    }
    @Test fun `Stop before queued initialization never acquires a grant`() {
        start()
        service.onStartCommand(Intent().setAction(VideoCaptureService.STOP), 0, 2)
        flushWorker()
        assertEquals(0, grants); assertEquals(0, engine.starts)
    }
    @Test fun `obsolete consent generation never acquires a grant`() {
        service.onStartCommand(Intent().setAction(VideoCaptureService.START)
            .putExtra(VideoCaptureService.CONSENT, Intent()).putExtra(VideoCaptureService.GENERATION, 500L), 0, 1)
        flushWorker()
        assertEquals(0, grants); assertEquals("control_unavailable", VideoState.status)
    }
    @Test fun `waiting does not request native stats and viewer closure stops polling`() {
        start(); flushWorker()
        val looper = shadowOf(ReflectionHelpers.getField<Handler>(service, "worker").looper)
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals(0, engine.stats)
        signal("video.ready"); looper.idleFor(Duration.ofMillis(2500))
        assertTrue(engine.stats > 0)
        signal("video.closed")
        val count = engine.stats
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals(count, engine.stats)
    }
    @Test fun `Stop during native initialization cannot resume or overwrite stopped state`() {
        engine.starting = { service.onStartCommand(Intent().setAction(VideoCaptureService.STOP), 0, 2) }
        owner.viewerReady = true
        start(); flushWorker()
        assertEquals("stopped", VideoState.status); assertEquals(0, engine.resumes)
    }
    private fun start() {
        service.onStartCommand(Intent().setAction(VideoCaptureService.START)
            .putExtra(VideoCaptureService.CONSENT, Intent()).putExtra(VideoCaptureService.GENERATION, owner.generation), 0, 1)
    }
    private fun signal(type: String) { VideoControlBridge.receive(owner, JSONObject().put("type", type)); flushWorker() }
    private fun flushWorker() {
        val worker = ReflectionHelpers.getField<Handler>(service, "worker")
        shadowOf(worker.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun destroyAndJoin() {
        val worker = ReflectionHelpers.getField<Handler>(service, "worker")
        shadowOf(worker.looper).unPause()
        controller.destroy()
        val thread = ReflectionHelpers.getField<HandlerThread>(service, "thread")
        thread.join(2000)
        assertFalse("Capture cleanup must finish", thread.isAlive)
    }
    private fun link(generation: Long) = VideoLink(generation, VideoLan(ShadowNetwork.newInstance(301),
        "wlan0", setOf("192.168.49.2"), true, "192.168.49.1")) { _, complete -> complete(true) }
    private class FakeEngine : VideoCaptureEngine {
        var starts = 0; var resumes = 0; var closes = 0; var stats = 0
        var starting: () -> Unit = {}
        private var sending = false
        override fun start(projection: MediaProjection, width: Int, height: Int, density: Int) { starts++; starting() }
        override fun viewerChanged(ready: Boolean) {
            if (ready && !sending) resumes++
            sending = ready
            VideoState.status = if (ready) "negotiating" else "waiting_viewer"
        }
        override fun signal(message: JSONObject) = Unit
        override fun collectStats() { stats++ }
        override fun close() { closes++ }
    }
    @Implements(MediaProjection::class)
    class ProjectionShadow {
        val callbacks = linkedMapOf<MediaProjection.Callback, Handler>()
        var stops = 0
        @Implementation fun registerCallback(callback: MediaProjection.Callback, handler: Handler) { callbacks[callback] = handler }
        @Implementation fun unregisterCallback(callback: MediaProjection.Callback) { callbacks.remove(callback) }
        @Implementation fun stop() { stops++ }
        fun revoke() { callbacks.toMap().forEach { (callback, handler) -> handler.post { callback.onStop() } } }
    }
}

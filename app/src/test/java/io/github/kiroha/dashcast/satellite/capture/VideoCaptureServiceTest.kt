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
        VideoState.status = "stopped"; VideoState.captureBusy = false; VideoState.reference = false
        controller = Robolectric.buildService(VideoCaptureService::class.java).create()
        service = controller.get()
        shadowOf(ReflectionHelpers.getField<Handler>(service, "worker").looper).pause()
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        projection = Shadow.newInstanceOf(MediaProjection::class.java)
        grant = Shadow.extract(projection)
        engine = FakeEngine()
        service.createProjection = { grants++; projection }
        service.createEngine = { _, _, post, _, changed -> engine.apply { postAction = post; stateChanged = changed } }
        owner = link(501); VideoControlBridge.connected(owner)
    }
    @After fun cleanup() {
        destroyAndJoin()
        VideoControlBridge.link?.let { VideoControlBridge.disconnected(it.generation) }
        VideoState.status = "stopped"; VideoState.captureBusy = false; VideoState.reference = false
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
    @Test fun `capture setup failure retains safe category and never the platform exception message`() {
        service.createProjection = { throw SecurityException("private screen title token SDP") }
        start(); flushWorker()
        assertEquals("capture_unavailable", VideoState.status)
        assertTrue(VideoDiagnostics.summary().contains("Capture setup failed (security)"))
        assertFalse(VideoDiagnostics.summary().contains("private screen title"))
        assertEquals(0, engine.starts)
    }
    @Test fun `temporary hidden capture keeps grant Stop notification and resumes stats without new consent`() {
        start(); flushWorker(); signal("video.ready")
        val looper = shadowOf(ReflectionHelpers.getField<Handler>(service, "worker").looper)
        looper.idleFor(Duration.ofMillis(1500))
        engine.sourceChanged(false); flushWorker()
        assertEquals("capture_paused", VideoState.status)
        assertTrue(VideoState.active); assertTrue(VideoState.captureBusy)
        assertEquals(0, engine.closes); assertEquals(0, grant.stops)
        val notification = shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(2)
        assertEquals("Screen sharing paused · open the shared app", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(1, notification.actions.size)
        val count = engine.stats
        looper.idleFor(Duration.ofSeconds(60))
        assertEquals(count, engine.stats)
        engine.sourceChanged(true); flushWorker()
        looper.idleFor(Duration.ofMillis(1500))
        assertEquals(2, engine.resumes); assertEquals(1, engine.starts); assertEquals(1, grants)
        assertTrue(engine.stats > count)
    }
    @Test fun `source resumes without a viewer only waits and never polls or acquires a second grant`() {
        start(); flushWorker(); signal("video.ready")
        engine.sourceChanged(false); flushWorker()
        signal("video.closed")
        engine.sourceChanged(true); flushWorker()
        assertEquals("waiting_viewer", VideoState.status)
        assertEquals(1, engine.resumes); assertEquals(1, grants)
        val count = engine.stats
        shadowOf(ReflectionHelpers.getField<Handler>(service, "worker").looper).idleFor(Duration.ofSeconds(60))
        assertEquals(count, engine.stats)
        signal("video.ready")
        assertEquals(2, engine.resumes); assertEquals(1, grants)
    }
    @Test fun `revocation while source paused stays terminal despite a late source resume`() {
        start(); flushWorker(); signal("video.ready")
        engine.sourceChanged(false); flushWorker()
        grant.revoke(); flushWorker()
        engine.sourceChanged(true); flushWorker()
        assertEquals("capture_revoked", VideoState.status)
        assertEquals(1, engine.resumes); assertEquals(1, grants)
    }

    @Test fun `failed source resume callback cannot escape the worker and strand the capture lease`() {
        start(); flushWorker(); signal("video.ready")
        engine.sourceChanged(false); flushWorker()
        engine.resuming = { throw IllegalStateException("private codec SDP route") }
        engine.sourceChanged(true); flushWorker()
        assertEquals("capture_unavailable", VideoState.status)
        assertFalse(VideoDiagnostics.summary().contains("private codec SDP route"))
        destroyAndJoin()
        assertEquals(1, engine.closes); assertTrue(grant.stops >= 1)
        controller = Robolectric.buildService(VideoCaptureService::class.java).create()
        service = controller.get()
    }
    @Test fun `native linkage failure during asynchronous source resume terminates safely`() {
        start(); flushWorker(); signal("video.ready")
        engine.sourceChanged(false); flushWorker()
        engine.resuming = { throw UnsatisfiedLinkError("private native diagnostic") }
        engine.sourceChanged(true); flushWorker()
        assertEquals("native_unavailable", VideoState.status)
        assertFalse(VideoDiagnostics.summary().contains("private native diagnostic"))
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
    private fun link(generation: Long, reference: Boolean = false) = VideoLink(generation, VideoLan(ShadowNetwork.newInstance(301),
        "wlan0", setOf("192.168.49.2"), true, "192.168.49.1"), referenceSupported = reference) { _, complete -> complete(true) }
    private class FakeEngine : VideoCaptureEngine {
        var starts = 0; var resumes = 0; var closes = 0; var stats = 0
        var starting: () -> Unit = {}
        private var sending = false
        var sourceReady = true
        var resuming: () -> Unit = {}
        var viewerReady = false
        var postAction: (() -> Unit) -> Unit = { it() }
        var stateChanged: () -> Unit = {}
        override val needsStats: Boolean get() = sending
        fun sourceChanged(value: Boolean) { postAction {
            sourceReady = value
            if (!value) { sending = false; VideoState.status = "capture_paused" }
            else { resuming(); viewerChanged(viewerReady) }
            stateChanged()
        } }
        override fun start(projection: MediaProjection, width: Int, height: Int, density: Int) { starts++; starting() }
        override fun viewerChanged(ready: Boolean) {
            viewerReady = ready
            val send = ready && sourceReady
            if (send && !sending) resumes++
            sending = send
            VideoState.status = if (!sourceReady) "capture_paused" else if (ready) "negotiating" else "waiting_viewer"
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

    @Test fun `reference expires without a viewer or callback and releases its grant`() {
        owner = link(502,true); VideoControlBridge.connected(owner)
        service.onStartCommand(Intent().setAction(VideoCaptureService.START)
            .putExtra(VideoCaptureService.CONSENT,Intent()).putExtra(VideoCaptureService.GENERATION,owner.generation)
            .putExtra(VideoCaptureService.REFERENCE,true).putExtra(VideoCaptureService.CONSENT_AT,android.os.SystemClock.elapsedRealtime()),0,1)
        flushWorker(); assertEquals(1,grants)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals("reference_complete",VideoState.status)
        signal("video.ready"); assertEquals(0,engine.resumes)
        assertReferenceDeadlineCleanup()
    }
    @Test fun `reference media deadline ignores repeated readiness and expires without encoded frames`() {
        owner = link(502,true); VideoControlBridge.connected(owner)
        service.onStartCommand(Intent().setAction(VideoCaptureService.START)
            .putExtra(VideoCaptureService.CONSENT,Intent()).putExtra(VideoCaptureService.GENERATION,owner.generation)
            .putExtra(VideoCaptureService.REFERENCE,true).putExtra(VideoCaptureService.CONSENT_AT,android.os.SystemClock.elapsedRealtime()),0,1)
        flushWorker()
        val reference = ReflectionHelpers.getField<ReferenceSession>(service,"reference")
        signal("video.ready"); assertEquals(1,engine.resumes)
        reference.mediaStarted(); ReflectionHelpers.callInstanceMethod<Void>(service,"scheduleReferenceDeadline")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        signal("video.closed"); signal("video.ready"); assertEquals(2,engine.resumes)
        reference.mediaStarted(); ReflectionHelpers.callInstanceMethod<Void>(service,"scheduleReferenceDeadline")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("reference_complete",VideoState.status)
        assertReferenceDeadlineCleanup()
    }
    @Test fun `reference request on legacy receiver refuses before acquiring projection`() {
        service.onStartCommand(Intent().setAction(VideoCaptureService.START)
            .putExtra(VideoCaptureService.CONSENT,Intent()).putExtra(VideoCaptureService.GENERATION,owner.generation)
            .putExtra(VideoCaptureService.REFERENCE,true).putExtra(VideoCaptureService.CONSENT_AT,android.os.SystemClock.elapsedRealtime()),0,1)
        flushWorker(); assertEquals(0,grants); assertEquals("control_unavailable",VideoState.status)
    }


    private fun assertReferenceDeadlineCleanup() {
        // stopSelf is asynchronous on Android: prove the deadline requested framework teardown
        // before simulating its onDestroy, rather than relying on unconditional @After cleanup.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("The deadline must request stopSelf", shadowOf(service).isStoppedBySelf)
        assertEquals("reference_complete", VideoState.status)
        assertEquals(0, engine.closes); assertEquals(0, grant.stops)
        assertTrue(VideoState.captureBusy)
        val resumes = engine.resumes
        destroyAndJoin()
        assertEquals(1, engine.closes); assertEquals(1, grant.stops)
        assertTrue(grant.callbacks.isEmpty())
        assertFalse(VideoState.captureBusy); assertFalse(VideoState.active)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).notificationShouldRemoved)
        // Detached control events and a late redelivery of the old consent cannot restart this lease.
        VideoControlBridge.receive(owner, JSONObject().put("type", "video.ready"))
        service.onStartCommand(Intent().setAction(VideoCaptureService.START)
            .putExtra(VideoCaptureService.CONSENT, Intent()).putExtra(VideoCaptureService.GENERATION, owner.generation)
            .putExtra(VideoCaptureService.REFERENCE, true).putExtra(VideoCaptureService.CONSENT_AT, 0L), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, grants); assertEquals(1, engine.starts); assertEquals(resumes, engine.resumes)
        assertEquals(1, engine.closes); assertEquals(1, grant.stops)
        assertEquals("reference_complete", VideoState.status)
        assertFalse(VideoState.captureBusy)
        // Match the existing explicit-destruction fixtures: @After only cleans this fresh instance.
        controller = Robolectric.buildService(VideoCaptureService::class.java).create()
        service = controller.get()
    }
}

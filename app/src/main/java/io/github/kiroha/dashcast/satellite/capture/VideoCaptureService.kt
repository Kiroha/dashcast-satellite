package io.github.kiroha.dashcast.satellite.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.github.kiroha.dashcast.satellite.R
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** No START_STICKY, persisted consent, boot start or capture permission reuse. Guidance is separate. */
class VideoCaptureService : Service(), VideoControlBridge.Listener {
    private val thread = HandlerThread("dashsat-video-control")
    private lateinit var worker: Handler
    private val main = Handler(Looper.getMainLooper())
    private var sender: VideoCaptureEngine? = null
    private var projection: MediaProjection? = null
    private var subscription: Closeable? = null
    private var owner: VideoLink? = null
    @Volatile private var ending = false
    private var started = false
    private var reference: ReferenceSession? = null
    private val referenceDeadline = Runnable { finishCapture("reference_complete") }
    private fun scheduleReferenceDeadline() {
        if (ending) return
        val remaining = reference?.remaining() ?: return
        main.removeCallbacks(referenceDeadline)
        if (remaining <= 0) finishCapture("reference_complete") else main.postDelayed(referenceDeadline, remaining)
    }
    private var screenReceiverRegistered = false
    // Instance-local seams exercise the real service lifecycle without loading native codecs in JVM tests.
    internal var createProjection: (Intent) -> MediaProjection = { consent ->
        getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
            ?: error("consent_required")
    }
    internal var createEngine: (Context, VideoLink, (() -> Unit) -> Unit, (String) -> Unit, () -> Unit) -> VideoCaptureEngine =
        { context, link, post, failed, changed -> NativeVideoSender(context, link, post, failed,
            active = { !ending && canCapture() }, stateChanged = changed, recoveryActive = { !ending && (reference?.remaining()?.let { it > 0 } != false) },
            reference = reference, referenceStarted = ::scheduleReferenceDeadline) }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { finishCapture("capture_revoked") }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) finishCapture("capture_locked")
        }
    }
    private var notificationText = 0
    private val queued = AtomicInteger()
    private val stats = object : Runnable {
        override fun run() {
            if (ending || owner?.viewerReady != true || sender?.needsStats != true) return
            safely { sender?.collectStats(); updateNotification() }
            if (!ending && owner?.viewerReady == true && sender?.needsStats == true)
                worker.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        thread.start(); worker = Handler(thread.looper)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishCapture("stopped"); return START_NOT_STICKY }
        if (started || ending || intent?.action != START) { if (!started) finishCapture("consent_required"); return START_NOT_STICKY }
        if (VideoState.captureBusy) { stopSelf(); return START_NOT_STICKY }
        started = true
        VideoState.captureBusy = true
        VideoDiagnostics.record(VideoDiagnostics.Event.SHARE_STARTED)
        try {
            showForeground()
            @Suppress("DEPRECATION")
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(CONSENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(CONSENT)
            val link = VideoControlBridge.link
            if (consent == null || link == null ||
                intent.getLongExtra(GENERATION, -1) != link.generation) {
                finishCapture("control_unavailable"); return START_NOT_STICKY
            }
            owner = link
            VideoState.reference = intent.getBooleanExtra(REFERENCE, false)
            if (VideoState.reference) {
                if (!link.referenceSupported) { finishCapture("control_unavailable"); return START_NOT_STICKY }
                val acceptedAt = intent.getLongExtra(CONSENT_AT, -1)
                val now = android.os.SystemClock.elapsedRealtime()
                if (acceptedAt !in maxOf(0, now - 60_000)..now) { finishCapture("reference_complete"); return START_NOT_STICKY }
                reference = ReferenceSession(acceptedAt)
                scheduleReferenceDeadline()
            }
            VideoState.status = "starting"; VideoState.metrics = ""
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenReceiverRegistered = true
            // Subscribe before initialization, then reconcile current readiness: ready/closed can
            // change while Android consent or native setup is in progress.
            subscription = VideoControlBridge.subscribe(this)
            worker.post {
                if (ending) return@post
                try {
                    if (!canCapture()) { finishCapture("capture_locked"); return@post }
                    if (VideoControlBridge.link !== link) { finishCapture("control_unavailable"); return@post }
                    val value = createProjection(consent)
                    projection = value
                    VideoDiagnostics.record(VideoDiagnostics.Event.PROJECTION_CREATED)
                    // Observe revocation even before a viewer exists and before the first VD.
                    value.registerCallback(projectionCallback, worker)
                    if (ending) return@post
                    val metrics = resources.displayMetrics
                    sender = createEngine(applicationContext, link, ::post, { finishCapture(it) }) {
                        if (!ending) { updateNotification(); scheduleStats() }
                    }
                    sender!!.start(value, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                    if (ending) return@post
                    sender!!.viewerChanged(link.viewerReady)
                    updateNotification()
                    scheduleStats()
                } catch (error: LinkageError) { VideoDiagnostics.exception(VideoDiagnostics.Event.NATIVE_UNAVAILABLE, error); finishCapture("native_unavailable") }
                catch (error: Exception) { VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, error); finishCapture("capture_unavailable") }
            }
        } catch (error: Exception) { VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, error); finishCapture("capture_unavailable") }
        return START_NOT_STICKY
    }
    private fun showForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = notification(R.string.video_notification_preparing)
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, notification)
    }
    private fun notification(text: Int): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, VideoCaptureActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, VideoCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_satellite)
            .setContentTitle(getString(R.string.video_title)).setContentText(getString(text))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.video_stop), stop).build()).build()
    }
    private fun updateNotification() {
        if (ending) return
        val text = if (reference != null) R.string.video_reference_notification else when (VideoState.status) {
            "waiting_viewer" -> R.string.video_notification_waiting
            "capture_paused" -> R.string.video_notification_source_paused
            "streaming" -> R.string.video_notification
            else -> R.string.video_notification_preparing
        }
        if (text != notificationText) {
            notificationText = text
            getSystemService(NotificationManager::class.java).notify(2, notification(text))
        }
    }
    private fun canCapture(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked
    private fun safely(action: () -> Unit) {
        try { action() }
        catch (error: LinkageError) { VideoDiagnostics.exception(VideoDiagnostics.Event.NATIVE_UNAVAILABLE, error); finishCapture("native_unavailable") }
        catch (error: Exception) { VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, error); finishCapture("capture_unavailable") }
    }
    private fun post(action: () -> Unit) {
        if (!ending) worker.post { if (!ending) safely(action) }
    }
    private fun scheduleStats() {
        worker.removeCallbacks(stats)
        if (!ending && owner?.viewerReady == true && sender?.needsStats == true) worker.postDelayed(stats, 1000)
    }
    override fun linkChanged(link: VideoLink?) { post { if (owner !== link) finishCapture("control_unavailable") } }
    override fun signal(link: VideoLink, message: org.json.JSONObject) {
        if (owner !== link || ending) return
        if (queued.incrementAndGet() > 32) { queued.decrementAndGet(); finishCapture("signalling_overflow"); return }
        worker.post {
            try { if (!ending && owner === link) {
                try {
                    if (message.optString("type") in setOf("video.ready", "video.closed")) {
                        if (!canCapture()) finishCapture("capture_locked")
                        else sender?.viewerChanged(message.optString("type") == "video.ready" && link.viewerReady)
                        updateNotification()
                        scheduleStats()
                    } else sender?.signal(message)
                } catch (error: LinkageError) { VideoDiagnostics.exception(VideoDiagnostics.Event.NATIVE_UNAVAILABLE, error); finishCapture("native_unavailable") }
                catch (error: Exception) { VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, error); finishCapture("capture_unavailable") }
            } } finally { queued.decrementAndGet() }
        }
    }
    @Synchronized private fun finishCapture(code: String) {
        if (ending) return
        ending = true
        VideoDiagnostics.terminal(code)
        VideoState.status = code; VideoState.metrics = ""
        main.post { stopSelf() }
    }
    override fun onDestroy() {
        if (!ending && started) VideoDiagnostics.record(VideoDiagnostics.Event.STOPPED)
        ending = true
        subscription?.close(); subscription = null
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
        worker.removeCallbacks(stats)
        worker.post {
            try {
                runCatching { sender?.close() }
                runCatching { projection?.unregisterCallback(projectionCallback) }
                runCatching { projection?.stop() }
            } finally {
                sender = null
                projection = null
                owner = null
                if (started) VideoState.captureBusy = false
                thread.quitSafely()
            }
        }
        if (VideoState.active) VideoState.status = "stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "io.github.kiroha.dashcast.satellite.VIDEO_START"
        const val STOP = "io.github.kiroha.dashcast.satellite.VIDEO_STOP"
        const val CONSENT = "capture_consent"
        const val REFERENCE = "capture_reference"
        const val CONSENT_AT = "capture_consent_at"
        const val GENERATION = "capture_link_generation"
        private const val CHANNEL = "satellite_video"
    }
}

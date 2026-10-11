package io.github.kiroha.dashcast.satellite.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import io.github.kiroha.dashcast.satellite.R
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** No START_STICKY, persisted consent, boot start or capture permission reuse. Guidance is separate. */
class VideoCaptureService : Service(), VideoControlBridge.Listener {
    private val thread = HandlerThread("dashsat-video-control")
    private lateinit var worker: Handler
    private val main = Handler(Looper.getMainLooper())
    private var sender: NativeVideoSender? = null
    private var projection: MediaProjection? = null
    private var subscription: Closeable? = null
    private var owner: VideoLink? = null
    @Volatile private var ending = false
    private var started = false
    private val queued = AtomicInteger()
    private val stats = object : Runnable {
        override fun run() {
            if (ending) return
            sender?.collectStats()
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
        try {
            showForeground()
            @Suppress("DEPRECATION")
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(CONSENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(CONSENT)
            val link = VideoControlBridge.link
            if (consent == null || link == null || !link.viewerReady ||
                intent.getLongExtra(GENERATION, -1) != link.generation) {
                finishCapture("viewer_required"); return START_NOT_STICKY
            }
            owner = link
            VideoState.status = "starting"; VideoState.metrics = ""
            worker.post {
                if (ending) return@post
                try {
                    val value = getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(Activity.RESULT_OK, consent) ?: error("consent_required")
                    projection = value
                    val metrics = resources.displayMetrics
                    sender = NativeVideoSender(applicationContext, link, ::post) { finishCapture(it) }
                    sender!!.start(value, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                    subscription = VideoControlBridge.subscribe(this)
                    worker.post(stats)
                } catch (_: LinkageError) { finishCapture("native_unavailable") }
                catch (_: Exception) { finishCapture("capture_unavailable") }
            }
        } catch (_: Exception) { finishCapture("capture_unavailable") }
        return START_NOT_STICKY
    }
    private fun showForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 10, Intent(this, VideoCaptureActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, VideoCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_satellite)
            .setContentTitle(getString(R.string.video_title)).setContentText(getString(R.string.video_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.video_stop), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, notification)
    }
    private fun post(action: () -> Unit) {
        if (!ending) worker.post { if (!ending) action() }
    }
    override fun linkChanged(link: VideoLink?) { post { if (owner !== link) finishCapture("control_unavailable") } }
    override fun signal(link: VideoLink, message: org.json.JSONObject) {
        if (owner !== link || ending) return
        if (queued.incrementAndGet() > 32) { queued.decrementAndGet(); finishCapture("signalling_overflow"); return }
        worker.post {
            try { if (!ending && owner === link) {
                if (message.optString("type") == "video.closed") finishCapture("viewer_closed")
                else sender?.signal(message)
            } } finally { queued.decrementAndGet() }
        }
    }
    @Synchronized private fun finishCapture(code: String) {
        if (ending) return
        ending = true
        VideoState.status = code; VideoState.metrics = ""
        main.post { stopSelf() }
    }
    override fun onDestroy() {
        ending = true
        subscription?.close(); subscription = null
        worker.removeCallbacks(stats)
        worker.post {
            try { if (sender != null) sender?.close() else runCatching { projection?.stop() } } finally {
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
        const val GENERATION = "capture_link_generation"
        private const val CHANNEL = "satellite_video"
    }
}

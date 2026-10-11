package io.github.kiroha.dashcast.satellite.capture

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import io.github.kiroha.dashcast.satellite.R

/** The app choice launches on this box only; Android remains the authority for captured content. */
class VideoCaptureActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var chosen: String? = null
    private var pendingGeneration = -1L
    private var consentPending = false
    private var launchDeadline = 0L
    private val refresh = object : Runnable {
        override fun run() {
            render()
            if (launchDeadline != 0L) {
                if (VideoState.status in setOf("negotiating", "connected_waiting_frames", "streaming")) {
                    launchDeadline = 0L; openChosen()
                } else if (android.os.SystemClock.elapsedRealtime() >= launchDeadline || !(VideoState.active || VideoState.captureBusy)) {
                    launchDeadline = 0L
                }
            }
            main.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_video_capture)
        findViewById<View>(R.id.video_root).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        chosen = savedInstanceState?.getString("chosen")
        pendingGeneration = savedInstanceState?.getLong("generation", -1) ?: -1
        consentPending = savedInstanceState?.getBoolean("pending") ?: false
        findViewById<Button>(R.id.video_choose).setOnClickListener { chooseApplication() }
        findViewById<Button>(R.id.video_open_app).setOnClickListener { openChosen() }
        findViewById<Button>(R.id.video_start).setOnClickListener {
            val link = VideoControlBridge.link
            if (link?.viewerReady != true || chosen == null || (VideoState.active || VideoState.captureBusy)) return@setOnClickListener
            try {
                pendingGeneration = link.generation; consentPending = true
                @Suppress("DEPRECATION")
                startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), CAPTURE)
            } catch (_: Exception) { consentPending = false; toast(R.string.video_unavailable) }
            render()
        }
        findViewById<Button>(R.id.video_stop).setOnClickListener {
            stopService(Intent(this, VideoCaptureService::class.java))
            launchDeadline = 0L
        }
        findViewById<TextView>(R.id.video_help).setText(if (Build.VERSION.SDK_INT >= 34)
            R.string.video_help_app else R.string.video_help_display)
    }
    @Suppress("DEPRECATION")
    private fun chooseApplication() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) { toast(R.string.video_no_apps); return }
        AlertDialog.Builder(this).setTitle(R.string.video_choose)
            .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, position ->
                chosen = apps[position].activityInfo.packageName
                render()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun openChosen() {
        val app = chosen ?: return
        val launch = packageManager.getLaunchIntentForPackage(app)
        if (launch == null) { toast(R.string.video_app_unavailable); return }
        runCatching { startActivity(launch) }.onFailure { toast(R.string.video_app_unavailable) }
    }
    @Deprecated("Framework Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE) return
        consentPending = false
        if (resultCode != RESULT_OK || data == null) { render(); return }
        val link = VideoControlBridge.link
        if (link == null || !link.viewerReady || link.generation != pendingGeneration || (VideoState.active || VideoState.captureBusy)) {
            toast(R.string.video_viewer_required); render(); return
        }
        try {
            VideoState.status = "starting"
            startForegroundService(Intent(this, VideoCaptureService::class.java).setAction(VideoCaptureService.START)
                .putExtra(VideoCaptureService.CONSENT, data).putExtra(VideoCaptureService.GENERATION, link.generation))
            // Open only once the foreground service, native encoder and capture actually exist.
            launchDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
        } catch (_: Exception) { VideoState.status = "capture_unavailable"; toast(R.string.video_unavailable) }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("chosen", chosen)
        outState.putLong("generation", pendingGeneration)
        outState.putBoolean("pending", consentPending)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    private fun render() {
        val label = chosen?.let { name -> runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString()
        }.getOrNull() }
        findViewById<TextView>(R.id.video_application).text = getString(R.string.video_selected, label ?: getString(R.string.video_none))
        val link = VideoControlBridge.link
        findViewById<TextView>(R.id.video_status).setText(when {
            consentPending -> R.string.video_consent_pending
            VideoState.status == "streaming" -> R.string.video_streaming
            VideoState.status in setOf("starting", "negotiating", "connected_waiting_frames") -> R.string.video_connecting
            VideoState.status == "viewer_closed" -> R.string.video_viewer_closed
            VideoState.status == "control_unavailable" -> R.string.video_control_lost
            VideoState.status == "receiver_address_unsupported" -> R.string.video_address_unsupported
            VideoState.status != "stopped" -> R.string.video_restart_required
            link == null -> R.string.video_connection_required
            !link.viewerReady -> R.string.video_viewer_required
            else -> R.string.video_ready
        })
        findViewById<TextView>(R.id.video_metrics).text = VideoState.metrics
        findViewById<Button>(R.id.video_choose).isEnabled = !(VideoState.active || VideoState.captureBusy) && !consentPending
        findViewById<Button>(R.id.video_open_app).isEnabled = chosen != null && !consentPending
        findViewById<Button>(R.id.video_start).isEnabled = chosen != null && link?.viewerReady == true && !(VideoState.active || VideoState.captureBusy) && !consentPending
        findViewById<Button>(R.id.video_stop).isEnabled = (VideoState.active || VideoState.captureBusy)
    }
    private fun toast(resource: Int) { Toast.makeText(this, resource, Toast.LENGTH_LONG).show() }
    companion object { private const val CAPTURE = 10 }
}

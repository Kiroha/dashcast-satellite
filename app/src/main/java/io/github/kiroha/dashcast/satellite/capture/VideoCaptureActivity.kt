package io.github.kiroha.dashcast.satellite.capture

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
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
                if (VideoState.status in setOf("waiting_viewer", "capture_paused", "negotiating", "connected_waiting_frames", "streaming")) {
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
            if (link == null || chosen == null || (VideoState.active || VideoState.captureBusy)) return@setOnClickListener
            try {
                pendingGeneration = link.generation; consentPending = true
                VideoDiagnostics.record(VideoDiagnostics.Event.CONSENT_REQUESTED)
                @Suppress("DEPRECATION")
                startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), CAPTURE)
            } catch (error: Exception) {
                VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, error)
                consentPending = false; toast(R.string.video_unavailable)
            }
            render()
        }
        findViewById<Button>(R.id.video_stop).setOnClickListener {
            stopService(Intent(this, VideoCaptureService::class.java))
            launchDeadline = 0L
        }
        findViewById<Button>(R.id.video_copy_diagnostics).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.video_history_title), VideoDiagnostics.summary()))
            toast(R.string.video_diagnostics_copied)
        }
        findViewById<Button>(R.id.video_send_diagnostics).setOnClickListener {
            VideoDiagnosticsTransfer.send(this)
            render()
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
        if (resultCode != RESULT_OK || data == null) {
            VideoDiagnostics.record(VideoDiagnostics.Event.CONSENT_CANCELLED)
            render(); return
        }
        val link = VideoControlBridge.link
        if (link == null || link.generation != pendingGeneration || (VideoState.active || VideoState.captureBusy)) {
            toast(R.string.video_connection_required); render(); return
        }
        try {
            VideoState.status = "starting"
            startForegroundService(Intent(this, VideoCaptureService::class.java).setAction(VideoCaptureService.START)
                .putExtra(VideoCaptureService.CONSENT, data).putExtra(VideoCaptureService.GENERATION, link.generation))
            // The foreground capture lease is enough; a shared central screen may hide the viewer.
            launchDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
        } catch (error: Exception) {
            VideoDiagnostics.exception(VideoDiagnostics.Event.CAPTURE_UNAVAILABLE, error)
            VideoState.status = "capture_unavailable"; toast(R.string.video_unavailable)
        }
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
            VideoState.status == "waiting_viewer" -> R.string.video_waiting_viewer
            VideoState.status == "capture_paused" -> R.string.video_source_paused
            VideoState.status == "streaming" -> R.string.video_streaming
            VideoState.status in setOf("starting", "negotiating", "connected_waiting_frames") -> R.string.video_connecting
            VideoState.status == "control_unavailable" -> R.string.video_control_lost
            VideoState.status == "receiver_address_unsupported" -> R.string.video_address_unsupported
            VideoState.status == "h264_profile_unsupported" -> R.string.video_h264_profile_unsupported
            VideoState.status != "stopped" -> R.string.video_restart_required
            link == null -> R.string.video_connection_required
            else -> R.string.video_ready
        })
        findViewById<TextView>(R.id.video_metrics).text = VideoState.metrics
        findViewById<TextView>(R.id.video_history).text = VideoDiagnostics.recent()
        findViewById<Button>(R.id.video_send_diagnostics).isEnabled =
            VideoDiagnosticsTransfer.availability == VideoDiagnosticsTransfer.Availability.AVAILABLE &&
                VideoDiagnosticsTransfer.result != VideoDiagnosticsTransfer.Result.SENDING
        val transferText = getString(when (VideoDiagnosticsTransfer.result) {
            VideoDiagnosticsTransfer.Result.SENDING -> R.string.video_diagnostics_sending
            VideoDiagnosticsTransfer.Result.RECEIVED -> R.string.video_diagnostics_received
            VideoDiagnosticsTransfer.Result.REJECTED -> R.string.video_diagnostics_rejected
            VideoDiagnosticsTransfer.Result.FAILED -> R.string.video_diagnostics_failed
            VideoDiagnosticsTransfer.Result.TIMEOUT -> R.string.video_diagnostics_timeout
            VideoDiagnosticsTransfer.Result.IDLE -> when (VideoDiagnosticsTransfer.availability) {
                VideoDiagnosticsTransfer.Availability.DISCONNECTED -> R.string.video_diagnostics_connect
                VideoDiagnosticsTransfer.Availability.UNSUPPORTED -> R.string.video_diagnostics_unsupported
                VideoDiagnosticsTransfer.Availability.AVAILABLE -> R.string.video_diagnostics_send_help
            }
        }) + when {
            VideoDiagnosticsTransfer.result == VideoDiagnosticsTransfer.Result.IDLE -> ""
            VideoDiagnosticsTransfer.availability == VideoDiagnosticsTransfer.Availability.DISCONNECTED -> "\n" + getString(R.string.video_diagnostics_connect)
            VideoDiagnosticsTransfer.availability == VideoDiagnosticsTransfer.Availability.UNSUPPORTED -> "\n" + getString(R.string.video_diagnostics_unsupported)
            else -> ""
        }
        val transferView = findViewById<TextView>(R.id.video_diagnostics_transfer)
        if (transferView.text.toString() != transferText) transferView.text = transferText
        findViewById<Button>(R.id.video_choose).isEnabled = !(VideoState.active || VideoState.captureBusy) && !consentPending
        findViewById<Button>(R.id.video_open_app).isEnabled = chosen != null && !consentPending
        findViewById<Button>(R.id.video_start).isEnabled = chosen != null && link != null && !(VideoState.active || VideoState.captureBusy) && !consentPending
        findViewById<Button>(R.id.video_stop).isEnabled = (VideoState.active || VideoState.captureBusy)
    }
    private fun toast(resource: Int) { Toast.makeText(this, resource, Toast.LENGTH_LONG).show() }
    companion object { private const val CAPTURE = 10 }
}

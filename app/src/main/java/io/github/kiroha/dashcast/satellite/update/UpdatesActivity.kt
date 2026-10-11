package io.github.kiroha.dashcast.satellite.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.kiroha.dashcast.satellite.R

class UpdatesActivity : Activity() {
    private lateinit var session: UpdateSession
    private var resumed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates)
        findViewById<View>(R.id.update_root).setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        @Suppress("DEPRECATION")
        session = lastNonConfigurationInstance as? UpdateSession ?: createSession(applicationContext)
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        findViewById<TextView>(R.id.update_installed).text = getString(R.string.update_installed, installed)
        findViewById<Button>(R.id.update_check).setOnClickListener { session.check() }
        findViewById<Button>(R.id.update_download).setOnClickListener { session.download() }
        findViewById<Button>(R.id.update_install).setOnClickListener {
            if (!packageManager.canRequestPackageInstalls()) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"))) }.onFailure { unavailable() }
            } else session.verifyForInstall()
        }
        findViewById<Button>(R.id.update_close).setOnClickListener { finish() }
        render()
    }
    override fun onResume() {
        super.onResume(); resumed = true
        session.observer = { render() }
        render()
    }
    override fun onPause() { resumed = false; session.observer = null; super.onPause() }
    @Deprecated("Framework-only retained worker")
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onDestroy() {
        session.observer = null
        if (!isChangingConfigurations) session.close()
        super.onDestroy()
    }
    private fun render() {
        val state = session.state
        val status = when (state) {
            "checking" -> R.string.update_checking
            "current" -> R.string.update_current
            "available" -> R.string.update_available
            "downloading" -> R.string.update_downloading
            "verifying" -> R.string.update_verifying
            "ready", "installable" -> R.string.update_ready
            "error" -> R.string.update_error
            else -> R.string.update_idle
        }
        findViewById<TextView>(R.id.update_status).text = if (state == "downloading")
            getString(status, session.percent) else getString(status)
        findViewById<TextView>(R.id.update_notes).text = session.release?.let {
            getString(R.string.update_release_notes, it.tag, it.notes)
        } ?: ""
        val busy = state in listOf("checking", "downloading", "verifying")
        findViewById<Button>(R.id.update_check).isEnabled = !busy
        findViewById<Button>(R.id.update_download).apply {
            visibility = if (session.release != null && session.file == null) View.VISIBLE else View.GONE
            isEnabled = !busy
        }
        findViewById<Button>(R.id.update_install).apply {
            visibility = if (session.file != null) View.VISIBLE else View.GONE
            isEnabled = !busy
        }
        if (state == "installable" && resumed && !isFinishing && !isDestroyed) {
            session.installerOpened()
            if (!packageManager.canRequestPackageInstalls()) { unavailable(); return }
            runCatching {
                val file = requireNotNull(session.file)
                val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("DashSat update", uri)
                })
            }.onFailure { session.installerFailed(); unavailable() }
        }
    }
    companion object {
        internal var createSession: (android.content.Context) -> UpdateSession = { UpdateSession(it) }
    }
    private fun unavailable() = Toast.makeText(this, R.string.update_installer_unavailable, Toast.LENGTH_LONG).show()
}

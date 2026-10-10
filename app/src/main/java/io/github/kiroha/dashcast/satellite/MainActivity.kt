package io.github.kiroha.dashcast.satellite

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.kiroha.dashcast.satellite.navigation.NavigationNotificationListenerService
import io.github.kiroha.dashcast.satellite.navigation.NavigationObservationBus
import io.github.kiroha.dashcast.satellite.navigation.NavigationSource
import io.github.kiroha.dashcast.satellite.navigation.NotificationAccess
import io.github.kiroha.dashcast.satellite.navigation.SourceStatus
import io.github.kiroha.dashcast.satellite.pairing.PairingProfile
import io.github.kiroha.dashcast.satellite.pairing.PairingStore
import io.github.kiroha.dashcast.satellite.pairing.CodePairingActivity
import io.github.kiroha.dashcast.satellite.pairing.PairingOperation
import io.github.kiroha.dashcast.satellite.pairing.SavedReceiver
import io.github.kiroha.dashcast.satellite.pairing.SatelliteDeviceIdentity
import io.github.kiroha.dashcast.satellite.transport.TransportState

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SatelliteSettings
    private var paired = false
    private var savedReceiver: SavedReceiver? = null
    private var lastImportRevision = -1L
    private var importOperation: PairingOperation.Ticket? = null
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        settings = SatelliteSettings(this)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
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
        findViewById<Button>(R.id.code_pairing).setOnClickListener {
            if (PairingOperation.busy) return@setOnClickListener
            stopTransmission()
            startActivity(Intent(this, CodePairingActivity::class.java))
        }
        findViewById<Button>(R.id.import_pairing).setOnClickListener {
            if (PairingOperation.busy) return@setOnClickListener
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            runCatching { startActivityForResult(intent, IMPORT_PROFILE) }
                .onFailure { toast(R.string.settings_unavailable) }
        }
        findViewById<Button>(R.id.forget_pairing).setOnClickListener {
            stopTransmission()
            runCatching { PairingOperation.forget { PairingStore(this).clear() } }
                .onFailure { toast(R.string.import_failed) }
            refreshPairing()
            renderStatus()
        }
        val choice = findViewById<RadioGroup>(R.id.source_choice)
        choice.check(if (settings.source == NavigationSource.MAPS) R.id.maps else R.id.abrp)
        choice.setOnCheckedChangeListener { _, checked ->
            settings.source = if (checked == R.id.maps) NavigationSource.MAPS else NavigationSource.ABRP
            NavigationObservationBus.configure(settings.source, settings.enabled)
        }
        findViewById<Button>(R.id.grant_access).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { toast(R.string.settings_unavailable) }
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }.onFailure { toast(R.string.app_info_unavailable) }
        }
        findViewById<Button>(R.id.start).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
            } else startTransmission()
        }
        findViewById<Button>(R.id.stop).setOnClickListener { stopTransmission() }
        findViewById<CheckBox>(R.id.restart_after_boot).apply {
            isChecked = settings.restartAfterBoot
            setOnCheckedChangeListener { _, checked -> settings.restartAfterBoot = checked }
        }
        val webView = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        findViewById<TextView>(R.id.device_info).text = getString(R.string.device_info,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            webView ?: getString(R.string.unknown))
        val app = applicationContext
        Thread({
            val identity = runCatching { SatelliteDeviceIdentity.load(app) }.getOrNull()
            handler.post {
                if (!isDestroyed && !isFinishing && identity != null) {
                    findViewById<TextView>(R.id.satellite_identity).text =
                        getString(R.string.satellite_identity, identity.name, identity.id)
                }
            }
        }, "satellite-identity").start()
    }

    override fun onResume() {
        super.onResume()
        refreshPairing()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        importOperation?.let(PairingOperation::cancel)
        importOperation = null
        super.onDestroy()
    }

    override fun onStop() {
        importOperation?.let(PairingOperation::cancel)
        importOperation = null
        super.onStop()
    }

    @Deprecated("Platform callback used for the minimal framework-only UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val ticket = PairingOperation.begin() ?: return
        importOperation = ticket
        stopTransmission()
        renderStatus()
        Thread({
            val success = runCatching {
                val json = contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = ByteArray(16_385)
                    var count = 0
                    while (count < bytes.size) {
                        val read = stream.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    require(count in 1..16_384)
                    String(bytes, 0, count, Charsets.UTF_8)
                } ?: error("unreadable_profile")
                val profile = PairingProfile.parse(json)
                PairingOperation.complete(ticket) { PairingStore(applicationContext).save(profile) }
            }.getOrDefault(false)
            PairingOperation.cancel(ticket)
            handler.post {
                if (importOperation === ticket && !isFinishing && !isDestroyed) {
                    importOperation = null
                    refreshPairing()
                    renderStatus()
                    toast(if (success) R.string.import_ok else R.string.import_failed)
                }
            }
        }, "pairing-import").start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Android permits the foreground service even if its notification drawer permission is denied.
        if (requestCode == NOTIFICATION_PERMISSION) startTransmission()
    }

    private fun startTransmission() {
        if (PairingOperation.busy) return
        refreshPairing()
        if (!paired) { toast(R.string.need_profile); return }
        settings.enabled = true
        try {
            startForegroundService(Intent(this, SatelliteService::class.java))
            if (NotificationAccess.isGranted(this)) {
                NotificationListenerService.requestRebind(ComponentName(this,
                    NavigationNotificationListenerService::class.java))
            }
        } catch (_: Exception) {
            settings.enabled = false
            toast(R.string.service_error)
        }
        renderStatus()
    }

    private fun stopTransmission() {
        settings.enabled = false
        NavigationObservationBus.configure(settings.source, transmittingEnabled = false)
        stopService(Intent(this, SatelliteService::class.java))
        renderStatus()
    }

    private fun refreshPairing() {
        savedReceiver = runCatching { PairingStore(this).load()?.let(SavedReceiver::from) }.getOrNull()
        paired = savedReceiver != null
    }

    private fun renderStatus() {
        // An import can finish after Activity recreation; refresh the newly visible instance too.
        if (lastImportRevision != PairingOperation.revision) {
            refreshPairing()
            lastImportRevision = PairingOperation.revision
        }
        val control = SatelliteState.transport
        val connection = when (control.state) {
            TransportState.STOPPED -> R.string.state_stopped
            TransportState.WAITING_FOR_LAN -> R.string.state_waiting_for_lan
            TransportState.CONNECTING -> R.string.state_connecting
            TransportState.AUTHENTICATING -> R.string.state_authenticating
            TransportState.CONNECTED -> R.string.state_connected
            TransportState.RECONNECTING -> R.string.state_reconnecting
            TransportState.PAIRING_REJECTED -> R.string.state_pairing_rejected
        }
        val granted = NotificationAccess.isGranted(this)
        val source = when (if (granted) NavigationObservationBus.status else SourceStatus.PERMISSION_MISSING) {
            SourceStatus.ACTIVE -> R.string.source_active
            SourceStatus.INACTIVE -> R.string.source_inactive
            SourceStatus.UNSUPPORTED -> R.string.source_unsupported
            SourceStatus.PERMISSION_MISSING -> R.string.source_permission_missing
            SourceStatus.SOURCE_UNAVAILABLE -> R.string.source_unavailable
        }
        findViewById<TextView>(R.id.pairing_state).text = savedReceiver?.let {
            getString(R.string.saved_receiver, it.displayId, it.hosts.joinToString(", "))
        } ?: getString(R.string.not_paired)
        findViewById<TextView>(R.id.access_state).setText(if (granted) R.string.access_on else R.string.access_off)
        val restrictedHelpVisible = Build.VERSION.SDK_INT >= 33 && !granted
        findViewById<View>(R.id.restricted_access_help).visibility = if (restrictedHelpVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.open_app_info).visibility = if (restrictedHelpVisible) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.connection_state).text = if (control.detail == "service_start_failed")
            getString(R.string.service_error) else getString(R.string.connection_status, getString(connection))
        val details = mutableListOf<String>()
        control.endpointHost?.let { details.add(getString(R.string.connection_endpoint, it)) }
        val issue = when (control.detail) {
            "connection_timeout", "handshake_timeout" -> R.string.connection_timeout
            "authentication_timeout" -> R.string.authentication_timeout
            "connection_failed" -> R.string.connection_failed
            "connection_closed" -> R.string.connection_closed
            "certificate_rejected" -> R.string.certificate_rejected
            "tls_failed" -> R.string.tls_failed
            "pairing_rejected" -> R.string.pairing_rejected
            "lan_lost" -> R.string.lan_lost
            "heartbeat_timeout" -> R.string.heartbeat_timeout
            "protocol_rejected" -> R.string.protocol_rejected
            "invalid_server_message" -> R.string.invalid_server_message
            "transport_failure" -> R.string.transport_failure
            "slow_connection" -> R.string.slow_connection
            else -> null
        }
        issue?.let { details.add(getString(R.string.connection_last_issue, getString(it))) }
        findViewById<TextView>(R.id.connection_detail).apply {
            text = details.joinToString("\n")
            visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.source_state).text = getString(R.string.source_status, getString(source))
        findViewById<TextView>(R.id.receiver_state).text = getString(R.string.receiver_status,
            getString(if (control.remoteGuidance) R.string.receiver_enabled
                else if (control.state == TransportState.CONNECTED) R.string.receiver_guidance_off
                else R.string.receiver_disabled))
        val busy = PairingOperation.busy
        findViewById<Button>(R.id.code_pairing).isEnabled = !busy
        findViewById<Button>(R.id.import_pairing).isEnabled = !busy
        findViewById<Button>(R.id.forget_pairing).isEnabled = paired && !busy
        findViewById<Button>(R.id.start).isEnabled = paired && !busy &&
            (!settings.enabled || control.state == TransportState.STOPPED)
        findViewById<Button>(R.id.stop).isEnabled = settings.enabled
    }

    private fun toast(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    companion object {
        private const val IMPORT_PROFILE = 1
        private const val NOTIFICATION_PERMISSION = 2
    }
}

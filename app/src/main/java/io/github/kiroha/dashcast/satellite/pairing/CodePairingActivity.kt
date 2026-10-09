package io.github.kiroha.dashcast.satellite.pairing

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.kiroha.dashcast.satellite.R

/** Pairing input is never stored in instance state, intents, clipboard or diagnostics. */
class CodePairingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var operation: PairingOperation.Ticket? = null
    private var client: CodePairingClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_code_pairing)
        findViewById<View>(R.id.code_pairing_root).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        findViewById<CheckBox>(R.id.manual_address_toggle).setOnCheckedChangeListener { _, checked ->
            findViewById<View>(R.id.manual_address_fields).visibility = if (checked) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.code_pair).setOnClickListener { pair() }
        findViewById<Button>(R.id.code_cancel).setOnClickListener { cancelPairing(clearInput = true); finish() }
    }

    private fun pair() {
        if (operation != null) return
        val codeInput = findViewById<EditText>(R.id.pairing_code)
        val code = runCatching { SatellitePairingCode.normalize(codeInput.text.toString()) }.getOrNull()
        if (code == null) {
            codeInput.error = getString(R.string.pairing_code_invalid)
            return
        }
        val addressInput = findViewById<EditText>(R.id.manual_address)
        val manual = if (findViewById<CheckBox>(R.id.manual_address_toggle).isChecked)
            addressInput.text.toString().trim() else null
        if (manual != null && PairingProfile.numericLocalAddress(manual) == null) {
            addressInput.error = getString(R.string.pairing_address_invalid)
            return
        }
        val ticket = PairingOperation.begin() ?: return
        operation = ticket
        val activeClient = CodePairingClient(applicationContext)
        client = activeClient
        setBusy(true)
        findViewById<TextView>(R.id.code_pairing_status).setText(R.string.pairing_code_connecting)
        Thread({
            var failure: CodePairingClient.Failure? = null
            val success = try {
                val profile = activeClient.pair(code, manual)
                PairingOperation.complete(ticket) { PairingStore(applicationContext).save(profile) }
            } catch (error: CodePairingClient.PairingException) {
                failure = error.failure
                false
            } catch (_: Exception) {
                false
            } finally {
                activeClient.close()
                PairingOperation.cancel(ticket)
            }
            handler.post {
                if (operation !== ticket || isFinishing || isDestroyed) return@post
                operation = null
                client = null
                setBusy(false)
                if (success) {
                    codeInput.text.clear()
                    Toast.makeText(this, R.string.import_ok, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    findViewById<TextView>(R.id.code_pairing_status).setText(
                        if (failure == CodePairingClient.Failure.NO_LAN) R.string.pairing_code_no_lan
                        else R.string.pairing_code_failed)
                }
            }
        }, "code-pairing").start()
    }

    private fun setBusy(busy: Boolean) {
        findViewById<Button>(R.id.code_pair).isEnabled = !busy
        findViewById<EditText>(R.id.pairing_code).isEnabled = !busy
        findViewById<EditText>(R.id.manual_address).isEnabled = !busy
        findViewById<CheckBox>(R.id.manual_address_toggle).isEnabled = !busy
    }

    private fun cancelPairing(clearInput: Boolean = false) {
        operation?.let(PairingOperation::cancel)
        operation = null
        client?.close()
        client = null
        if (clearInput) findViewById<EditText>(R.id.pairing_code).text.clear()
        setBusy(false)
    }

    override fun onStop() {
        // Stop network work when hidden, but retain input in this Activity while the driver
        // switches to DashCast to check the code. Instance-state saving remains disabled.
        cancelPairing()
        findViewById<TextView>(R.id.code_pairing_status).text = ""
        super.onStop()
    }

    override fun onDestroy() {
        cancelPairing(clearInput = true)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

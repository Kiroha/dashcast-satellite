package io.github.kiroha.dashcast.satellite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Guidance recovery is a separate opt-in. No capture consent is stored or replayed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = SatelliteSettings(context)
        if (settings.enabled && settings.restartAfterBoot) {
            runCatching { context.startForegroundService(Intent(context, SatelliteService::class.java)) }
        }
    }
}

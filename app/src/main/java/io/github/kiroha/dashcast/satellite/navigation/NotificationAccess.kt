package io.github.kiroha.dashcast.satellite.navigation

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

object NotificationAccess {
    /** Read-only check. Granting access always remains a separate user action in Android settings. */
    fun isGranted(context: Context): Boolean {
        val component = ComponentName(context, NavigationNotificationListenerService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
            } else {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(component) == true
            }
        } catch (_: RuntimeException) { false }
    }
}

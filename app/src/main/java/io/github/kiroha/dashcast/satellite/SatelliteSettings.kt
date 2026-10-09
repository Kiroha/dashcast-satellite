package io.github.kiroha.dashcast.satellite

import android.content.Context
import io.github.kiroha.dashcast.satellite.navigation.NavigationSource

class SatelliteSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var restartAfterBoot: Boolean
        get() = prefs.getBoolean("restartAfterBoot", false)
        set(value) { prefs.edit().putBoolean("restartAfterBoot", value).apply() }
    var source: NavigationSource
        get() = runCatching {
            NavigationSource.valueOf(prefs.getString("source", "MAPS")!!)
        }.getOrDefault(NavigationSource.MAPS)
        set(value) { prefs.edit().putString("source", value.name).apply() }
}

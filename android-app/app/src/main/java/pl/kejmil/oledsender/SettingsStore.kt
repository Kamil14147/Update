package pl.kejmil.oledsender

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kejmil_settings", Context.MODE_PRIVATE)

    var autostartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var debugModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    var darkThemeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()

    var firmwareManifestUrl: String
        get() = prefs.getString(KEY_FIRMWARE_MANIFEST_URL, DEFAULT_FIRMWARE_MANIFEST_URL)
            ?: DEFAULT_FIRMWARE_MANIFEST_URL
        set(value) = prefs.edit().putString(KEY_FIRMWARE_MANIFEST_URL, value.trim()).apply()

    var appUpdateManifestUrl: String
        get() = prefs.getString(KEY_APP_UPDATE_MANIFEST_URL, DEFAULT_APP_UPDATE_MANIFEST_URL)
            ?: DEFAULT_APP_UPDATE_MANIFEST_URL
        set(value) = prefs.edit().putString(KEY_APP_UPDATE_MANIFEST_URL, value.trim()).apply()

    fun isWidgetEnabled(type: WidgetType): Boolean {
        return prefs.getBoolean("widget_${type.name}", true)
    }

    fun setWidgetEnabled(type: WidgetType, enabled: Boolean) {
        prefs.edit().putBoolean("widget_${type.name}", enabled).apply()
    }

    fun priority(type: WidgetType): Int {
        return prefs.getInt("priority_${type.name}", type.defaultPriority)
    }

    fun setPriority(type: WidgetType, priority: Int) {
        prefs.edit().putInt("priority_${type.name}", priority.coerceIn(1, 100)).apply()
    }

    companion object {
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_DEBUG = "debug"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_FIRMWARE_MANIFEST_URL = "firmware_manifest_url"
        private const val KEY_APP_UPDATE_MANIFEST_URL = "app_update_manifest_url"
        const val DEFAULT_FIRMWARE_MANIFEST_URL =
            "https://raw.githubusercontent.com/krawc/kejmil-oled/main/firmware/latest.json"
        const val DEFAULT_APP_UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/krawc/kejmil-oled/main/android-app/latest.json"
    }
}

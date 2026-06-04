package pl.kejmil.oledsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        if (SettingsStore(context).autostartEnabled) {
            try {
                KejmilForegroundService.start(context)
            } catch (error: Exception) {
                AppBus.log("Autostart failed: ${error.message}")
            }
        }
    }
}

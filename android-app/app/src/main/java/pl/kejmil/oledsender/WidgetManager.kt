package pl.kejmil.oledsender

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WidgetManager(
    context: Context,
    private val onActiveChanged: (WidgetEvent) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val activeEvents = linkedMapOf<String, WidgetEvent>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var current: WidgetEvent? = null
    private var lastSwitchAt = 0L
    private var lastSentJson = ""
    private var lastSentAt = 0L
    private var phoneBattery = -1
    private var phoneCharging = false

    fun submit(event: WidgetEvent) {
        if (!settings.isWidgetEnabled(event.type)) {
            onLog("Ignored ${event.type.wireType}: disabled in settings")
            return
        }

        val fixedEvent = event.copy(priority = settings.priority(event.type))
        if (fixedEvent.type == WidgetType.BATTERY) {
            phoneBattery = fixedEvent.payload.optInt("percent", phoneBattery)
            phoneCharging = fixedEvent.payload.optBoolean("charging", phoneCharging)
        }
        activeEvents[fixedEvent.sourceKey] = fixedEvent
        evaluate(force = fixedEvent.priority >= WidgetType.NAVIGATION.defaultPriority)
    }

    fun clearSource(sourceKey: String) {
        if (activeEvents.remove(sourceKey) != null) {
            evaluate(force = true)
        }
    }

    fun clearType(type: WidgetType) {
        val toRemove = activeEvents.filterValues { it.type == type }.keys.toList()
        toRemove.forEach { activeEvents.remove(it) }
        if (toRemove.isNotEmpty()) {
            evaluate(force = true)
        }
    }

    fun updatePhoneBattery(percent: Int, charging: Boolean) {
        phoneBattery = percent.coerceIn(0, 100)
        phoneCharging = charging
    }

    fun tick() {
        evaluate(force = false)
    }

    private fun evaluate(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        activeEvents.entries.removeAll { it.value.isExpired(now) }

        val candidate = activeEvents.values
            .filter { settings.isWidgetEnabled(it.type) }
            .maxWithOrNull(compareBy<WidgetEvent> { it.priority }.thenBy { it.createdAt })
            ?: buildHomeEvent()

        val previous = current
        val cooldownActive = now - lastSwitchAt < SWITCH_COOLDOWN_MS
        val canSwitch = force ||
            previous == null ||
            previous.isExpired(now) ||
            candidate.sourceKey == previous.sourceKey ||
            candidate.priority > previous.priority ||
            !cooldownActive

        val selected = if (canSwitch) candidate else previous ?: candidate
        if (selected.sourceKey != previous?.sourceKey) {
            lastSwitchAt = now
            onLog("Active widget: ${selected.type.wireType}, priority ${selected.priority}")
        }

        current = selected
        val json = selected.toWireJson()
        val shouldSend = force ||
            json != lastSentJson ||
            now - lastSentAt > HEARTBEAT_MS ||
            selected.type == WidgetType.HOME && now - lastSentAt > HOME_REFRESH_MS

        if (shouldSend) {
            lastSentJson = json
            lastSentAt = now
            onActiveChanged(selected)
        }
    }

    private fun buildHomeEvent(): WidgetEvent {
        val payload = JSONObject()
            .put("time", timeFormat.format(Date()))
            .put("phoneBattery", phoneBattery)
            .put("charging", phoneCharging)
            .put("bt", true)
        if (phoneBattery >= 0) {
            payload.put("percent", phoneBattery)
        }
        return WidgetEvent(
            type = WidgetType.HOME,
            sourceKey = "home",
            priority = settings.priority(WidgetType.HOME),
            payload = payload
        )
    }

    companion object {
        private const val SWITCH_COOLDOWN_MS = 1200L
        private const val HEARTBEAT_MS = 60000L
        private const val HOME_REFRESH_MS = 60000L
    }
}

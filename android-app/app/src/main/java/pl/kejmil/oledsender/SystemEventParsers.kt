package pl.kejmil.oledsender

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object SystemEventParsers {
    private val navigationPackages = setOf(
        "com.google.android.apps.maps",
        "com.google.android.apps.mapslite",
        "com.google.android.projection.gearhead",
        "com.waze",
        "pl.neptis.yanosik.mobi.android",
        "com.yanosik.android",
        "com.sygic.aura",
        "com.tomtom.gplay.navapp",
        "com.huawei.maps.app",
        "com.mapfactor.navigator"
    )

    private val importantPackages = listOf(
        "sms",
        "mms",
        "messaging",
        "messenger",
        "whatsapp",
        "discord",
        "signal",
        "telegram"
    )

    private val mediaPackages = listOf(
        "spotify",
        "youtube",
        "music",
        "audio",
        "media",
        "tidal",
        "deezer",
        "podcast",
        "player",
        "radio",
        "soundcloud",
        "audible",
        "audiobook",
        "maxmpz",
        "vlc",
        "foobar"
    )

    private val weatherPackages = listOf("weather", "pogoda", "meteo", "accuweather")
    private val distanceRegex = Regex("""(?i)\b\d{1,4}([,.]\d)?\s?(m|km)\b""")
    private val temperatureRegex = Regex("""(?i)-?\d{1,2}\s?(\u00B0\s?)?C\b""")
    private val streetRegex = Regex("""(?iu)(ul\.|al\.|aleja|rondo|droga|street|st\.|avenue|ave)\s+[\p{L}0-9 .-]{2,40}""")

    fun sourceKey(sbn: StatusBarNotification, prefix: String): String {
        return "$prefix:${sbn.packageName}:${sbn.key}"
    }

    fun parseNotification(context: Context, sbn: StatusBarNotification): WidgetEvent? {
        if (sbn.packageName == context.packageName || sbn.isClearable.not() && sbn.id == KejmilForegroundService.NOTIFICATION_ID) {
            return null
        }

        val notification = sbn.notification ?: return null
        val title = extrasText(notification, Notification.EXTRA_TITLE)
        val text = extrasText(notification, Notification.EXTRA_TEXT)
        val bigText = extrasText(notification, Notification.EXTRA_BIG_TEXT)
        val subText = extrasText(notification, Notification.EXTRA_SUB_TEXT)
        val lines = extrasLines(notification)
        val titleBig = extrasText(notification, Notification.EXTRA_TITLE_BIG)
        val summary = extrasText(notification, Notification.EXTRA_SUMMARY_TEXT)
        val info = extrasText(notification, Notification.EXTRA_INFO_TEXT)
        val body = listOf(text, bigText, subText, titleBig, summary, info, lines)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        val appLabel = packageLabel(context, sbn.packageName)
        val combined = listOf(title, body).joinToString(" ").trim()

        return when {
            looksLikeCall(notification, sbn.packageName, combined) ->
                callEvent(sbn, title, body, combined)
            looksLikeNavigation(notification, sbn.packageName, combined) ->
                navigationEvent(sbn, appLabel, title, body, combined)
            looksLikeMedia(notification, sbn.packageName, title, body) ->
                mediaNotificationEvent(sbn, appLabel, title, body)
            looksLikeWeather(sbn.packageName, combined) ->
                weatherEvent(sbn, appLabel, title, body, combined)
            looksImportant(notification, sbn.packageName) ->
                notificationEvent(sbn, appLabel, title, body)
            else -> null
        }
    }

    private fun navigationEvent(
        sbn: StatusBarNotification,
        appLabel: String,
        title: String,
        body: String,
        combined: String
    ): WidgetEvent {
        val instruction = chooseNavigationInstruction(title, body, combined)
        val distance = distanceRegex.find(combined)?.value ?: ""
        val street = streetRegex.find(combined)?.value ?: ""
        val direction = detectDirection(combined)

        val payload = JSONObject()
            .put("instruction", limit(instruction, 64))
            .put("distance", limit(distance, 18))
            .put("street", limit(street, 48))
            .put("direction", direction)
            .put("app", appLabel)

        return WidgetEvent(
            type = WidgetType.NAVIGATION,
            sourceKey = sourceKey(sbn, "nav"),
            priority = WidgetType.NAVIGATION.defaultPriority,
            payload = payload
        )
    }

    private fun callEvent(
        sbn: StatusBarNotification,
        title: String,
        body: String,
        combined: String
    ): WidgetEvent {
        val folded = fold(combined)
        val state = when {
            listOf("incoming", "przychodz", "dzwoni").any { folded.contains(it) } -> "incoming"
            listOf("ongoing", "active", "trwa", "rozmowa").any { folded.contains(it) } -> "active"
            else -> "incoming"
        }
        val name = cleanupCallerName(firstMeaningful(title, body, "Nieznany"))
        val number = Regex("""\+?\d[\d ()-]{5,}""").find(combined)?.value?.trim().orEmpty()
        val payload = JSONObject()
            .put("name", limit(name, 40))
            .put("number", limit(number, 24))
            .put("state", state)

        return WidgetEvent(
            type = WidgetType.CALL,
            sourceKey = sourceKey(sbn, "call"),
            priority = WidgetType.CALL.defaultPriority,
            payload = payload
        )
    }

    private fun mediaNotificationEvent(
        sbn: StatusBarNotification,
        appLabel: String,
        title: String,
        body: String
    ): WidgetEvent {
        val displayTitle = firstMeaningful(title, body, appLabel)
        val displayArtist = if (title.isNotBlank() && body.isNotBlank()) body else appLabel
        val payload = JSONObject()
            .put("title", limit(displayTitle, 48))
            .put("artist", limit(displayArtist, 48))
            .put("state", "playing")
            .put("progress", -1)

        return WidgetEvent(
            type = WidgetType.MUSIC,
            sourceKey = "media_notification:${sbn.packageName}",
            priority = WidgetType.MUSIC.defaultPriority,
            payload = payload,
            timeoutMs = 20000L
        )
    }

    private fun weatherEvent(
        sbn: StatusBarNotification,
        appLabel: String,
        title: String,
        body: String,
        combined: String
    ): WidgetEvent {
        val temp = temperatureRegex.find(combined)?.value ?: ""
        val payload = JSONObject()
            .put("temp", limit(temp, 12))
            .put("desc", limit(firstMeaningful(body, title, appLabel), 48))
            .put("city", limit(firstMeaningful(title, appLabel, ""), 32))

        return WidgetEvent(
            type = WidgetType.WEATHER,
            sourceKey = sourceKey(sbn, "weather"),
            priority = WidgetType.WEATHER.defaultPriority,
            payload = payload,
            timeoutMs = 8000L
        )
    }

    private fun notificationEvent(
        sbn: StatusBarNotification,
        appLabel: String,
        title: String,
        body: String
    ): WidgetEvent {
        val payload = JSONObject()
            .put("app", limit(appLabel, 24))
            .put("title", limit(firstMeaningful(title, "", "Notification"), 48))
            .put("text", limit(body, 96))
            .put("timeout", 5000)

        return WidgetEvent(
            type = WidgetType.NOTIFICATION,
            sourceKey = sourceKey(sbn, "notification"),
            priority = WidgetType.NOTIFICATION.defaultPriority,
            payload = payload,
            timeoutMs = 5000L
        )
    }

    private fun looksLikeNavigation(notification: Notification, packageName: String, combined: String): Boolean {
        val lowerPackage = packageName.lowercase(Locale.US)
        val lower = fold(combined)
        return navigationPackages.contains(packageName) ||
            notification.category == Notification.CATEGORY_NAVIGATION ||
            (distanceRegex.containsMatchIn(combined) &&
                listOf(
                    "skrec",
                    "turn",
                    "jedz",
                    "kontynuuj",
                    "continue",
                    "rondo",
                    "roundabout",
                    "zjazd",
                    "exit",
                    "nawig",
                    "kieruj"
                ).any { lower.contains(it) }) ||
            listOf("maps", "mapy", "waze", "yanosik", "navigation", "nav").any { lowerPackage.contains(it) }
    }

    private fun looksLikeCall(notification: Notification, packageName: String, combined: String): Boolean {
        val lowerPackage = packageName.lowercase(Locale.US)
        val lower = fold(combined)
        return notification.category == Notification.CATEGORY_CALL ||
            listOf("dialer", "incallui", "telecom", "phone").any { lowerPackage.contains(it) } ||
            listOf("polaczenie", "dzwoni", "incoming call", "trwa rozmowa").any { lower.contains(it) }
    }

    private fun looksLikeMedia(notification: Notification, packageName: String, title: String, body: String): Boolean {
        val lowerPackage = packageName.lowercase(Locale.US)
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            (mediaPackages.any { lowerPackage.contains(it) } && (title.isNotBlank() || body.isNotBlank()))
    }

    private fun looksLikeWeather(packageName: String, combined: String): Boolean {
        val lowerPackage = packageName.lowercase(Locale.US)
        return temperatureRegex.containsMatchIn(combined) &&
            (weatherPackages.any { lowerPackage.contains(it) } ||
                listOf("weather", "pogoda", "temperatura", "forecast").any {
                    combined.lowercase(Locale.getDefault()).contains(it)
                })
    }

    private fun looksImportant(notification: Notification, packageName: String): Boolean {
        val lowerPackage = packageName.lowercase(Locale.US)
        return notification.category == Notification.CATEGORY_MESSAGE ||
            importantPackages.any { lowerPackage.contains(it) }
    }

    private fun detectDirection(text: String): String {
        val lower = fold(text)
        return when {
            listOf("prawo", "right").any { lower.contains(it) } -> "right"
            listOf("lewo", "left").any { lower.contains(it) } -> "left"
            listOf("prosto", "straight", "continue").any { lower.contains(it) } -> "straight"
            listOf("rondo", "roundabout").any { lower.contains(it) } -> "roundabout"
            listOf("zawroc", "u-turn", "uturn").any { lower.contains(it) } -> "uturn"
            else -> ""
        }
    }

    private fun extrasText(notification: Notification, key: String): String {
        return notification.extras?.getCharSequence(key)?.toString()?.trim().orEmpty()
    }

    private fun extrasLines(notification: Notification): String {
        val array = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        return array?.joinToString(" ") { it.toString() }?.trim().orEmpty()
    }

    private fun packageLabel(context: Context, packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun firstMeaningful(first: String, second: String, fallback: String): String {
        return when {
            first.isNotBlank() -> first.trim()
            second.isNotBlank() -> second.trim()
            else -> fallback
        }
    }

    private fun chooseNavigationInstruction(title: String, body: String, combined: String): String {
        val candidates = listOf(body, title, combined)
            .flatMap { it.split("\n", "  ", " • ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return candidates.firstOrNull { candidate ->
            val folded = fold(candidate)
            listOf("skrec", "turn", "prosto", "straight", "rondo", "roundabout", "zjazd", "exit").any {
                folded.contains(it)
            }
        } ?: candidates.firstOrNull { it != title } ?: firstMeaningful(title, body, "Nawigacja")
    }

    private fun cleanupCallerName(value: String): String {
        val folded = fold(value)
        val generic = listOf("incoming call", "polaczenie", "dzwoni", "phone", "telefon")
            .any { folded.contains(it) }
        return if (generic) "Nieznany" else value
    }

    private fun fold(value: String): String {
        val normalized = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "")
    }

    private fun limit(value: String, max: Int): String {
        val trimmed = value.replace(Regex("""\s+"""), " ").trim()
        return if (trimmed.length <= max) trimmed else trimmed.take(max - 3) + "..."
    }
}

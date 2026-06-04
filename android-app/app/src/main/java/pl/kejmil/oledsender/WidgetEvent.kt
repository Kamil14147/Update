package pl.kejmil.oledsender

import android.os.SystemClock
import org.json.JSONObject

data class WidgetEvent(
    val type: WidgetType,
    val sourceKey: String,
    val priority: Int,
    val payload: JSONObject,
    val timeoutMs: Long = 0L,
    val createdAt: Long = SystemClock.elapsedRealtime()
) {
    val expiresAt: Long = if (timeoutMs > 0L) createdAt + timeoutMs else 0L

    fun isExpired(now: Long = SystemClock.elapsedRealtime()): Boolean {
        return expiresAt > 0L && now >= expiresAt
    }

    fun toWireJson(): String {
        val json = JSONObject(payload.toString())
        json.put("type", type.wireType)
        json.put("priority", priority)
        if (timeoutMs > 0L) {
            json.put("timeout", timeoutMs.coerceAtMost(30_000L).toInt())
        }
        return json.toString()
    }
}

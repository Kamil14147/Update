package pl.kejmil.oledsender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OledPreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1f
        textSize = 7f
        typeface = Typeface.MONOSPACE
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var json: JSONObject? = null
    private var lastJsonText = ""

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setJson(value: String?) {
        val text = value.orEmpty().trim()
        if (text == lastJsonText) {
            return
        }
        lastJsonText = text
        json = runCatching { JSONObject(text) }.getOrNull()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(128)
        val wantedHeight = (width * 64f / 128f).toInt()
        val height = resolveSize(wantedHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / OLED_W, height / OLED_H)
        val offsetX = (width - OLED_W * scale) / 2f
        val offsetY = (height - OLED_H * scale) / 2f

        canvas.drawRoundRect(
            RectF(offsetX - 6f, offsetY - 6f, offsetX + OLED_W * scale + 6f, offsetY + OLED_H * scale + 6f),
            10f,
            10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7, 9, 12) }
        )
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawRect(
            0f,
            0f,
            OLED_W,
            OLED_H,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        )

        val data = json ?: homeJson()
        when (data.optString("type", "home")) {
            "music" -> drawMusic(canvas, data)
            "nav" -> drawNavigation(canvas, data)
            "notification" -> drawNotification(canvas, data)
            "call" -> drawCall(canvas, data)
            "battery" -> drawBattery(canvas, data)
            "weather" -> drawWeather(canvas, data)
            "wifi" -> drawWifi(canvas, data)
            "storage" -> drawStorage(canvas, data)
            "memory" -> drawMemory(canvas, data)
            "alarm" -> drawAlarm(canvas, data)
            "system" -> drawSystem(canvas, data)
            else -> drawHome(canvas, data)
        }
        canvas.restore()
    }

    private fun homeJson(): JSONObject {
        return JSONObject()
            .put("type", "home")
            .put("time", timeFormat.format(Date()))
            .put("phoneBattery", -1)
    }

    private fun drawHeader(canvas: Canvas, title: String, data: JSONObject) {
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 1f
        canvas.drawRect(0f, 0f, 128f, 11f, paint)
        paint.style = Paint.Style.FILL
        text(canvas, fit(title, 15), 3f, 8f, 7f)
        val battery = when {
            data.has("phoneBattery") -> data.optInt("phoneBattery", -1)
            data.has("percent") -> data.optInt("percent", -1)
            else -> -1
        }
        text(canvas, if (battery >= 0) "$battery%" else "--%", 101f, 8f, 7f)
    }

    private fun drawHome(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Glowny", data)
        centered(canvas, data.optString("time", timeFormat.format(Date())), 34f, 14f, 9)
        centered(canvas, "Powered by Kejmil", 49f, 7f, 21)
    }

    private fun drawMusic(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Muzyka", data)
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(8f, 20f, 3f, paint)
        canvas.drawLine(11f, 20f, 11f, 13f, paint)
        canvas.drawLine(11f, 13f, 18f, 15f, paint)
        paint.style = Paint.Style.FILL
        text(canvas, fit(data.optString("title", "Brak muzyki"), 17), 24f, 20f, 7f)
        text(canvas, fit(data.optString("artist", "Nieznany artysta"), 17), 24f, 30f, 7f)
        text(canvas, if (data.optString("state") == "playing") "GRA" else "PAUZA", 4f, 45f, 7f)
        val progress = data.optInt("progress", -1)
        progress(canvas, 40f, 39f, 83f, 7f, progress.coerceAtLeast(0))
    }

    private fun drawNavigation(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Nawigacja", data)
        val direction = data.optString("direction", "right")
        arrow(canvas, direction, 5f, 31f)
        text(canvas, "skrec za", 36f, 20f, 7f)
        text(canvas, fit(data.optString("distance", "--"), 7), 36f, 43f, 14f)
        text(canvas, fit(data.optString("instruction", "Brak danych"), 15), 36f, 53f, 7f)
    }

    private fun drawNotification(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, data.optString("app", "Powiadom."), data)
        text(canvas, fit(data.optString("title", "Nowe powiadom."), 21), 4f, 22f, 7f)
        val body = data.optString("text", "Brak tekstu").replace(Regex("\\s+"), " ").trim()
        text(canvas, fit(body.take(21), 21), 4f, 36f, 7f)
        text(canvas, fit(body.drop(21).take(21), 21), 4f, 48f, 7f)
    }

    private fun drawCall(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Polaczenie", data)
        val state = when (data.optString("state", "incoming")) {
            "active" -> "Trwa rozmowa"
            "ended" -> "Zakonczone"
            else -> "Przychodzace"
        }
        centered(canvas, state, 22f, 7f, 21)
        val who = data.optString("name").ifBlank { data.optString("number", "Nieznany") }
        centered(canvas, who, 40f, 14f, 10)
        centered(canvas, data.optString("number", ""), 53f, 7f, 21)
    }

    private fun drawBattery(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Bateria tel.", data)
        val percent = data.optInt("percent", data.optInt("phoneBattery", 0)).coerceIn(0, 100)
        batteryIcon(canvas, 8f, 18f, percent, data.optBoolean("charging", false))
        text(canvas, "$percent%", 54f, 36f, 14f)
        text(canvas, if (data.optBoolean("charging", false)) "ladowanie" else "nie laduje", 54f, 50f, 7f)
    }

    private fun drawWeather(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Pogoda", data)
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(16f, 24f, 8f, paint)
        canvas.drawLine(16f, 10f, 16f, 14f, paint)
        canvas.drawLine(16f, 34f, 16f, 38f, paint)
        canvas.drawLine(2f, 24f, 6f, 24f, paint)
        canvas.drawLine(26f, 24f, 30f, 24f, paint)
        paint.style = Paint.Style.FILL
        text(canvas, fit(data.optString("temp", "--"), 7), 42f, 30f, 14f)
        text(canvas, fit(data.optString("desc", ""), 15), 42f, 42f, 7f)
        text(canvas, fit(data.optString("city", ""), 15), 42f, 52f, 7f)
    }

    private fun drawWifi(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "WiFi", data)
        centered(canvas, fit(data.optString("ssid", "Offline"), 18), 24f, 7f, 18)
        progress(canvas, 12f, 34f, 104f, 8f, data.optInt("signal", 0))
        centered(canvas, data.optString("state", "--"), 55f, 7f, 21)
    }

    private fun drawStorage(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Pamiec plikow", data)
        centered(canvas, "Wolne", 23f, 7f, 21)
        centered(canvas, data.optString("free", "--"), 41f, 14f, 10)
        progress(canvas, 12f, 48f, 104f, 7f, data.optInt("usedPercent", 0))
    }

    private fun drawMemory(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "RAM", data)
        centered(canvas, "Wolne RAM", 23f, 7f, 21)
        centered(canvas, data.optString("free", "--"), 41f, 14f, 10)
        progress(canvas, 12f, 48f, 104f, 7f, data.optInt("usedPercent", 0))
    }

    private fun drawAlarm(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Alarm", data)
        centered(canvas, data.optString("time", "--:--"), 38f, 18f, 6)
        centered(canvas, fit(data.optString("label", "Nastepny alarm"), 21), 53f, 7f, 21)
    }

    private fun drawSystem(canvas: Canvas, data: JSONObject) {
        drawHeader(canvas, "Telefon", data)
        centered(canvas, fit(data.optString("model", "Android"), 18), 24f, 7f, 18)
        centered(canvas, data.optString("uptime", "--"), 41f, 14f, 10)
        centered(canvas, data.optString("android", ""), 54f, 7f, 21)
    }

    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = size
        paint.typeface = Typeface.MONOSPACE
        canvas.drawText(value, x, baseline, paint)
    }

    private fun centered(canvas: Canvas, value: String, baseline: Float, size: Float, maxChars: Int) {
        val fitted = fit(value, maxChars)
        val width = fitted.length * size * 0.62f
        text(canvas, fitted, ((128f - width) / 2f).coerceAtLeast(0f), baseline, size)
    }

    private fun progress(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, value: Int) {
        paint.style = Paint.Style.STROKE
        canvas.drawRect(x, y, x + w, y + h, paint)
        val fill = ((w - 2f) * value.coerceIn(0, 100) / 100f).coerceAtLeast(0f)
        paint.style = Paint.Style.FILL
        if (fill > 0f) canvas.drawRect(x + 1f, y + 1f, x + 1f + fill, y + h - 1f, paint)
    }

    private fun batteryIcon(canvas: Canvas, x: Float, y: Float, percent: Int, charging: Boolean) {
        paint.style = Paint.Style.STROKE
        canvas.drawRect(x, y, x + 36f, y + 18f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(x + 36f, y + 5f, x + 39f, y + 13f, paint)
        val fill = 32f * percent.coerceIn(0, 100) / 100f
        if (fill > 0f) canvas.drawRect(x + 2f, y + 2f, x + 2f + fill, y + 16f, paint)
        if (charging) {
            paint.color = Color.BLACK
            paint.textSize = 7f
            canvas.drawText("+", x + 14f, y + 13f, paint)
            paint.color = Color.WHITE
        }
    }

    private fun arrow(canvas: Canvas, direction: String, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        when (direction) {
            "left" -> {
                canvas.drawLine(x + 23f, y, x + 5f, y, paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(x + 3f, y)
                    lineTo(x + 12f, y - 9f)
                    lineTo(x + 12f, y + 9f)
                    close()
                }, paint)
            }
            "straight" -> {
                canvas.drawLine(x + 12f, y + 10f, x + 12f, y - 9f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(x + 12f, y - 12f)
                    lineTo(x + 4f, y - 3f)
                    lineTo(x + 20f, y - 3f)
                    close()
                }, paint)
            }
            "roundabout" -> {
                canvas.drawCircle(x + 12f, y, 9f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(x + 18f, y - 8f)
                    lineTo(x + 24f, y - 5f)
                    lineTo(x + 18f, y - 2f)
                    close()
                }, paint)
            }
            "uturn" -> {
                canvas.drawRoundRect(RectF(x + 5f, y - 10f, x + 23f, y + 8f), 7f, 7f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(x + 5f, y + 8f)
                    lineTo(x + 12f, y + 2f)
                    lineTo(x + 12f, y + 14f)
                    close()
                }, paint)
            }
            else -> {
                canvas.drawLine(x + 3f, y, x + 21f, y, paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(x + 23f, y)
                    lineTo(x + 14f, y - 9f)
                    lineTo(x + 14f, y + 9f)
                    close()
                }, paint)
            }
        }
    }

    private fun fit(value: String, max: Int): String {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean else clean.take((max - 3).coerceAtLeast(1)) + "..."
    }

    companion object {
        private const val OLED_W = 128f
        private const val OLED_H = 64f
    }
}

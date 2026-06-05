package pl.kejmil.oledsender

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class KejmilForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsStore
    private lateinit var bleClient: BleClient
    private lateinit var widgetManager: WidgetManager
    private lateinit var firmwareUpdater: FirmwareUpdater
    private var weatherCollector: WeatherCollector? = null
    private var automaticFirmwareCheckDone = false

    private var mediaSessionManager: MediaSessionManager? = null
    private val mediaRegistrations = mutableListOf<ControllerRegistration>()
    private var mediaListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var lastMediaTitle = ""
    private var lastMediaArtist = ""
    private var lastMediaProgress = -1
    private var lastMediaRefresh = 0L
    private var lastNotificationSnapshotAt = 0L

    private var batteryReceiver: BroadcastReceiver? = null
    private var lastBatteryPercent = -1
    private var lastBatteryCharging = false
    private var lastBatteryWidgetAt = 0L
    private var lastSystemWidgetAt = 0L
    private var systemWidgetIndex = 0
    private val shortTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    private val busListener: (AppBus.Message) -> Unit = { message ->
        when (message) {
            is AppBus.Message.SubmitWidget -> widgetManager.submit(message.event)
            is AppBus.Message.ClearSource -> widgetManager.clearSource(message.sourceKey)
            is AppBus.Message.ClearType -> widgetManager.clearType(message.type)
            is AppBus.Message.Reconnect -> bleClient.reconnectNow()
            is AppBus.Message.RequestFirmwareVersion -> bleClient.requestFirmwareVersion()
            is AppBus.Message.CheckFirmwareUpdates -> firmwareUpdater.checkForUpdates(automatic = false)
            is AppBus.Message.InstallFirmwareUpdate -> firmwareUpdater.installLatest()
            is AppBus.Message.CancelFirmwareUpdate -> {
                firmwareUpdater.cancelInstall()
                bleClient.abortFirmwareUpdate()
            }
            is AppBus.Message.RefreshNotificationSources -> refreshNotificationAndMedia("manual scan")
            is AppBus.Message.DebugJson -> {
                if (settings.debugModeEnabled) {
                    bleClient.send(message.json)
                    log("Debug JSON sent")
                } else {
                    log("Debug JSON ignored: debug mode is off")
                }
            }
            is AppBus.Message.Status,
            is AppBus.Message.FirmwareStatus,
            is AppBus.Message.AppUpdateStatus,
            is AppBus.Message.CheckAppUpdates,
            is AppBus.Message.InstallAppUpdate,
            is AppBus.Message.CancelAppUpdate,
            is AppBus.Message.Log -> Unit
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            widgetManager.tick()
            val now = System.currentTimeMillis()
            if (now - lastMediaRefresh > MEDIA_REFRESH_MS) {
                lastMediaRefresh = now
                refreshMediaControllers()
            }
            if (now - lastNotificationSnapshotAt > NOTIFICATION_SNAPSHOT_MS) {
                lastNotificationSnapshotAt = now
                if (!KejmilNotificationListenerService.isConnected()) {
                    KejmilNotificationListenerService.requestRebind(this@KejmilForegroundService)
                }
                KejmilNotificationListenerService.requestActiveSnapshot("service tick")
            }
            weatherCollector?.refreshIfNeeded()
            refreshSystemWidgetIfNeeded(now)
            handler.postDelayed(this, SERVICE_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        widgetManager = WidgetManager(
            context = this,
            onActiveChanged = { event -> sendActiveWidget(event) },
            onLog = { text -> log(text) }
        )
        bleClient = BleClient(
            context = this,
            onStatus = { status ->
                AppBus.publish(AppBus.Message.Status(bleStatus = status))
                updateForegroundNotification(status)
            },
            onLog = { text -> log(text) },
            onFirmwareMessage = { line -> handleFirmwareMessage(line) },
            onFirmwareTransferFinished = { firmwareUpdater.markBleTransferFinished() }
        )
        firmwareUpdater = FirmwareUpdater(
            context = this,
            settings = settings,
            onLog = { text -> log(text) },
            onFirmwareReady = { manifest, firmware ->
                bleClient.startFirmwareUpdate(manifest, firmware)
            }
        )

        startAsForeground("Starting")
        AppBus.subscribe(busListener)
        startCollectors()
        bleClient.start()
        handler.postDelayed(tickRunnable, SERVICE_TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> bleClient.reconnectNow()
            ACTION_SCAN_SOURCES -> refreshNotificationAndMedia("manual scan")
            ACTION_DEBUG_JSON -> {
                intent.getStringExtra(EXTRA_JSON)?.let { json ->
                    if (settings.debugModeEnabled) bleClient.send(json)
                }
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        AppBus.unsubscribe(busListener)
        stopCollectors()
        bleClient.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCollectors() {
        registerBatteryReceiver()
        registerMediaSessionListener()
        refreshNotificationAndMedia("service start")
        registerCallListener()
        registerWeatherCollector()
    }

    private fun stopCollectors() {
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        unregisterMediaControllers()
        mediaListener?.let { listener ->
            mediaSessionManager?.removeOnActiveSessionsChangedListener(listener)
        }
        mediaListener = null
        unregisterCallListener()
        weatherCollector?.close()
        weatherCollector = null
    }

    private fun sendActiveWidget(event: WidgetEvent) {
        val json = event.toWireJson()
        bleClient.send(json)
        AppBus.publish(
            AppBus.Message.Status(
                activeWidget = event.type.wireType,
                lastJson = json
            )
        )
    }

    private fun log(text: String) {
        AppBus.publish(AppBus.Message.Log(text))
    }

    private fun refreshNotificationAndMedia(reason: String) {
        KejmilNotificationListenerService.requestRebind(this)
        KejmilNotificationListenerService.requestActiveSnapshot(reason)
        refreshMediaControllers()
        if (reason == "manual scan") {
            log("Media/navigation scan requested")
        }
    }

    private fun handleFirmwareMessage(line: String) {
        runCatching {
            JSONObject(line)
        }.onSuccess { json ->
            when (json.optString("event")) {
                "version" -> {
                    val version = json.optString("version")
                    if (version.isNotBlank()) {
                        firmwareUpdater.setCurrentEsp32Version(version)
                        if (!automaticFirmwareCheckDone) {
                            automaticFirmwareCheckDone = true
                            firmwareUpdater.checkForUpdates(automatic = true)
                        }
                    }
                }
                "ota" -> handleOtaStatus(json)
            }
        }.onFailure {
            log("Firmware message parse failed: ${it.message}")
        }
    }

    private fun handleOtaStatus(json: JSONObject) {
        val state = json.optString("state")
        val progress = json.optInt("progress", -1).takeIf { it >= 0 }
        val message = json.optString("message")
        val status = when (state) {
            "ready" -> "ESP32 gotowe na OTA"
            "progress" -> "ESP32 zapisuje firmware"
            "success" -> "Update OK, ESP32 restartuje sie"
            "error" -> "Update failed: ${if (message.isBlank()) "blad ESP32" else message}"
            else -> "OTA: $state"
        }
        if (state == "success" || state == "error") {
            firmwareUpdater.markBleTransferFinished()
        }
        AppBus.publish(
            AppBus.Message.FirmwareStatus(
                status = status,
                progress = progress
            )
        )
        if (state == "success") {
            handler.postDelayed({ bleClient.reconnectNow() }, 2500L)
        }
    }

    private fun startAsForeground(status: String) {
        createNotificationChannel()
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KejmilForegroundService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_stat_oled)
            .setContentTitle("KESP32")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_oled, "Polacz ponownie", reconnectIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Usluga KESP32",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Utrzymuje polaczenie BLE z ESP32 OLED"
        manager.createNotificationChannel(channel)
    }

    private fun registerBatteryReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleBattery(intent)
            }
        }
        batteryReceiver = receiver
        val sticky = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { handleBattery(it) }
    }

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) {
            return
        }
        val percent = ((level.toFloat() / scale.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        widgetManager.updatePhoneBattery(percent, charging)

        val now = System.currentTimeMillis()
        val changed = percent != lastBatteryPercent || charging != lastBatteryCharging
        val shouldShow = percent <= LOW_BATTERY_PERCENT ||
            charging != lastBatteryCharging ||
            now - lastBatteryWidgetAt > BATTERY_WIDGET_INTERVAL_MS

        lastBatteryPercent = percent
        lastBatteryCharging = charging

        if (changed && shouldShow) {
            lastBatteryWidgetAt = now
            val payload = JSONObject()
                .put("percent", percent)
                .put("charging", charging)
            widgetManager.submit(
                WidgetEvent(
                    type = WidgetType.BATTERY,
                    sourceKey = "battery",
                    priority = WidgetType.BATTERY.defaultPriority,
                    payload = payload,
                    timeoutMs = if (percent <= LOW_BATTERY_PERCENT) 0L else 6000L
                )
            )
        }
    }

    private fun registerMediaSessionListener() {
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = ComponentName(this, KejmilNotificationListenerService::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateMediaControllers(controllers ?: emptyList())
        }
        mediaListener = listener
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(listener, component, handler)
            refreshMediaControllers()
            log("MediaSession listener registered")
        } catch (error: SecurityException) {
            log("MediaSession unavailable until Notification Listener permission is enabled")
        }
    }

    private fun refreshMediaControllers() {
        val component = ComponentName(this, KejmilNotificationListenerService::class.java)
        try {
            val controllers = mediaSessionManager?.getActiveSessions(component).orEmpty()
            updateMediaControllers(controllers)
        } catch (_: SecurityException) {
            AppBus.publish(AppBus.Message.ClearSource("media"))
            KejmilNotificationListenerService.requestRebind(this)
        }
    }

    private fun updateMediaControllers(controllers: List<MediaController>) {
        unregisterMediaControllers()
        if (controllers.isEmpty()) {
            AppBus.publish(AppBus.Message.ClearSource("media"))
            KejmilNotificationListenerService.requestActiveSnapshot("media controllers empty")
            return
        }

        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    emitMedia(controller)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    emitMedia(controller)
                }
            }
            controller.registerCallback(callback, handler)
            mediaRegistrations.add(ControllerRegistration(controller, callback))
            emitMedia(controller)
        }
    }

    private fun unregisterMediaControllers() {
        mediaRegistrations.forEach { registration ->
            registration.controller.unregisterCallback(registration.callback)
        }
        mediaRegistrations.clear()
    }

    private fun emitMedia(controller: MediaController) {
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: ""

        if (title.isBlank() && artist.isBlank()) {
            return
        }

        val state = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE -> {
                AppBus.publish(AppBus.Message.ClearSource("media"))
                lastMediaTitle = ""
                lastMediaArtist = ""
                lastMediaProgress = -1
                return
            }
            else -> "paused"
        }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = playbackState?.position ?: 0L
        val sameTrack = title == lastMediaTitle && artist == lastMediaArtist
        val progress = if (duration > 0L) {
            ((position.toDouble() / duration.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else if (sameTrack) {
            lastMediaProgress
        } else {
            -1
        }
        lastMediaTitle = title
        lastMediaArtist = artist
        if (progress >= 0) {
            lastMediaProgress = progress
        }

        val payload = JSONObject()
            .put("title", limit(title, 48))
            .put("artist", limit(artist, 48))
            .put("state", state)
            .put("progress", progress)

        widgetManager.submit(
            WidgetEvent(
                type = WidgetType.MUSIC,
                sourceKey = "media",
                priority = WidgetType.MUSIC.defaultPriority,
                payload = payload,
                timeoutMs = if (state == "playing") 0L else 15000L
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerCallListener() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            log("Call detection disabled: READ_PHONE_STATE not granted")
            return
        }

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        val manager = telephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, "")
                }
            }
            telephonyCallback = callback
            manager.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Android API")
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    handleCallState(state, incomingNumber.orEmpty())
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        log("Call listener registered")
    }

    private fun registerWeatherCollector() {
        weatherCollector = WeatherCollector(
            context = this,
            onEvent = { event -> widgetManager.submit(event) },
            onLog = { text -> log(text) }
        )
        weatherCollector?.refreshIfNeeded()
    }

    private fun refreshSystemWidgetIfNeeded(now: Long) {
        if (now - lastSystemWidgetAt < SYSTEM_WIDGET_INTERVAL_MS) {
            return
        }
        lastSystemWidgetAt = now
        val event = when (systemWidgetIndex++ % 5) {
            0 -> wifiWidget()
            1 -> storageWidget()
            2 -> memoryWidget()
            3 -> alarmWidget()
            else -> systemWidget()
        }
        widgetManager.submit(event)
    }

    @SuppressLint("MissingPermission")
    private fun wifiWidget(): WidgetEvent {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        val info = wifi?.connectionInfo
        val ssid = if (connected) {
            info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "WiFi"
        } else {
            "Offline"
        }
        val signal = if (connected && info != null) {
            WifiManager.calculateSignalLevel(info.rssi, 100).coerceIn(0, 100)
        } else {
            0
        }
        val payload = JSONObject()
            .put("ssid", limit(ssid, 32))
            .put("signal", signal)
            .put("state", if (connected) "polaczone" else "brak sieci")
        return timedWidget(WidgetType.WIFI, "system:wifi", payload)
    }

    private fun storageWidget(): WidgetEvent {
        val stat = StatFs(filesDir.absolutePath)
        val total = stat.totalBytes.coerceAtLeast(1L)
        val free = stat.availableBytes.coerceAtLeast(0L)
        val usedPercent = (((total - free).toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        val payload = JSONObject()
            .put("free", formatBytesShort(free))
            .put("total", formatBytesShort(total))
            .put("usedPercent", usedPercent)
        return timedWidget(WidgetType.STORAGE, "system:storage", payload)
    }

    private fun memoryWidget(): WidgetEvent {
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val total = info.totalMem.coerceAtLeast(1L)
        val free = info.availMem.coerceAtLeast(0L)
        val usedPercent = (((total - free).toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        val payload = JSONObject()
            .put("free", formatBytesShort(free))
            .put("total", formatBytesShort(total))
            .put("usedPercent", usedPercent)
        return timedWidget(WidgetType.MEMORY, "system:memory", payload)
    }

    private fun alarmWidget(): WidgetEvent {
        val alarm = (getSystemService(ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock
        val payload = JSONObject()
            .put("time", alarm?.let { shortTimeFormat.format(Date(it.triggerTime)) } ?: "--:--")
            .put("label", alarm?.showIntent?.creatorPackage?.substringAfterLast('.') ?: "Brak alarmu")
        return timedWidget(WidgetType.ALARM, "system:alarm", payload)
    }

    private fun systemWidget(): WidgetEvent {
        val uptimeMinutes = SystemClock.elapsedRealtime() / 60000L
        val payload = JSONObject()
            .put("model", limit(Build.MODEL ?: "Android", 32))
            .put("uptime", formatUptime(uptimeMinutes))
            .put("android", "Android ${Build.VERSION.RELEASE}")
        return timedWidget(WidgetType.SYSTEM, "system:device", payload)
    }

    private fun timedWidget(type: WidgetType, sourceKey: String, payload: JSONObject): WidgetEvent {
        return WidgetEvent(
            type = type,
            sourceKey = sourceKey,
            priority = type.defaultPriority,
            payload = payload,
            timeoutMs = SYSTEM_WIDGET_DISPLAY_MS
        )
    }

    private fun formatBytesShort(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            String.format(Locale.US, "%d MB", bytes / (1024L * 1024L))
        }
    }

    private fun formatUptime(minutes: Long): String {
        val hours = minutes / 60L
        val rest = minutes % 60L
        return if (hours > 0L) {
            "${hours}h ${rest}m"
        } else {
            "${rest}m"
        }
    }

    private fun unregisterCallListener() {
        val manager = telephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = telephonyCallback
            if (manager != null && callback != null) {
                manager.unregisterTelephonyCallback(callback)
            }
        } else {
            val listener = phoneStateListener
            if (manager != null && listener != null) {
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    private fun handleCallState(state: Int, number: String) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> submitCall("incoming", number, 0L)
            TelephonyManager.CALL_STATE_OFFHOOK -> submitCall("active", number, 0L)
            TelephonyManager.CALL_STATE_IDLE -> {
                submitCall("ended", number, 3500L)
                handler.postDelayed({
                    AppBus.publish(AppBus.Message.ClearSource("call"))
                }, 3600L)
            }
        }
    }

    private fun submitCall(state: String, number: String, timeoutMs: Long) {
        val displayNumber = if (number.isBlank()) "Niedostepny" else number
        val payload = JSONObject()
            .put("name", if (state == "incoming") "Polaczenie" else "Rozmowa")
            .put("number", displayNumber)
            .put("state", state)
        widgetManager.submit(
            WidgetEvent(
                type = WidgetType.CALL,
                sourceKey = "call",
                priority = WidgetType.CALL.defaultPriority,
                payload = payload,
                timeoutMs = timeoutMs
            )
        )
    }

    private fun limit(value: String, max: Int): String {
        val cleaned = value.replace(Regex("""\s+"""), " ").trim()
        return if (cleaned.length <= max) cleaned else cleaned.take(max - 3) + "..."
    }

    private data class ControllerRegistration(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    companion object {
        const val NOTIFICATION_ID = 4242
        const val ACTION_START = "pl.kejmil.oledsender.START"
        const val ACTION_STOP = "pl.kejmil.oledsender.STOP"
        const val ACTION_RECONNECT = "pl.kejmil.oledsender.RECONNECT"
        const val ACTION_SCAN_SOURCES = "pl.kejmil.oledsender.SCAN_SOURCES"
        const val ACTION_DEBUG_JSON = "pl.kejmil.oledsender.DEBUG_JSON"
        const val EXTRA_JSON = "json"

        private const val CHANNEL_ID = "kejmil_oled_service"
        private const val LOW_BATTERY_PERCENT = 20
        private const val BATTERY_WIDGET_INTERVAL_MS = 10 * 60 * 1000L
        private const val SYSTEM_WIDGET_INTERVAL_MS = 25 * 1000L
        private const val SYSTEM_WIDGET_DISPLAY_MS = 6500L
        private const val MEDIA_REFRESH_MS = 5 * 1000L
        private const val NOTIFICATION_SNAPSHOT_MS = 7000L
        private const val SERVICE_TICK_MS = 3000L

        fun start(context: Context) {
            val intent = Intent(context, KejmilForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, KejmilForegroundService::class.java).setAction(ACTION_STOP))
        }

        fun scanNotificationSources(context: Context) {
            val intent = Intent(context, KejmilForegroundService::class.java).setAction(ACTION_SCAN_SOURCES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

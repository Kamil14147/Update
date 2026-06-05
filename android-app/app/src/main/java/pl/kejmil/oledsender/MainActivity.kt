package pl.kejmil.oledsender

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var appUpdater: AppUpdater
    private lateinit var palette: UiPalette
    private lateinit var rootView: LinearLayout
    private lateinit var tabContent: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var permissionView: TextView
    private lateinit var bleStatusView: TextView
    private lateinit var activeWidgetView: TextView
    private lateinit var lastJsonView: TextView
    private lateinit var logView: TextView
    private lateinit var debugContainer: LinearLayout
    private lateinit var oledPreviewView: OledPreviewView
    private lateinit var firmwareManifestInput: EditText
    private lateinit var firmwareEspVersionView: TextView
    private lateinit var firmwareLatestVersionView: TextView
    private lateinit var firmwareFileView: TextView
    private lateinit var firmwareChangelogView: TextView
    private lateinit var firmwareStatusView: TextView
    private lateinit var firmwareProgress: ProgressBar
    private lateinit var appUpdateManifestInput: EditText
    private lateinit var appCurrentVersionView: TextView
    private lateinit var appLatestVersionView: TextView
    private lateinit var appFileView: TextView
    private lateinit var appChangelogView: TextView
    private lateinit var appStatusView: TextView
    private lateinit var appProgress: ProgressBar

    private val tabViews = linkedMapOf<AppTab, View>()
    private val tabItems = linkedMapOf<AppTab, LinearLayout>()
    private val tabIcons = linkedMapOf<AppTab, ImageView>()
    private val tabLabels = linkedMapOf<AppTab, TextView>()
    private val priorityInputs = linkedMapOf<WidgetType, EditText>()
    private val logLines = ArrayDeque<String>()

    private var currentTab = AppTab.STATUS
    private var firmwareStatusText = "czekam"
    private var firmwareUpdateAvailable: Boolean? = null
    private var firmwareRequired: Boolean? = null
    private var currentEsp32Version: String? = null
    private var latestFirmwareVersion: String? = null
    private var latestFirmwareChangelog: String? = null
    private var latestFirmwareUrl: String? = null
    private var latestFirmwareSizeBytes: Long? = null
    private var appStatusText = "czekam"
    private var appUpdateAvailable: Boolean? = null
    private var appRequired: Boolean? = null
    private var currentAppVersionName: String? = null
    private var currentAppVersionCode: Long? = null
    private var latestAppVersionName: String? = null
    private var latestAppVersionCode: Long? = null
    private var latestAppChangelog: String? = null
    private var latestAppUrl: String? = null
    private var latestAppSizeBytes: Long? = null
    private var shownFirmwareDialogVersion: String? = null
    private var shownAppDialogVersionCode: Long? = null
    private var updateDialog: AlertDialog? = null
    private var updateDialogStatusView: TextView? = null
    private var updateDialogProgress: ProgressBar? = null
    private var updateDialogInstallButton: View? = null
    private var updateDialogEtaView: TextView? = null
    private var updateDialogActionArea: LinearLayout? = null
    private var activeUpdateKind: UpdateKind? = null
    private var updateDialogKind: UpdateKind? = null
    private var firmwareProgressStartedAt = 0L
    private var appProgressStartedAt = 0L

    private val busListener: (AppBus.Message) -> Unit = { message ->
        runOnUiThread {
            when (message) {
                is AppBus.Message.Status -> {
                    message.bleStatus?.let { bleStatusView.text = "BLE: $it" }
                    message.activeWidget?.let { activeWidgetView.text = "Aktywny widget: $it" }
                    message.lastJson?.let {
                        lastJsonView.text = "Ostatni JSON:\n$it"
                        oledPreviewView.setJson(it)
                    }
                }
                is AppBus.Message.FirmwareStatus -> updateFirmwareStatus(message)
                is AppBus.Message.AppUpdateStatus -> updateAppStatus(message)
                is AppBus.Message.CheckAppUpdates -> appUpdater.checkForUpdates(automatic = false)
                is AppBus.Message.InstallAppUpdate -> appUpdater.installLatest()
                is AppBus.Message.CancelAppUpdate -> appUpdater.cancelInstall()
                is AppBus.Message.Log -> appendLog(message.text)
                else -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        appUpdater = AppUpdater(this, settings) { text -> AppBus.log(text) }
        buildUi()
        AppBus.subscribe(busListener)
        appUpdater.publishCurrentVersion()
        appUpdater.checkForUpdates(automatic = true)

        if (!hasRuntimePermissions()) {
            requestRuntimePermissions()
        } else {
            KejmilForegroundService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        if (isNotificationListenerEnabled()) {
            KejmilNotificationListenerService.requestRebind(this)
        }
        AppBus.publish(AppBus.Message.RequestFirmwareVersion)
    }

    override fun onDestroy() {
        AppBus.unsubscribe(busListener)
        updateDialog?.dismiss()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
        if (requestCode == REQUEST_PERMISSIONS && hasRuntimePermissions()) {
            KejmilForegroundService.start(this)
        }
    }

    private fun buildUi() {
        palette = UiPalette.from(settings.darkThemeEnabled)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = if (settings.darkThemeEnabled) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(18), dp(16), dp(18), 0)
            alpha = 0f
        }
        rootView = root

        root.addView(hero(), matchWidth())

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        tabContent = FrameLayout(this).apply {
            setPadding(0, dp(12), 0, dp(18))
        }
        scrollView.addView(
            tabContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        bottomNav = bottomNavigation()
        root.addView(bottomNav, matchWidth().apply { bottomMargin = dp(10) })
        setContentView(root)
        root.animate().alpha(1f).setDuration(180L).start()

        addTabView(AppTab.STATUS, statusTab())
        addTabView(AppTab.WIDGETS, widgetsTab())
        addTabView(AppTab.SETTINGS, settingsTab())
        addTabView(AppTab.UPDATE, updateTab())
        addTabView(AppTab.LOGS, logsTab())
        showTab(AppTab.STATUS, animate = false)
    }

    private fun hero(): LinearLayout {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_oled_logo)
            setColorFilter(Color.WHITE)
            background = rounded(palette.accent, 0)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        hero.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        textBox.addView(TextView(this).apply {
            text = "KESP32"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        })
        hero.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        ObjectAnimator.ofFloat(icon, View.ROTATION, -2f, 2f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(icon, View.ALPHA, 0.82f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
        return hero
    }

    private fun bottomNavigation(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(palette.surface, palette.border)
        }
        AppTab.entries.forEach { tab ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener { showTab(tab) }
            }
            val icon = ImageView(this).apply {
                setImageResource(tab.iconRes)
            }
            val label = TextView(this).apply {
                text = tab.label
                textSize = 10f
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(23), dp(23)))
            item.addView(label, matchWidth().apply { topMargin = dp(3) })
            tabItems[tab] = item
            tabIcons[tab] = icon
            tabLabels[tab] = label
            nav.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        return nav
    }

    private fun addTabView(tab: AppTab, view: View) {
        view.visibility = View.GONE
        view.alpha = 0f
        tabViews[tab] = view
        tabContent.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun showTab(tab: AppTab, animate: Boolean = true) {
        if (tab == currentTab && tabViews[tab]?.visibility == View.VISIBLE) {
            updateBottomNav(tab)
            return
        }
        val previousTab = currentTab
        val previousView = tabViews[previousTab]
        val nextView = tabViews[tab] ?: return
        currentTab = tab
        updateBottomNav(tab)

        previousView?.animate()?.cancel()
        nextView.animate().cancel()
        val direction = if (tab.ordinal >= previousTab.ordinal) 1f else -1f

        if (previousView != null && previousView != nextView && previousView.visibility == View.VISIBLE && animate) {
            previousView.animate()
                .alpha(0f)
                .translationX(-direction * dp(22).toFloat())
                .setDuration(130L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    previousView.visibility = View.GONE
                    previousView.translationX = 0f
                }
                .start()
        } else {
            previousView?.visibility = View.GONE
        }

        nextView.visibility = View.VISIBLE
        nextView.alpha = if (animate) 0f else 1f
        nextView.translationX = if (animate) direction * dp(26).toFloat() else 0f
        nextView.translationY = if (animate) dp(8).toFloat() else 0f
        nextView.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(if (animate) 210L else 0L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun updateBottomNav(selectedTab: AppTab) {
        AppTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val item = tabItems[tab] ?: return@forEach
            val icon = tabIcons[tab] ?: return@forEach
            val label = tabLabels[tab] ?: return@forEach
            val color = if (selected) palette.accent else palette.mutedText
            item.background = if (selected) rounded(palette.accentSoft, 0) else rounded(Color.TRANSPARENT, 0)
            icon.setColorFilter(color)
            label.setTextColor(color)
            label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            item.animate()
                .scaleX(if (selected) 1.04f else 1f)
                .scaleY(if (selected) 1.04f else 1f)
                .setDuration(160L)
                .start()
        }
    }

    private fun statusTab(): LinearLayout {
        val root = tabRoot()

        val statusPanel = panel()
        statusPanel.addView(sectionTitle("Status"))
        bleStatusView = sectionText("BLE: czekam")
        activeWidgetView = sectionText("Aktywny widget: brak")
        permissionView = sectionText("")
        statusPanel.addView(bleStatusView)
        statusPanel.addView(activeWidgetView)
        statusPanel.addView(permissionView)
        root.addView(statusPanel, matchWidth())

        val actionPanel = panel()
        actionPanel.addView(sectionTitle("Sterowanie"))
        actionButton(actionPanel, R.drawable.ic_action_service, "Uruchom usluge") {
            if (!hasRuntimePermissions()) requestRuntimePermissions() else KejmilForegroundService.start(this)
        }
        actionButton(actionPanel, R.drawable.ic_action_reconnect, "Polacz ponownie") {
            AppBus.publish(AppBus.Message.Reconnect)
        }
        actionButton(actionPanel, R.drawable.ic_action_service, "Wylacz usluge") {
            KejmilForegroundService.stop(this)
        }
        actionButton(actionPanel, R.drawable.ic_action_app, "Zamknij aplikacje") {
            KejmilForegroundService.stop(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        }
        root.addView(actionPanel, spaced())

        return root
    }

    private fun widgetsTab(): LinearLayout {
        val root = tabRoot()
        val previewPanel = panel()
        previewPanel.addView(sectionTitle("Podglad OLED"))
        oledPreviewView = OledPreviewView(this)
        previewPanel.addView(oledPreviewView, matchWidth().apply {
            topMargin = dp(6)
        })
        root.addView(previewPanel, matchWidth())

        val widgetPanel = panel()
        widgetPanel.addView(sectionTitle("Widgety"))
        WidgetType.uiOrder.forEach { type ->
            widgetPanel.addView(widgetRow(type))
        }
        actionButton(widgetPanel, R.drawable.ic_action_save, "Zapisz priorytety") {
            savePriorities()
        }
        root.addView(widgetPanel, spaced())
        return root
    }

    private fun settingsTab(): LinearLayout {
        val root = tabRoot()
        val settingsPanel = panel()
        settingsPanel.addView(sectionTitle("Ustawienia"))

        val darkTheme = themedCheckBox("Ciemny motyw").apply {
            isChecked = settings.darkThemeEnabled
            setOnCheckedChangeListener { _, checked ->
                animateThemeChange(checked)
            }
        }
        settingsPanel.addView(darkTheme, matchWidth())

        val autostart = themedCheckBox("Start po restarcie telefonu").apply {
            isChecked = settings.autostartEnabled
            setOnCheckedChangeListener { _, checked -> settings.autostartEnabled = checked }
        }
        settingsPanel.addView(autostart, matchWidth())

        val debugSwitch = themedCheckBox("Tryb debug").apply {
            isChecked = settings.debugModeEnabled
            setOnCheckedChangeListener { _, checked ->
                settings.debugModeEnabled = checked
                debugContainer.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        settingsPanel.addView(debugSwitch, matchWidth())

        actionButton(settingsPanel, R.drawable.ic_action_bell, "Dostep do powiadomien") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        actionButton(settingsPanel, R.drawable.ic_action_permission, "Nie usypiaj") {
            requestIgnoreBatteryOptimization()
        }
        actionButton(settingsPanel, R.drawable.ic_action_permission, "Uprawnienia") {
            requestRuntimePermissions()
        }

        debugContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (settings.debugModeEnabled) View.VISIBLE else View.GONE
        }
        settingsPanel.addView(debugContainer, matchWidth())
        addDebugButtons(debugContainer)

        root.addView(settingsPanel, matchWidth())
        return root
    }

    private fun updateTab(): LinearLayout {
        val root = tabRoot()
        root.addView(firmwareUpdatePanel(), matchWidth())
        root.addView(appUpdatePanel(), spaced())
        return root
    }

    private fun firmwareUpdatePanel(): LinearLayout {
        val panel = panel()
        panel.addView(sectionTitle("ESP32 firmware"))

        firmwareEspVersionView = sectionText("Obecna wersja ESP32: nie odczytano")
        firmwareLatestVersionView = sectionText("Najnowsza wersja: nie sprawdzono")
        firmwareFileView = sectionText("Plik firmware: brak")
        firmwareChangelogView = sectionText("Changelog: brak")
        firmwareStatusView = sectionText("Status: czekam")
        panel.addView(firmwareEspVersionView, matchWidth())
        panel.addView(firmwareLatestVersionView, matchWidth())
        panel.addView(firmwareFileView, matchWidth())
        panel.addView(firmwareChangelogView, matchWidth())
        panel.addView(firmwareStatusView, matchWidth())

        firmwareProgress = progressBar()
        panel.addView(firmwareProgress, matchWidth().apply { topMargin = dp(6) })

        firmwareManifestInput = themedEditText("URL manifestu firmware").apply {
            setText(settings.firmwareManifestUrl)
        }
        panel.addView(firmwareManifestInput, matchWidth().apply { topMargin = dp(10) })

        actionButton(panel, R.drawable.ic_action_save, "Zapisz URL") {
            saveFirmwareUrl()
        }
        actionButton(panel, R.drawable.ic_action_check, "Check ESP32") {
            saveFirmwareUrl(showToast = false)
            AppBus.publish(AppBus.Message.CheckFirmwareUpdates)
        }
        actionButton(panel, R.drawable.ic_action_install, "Aktualizuj ESP32") {
            saveFirmwareUrl(showToast = false)
            AppBus.publish(AppBus.Message.InstallFirmwareUpdate)
        }

        return panel
    }

    private fun appUpdatePanel(): LinearLayout {
        val panel = panel()
        panel.addView(sectionTitle("KESP32 app"))

        appCurrentVersionView = sectionText("Obecna wersja: nie odczytano")
        appLatestVersionView = sectionText("Najnowsza wersja: nie sprawdzono")
        appFileView = sectionText("Plik APK: brak")
        appChangelogView = sectionText("Changelog: brak")
        appStatusView = sectionText("Status: czekam")
        panel.addView(appCurrentVersionView, matchWidth())
        panel.addView(appLatestVersionView, matchWidth())
        panel.addView(appFileView, matchWidth())
        panel.addView(appChangelogView, matchWidth())
        panel.addView(appStatusView, matchWidth())

        appProgress = progressBar()
        panel.addView(appProgress, matchWidth().apply { topMargin = dp(6) })

        appUpdateManifestInput = themedEditText("URL manifestu aplikacji").apply {
            setText(settings.appUpdateManifestUrl)
        }
        panel.addView(appUpdateManifestInput, matchWidth().apply { topMargin = dp(10) })

        actionButton(panel, R.drawable.ic_action_save, "Zapisz URL APK") {
            saveAppUpdateUrl()
        }
        actionButton(panel, R.drawable.ic_action_check, "Check app") {
            saveAppUpdateUrl(showToast = false)
            appUpdater.checkForUpdates(automatic = false)
        }
        actionButton(panel, R.drawable.ic_action_install, "Aktualizuj aplikacje") {
            saveAppUpdateUrl(showToast = false)
            appUpdater.installLatest()
        }

        return panel
    }

    private fun logsTab(): LinearLayout {
        val root = tabRoot()
        val logPanel = panel()
        logPanel.addView(sectionTitle("Logi"))
        lastJsonView = sectionText("Ostatni JSON: brak").apply {
            setTextIsSelectable(true)
        }
        logPanel.addView(lastJsonView, matchWidth())
        logView = sectionText("Zdarzenia: brak")
        logPanel.addView(logView, matchWidth())
        root.addView(logPanel, matchWidth())
        return root
    }

    private fun widgetRow(type: WidgetType): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val enabled = themedCheckBox(widgetName(type)).apply {
            isChecked = settings.isWidgetEnabled(type)
            setOnCheckedChangeListener { _, checked -> settings.setWidgetEnabled(type, checked) }
        }
        row.addView(enabled, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val input = themedEditText("").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.priority(type).toString())
            setEms(3)
            gravity = Gravity.CENTER
        }
        priorityInputs[type] = input
        row.addView(input, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun addDebugButtons(root: LinearLayout) {
        actionButton(root, R.drawable.ic_tab_widgets, "Muzyka test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "music")
                    .put("title", "Utwor testowy")
                    .put("artist", "Artysta testowy")
                    .put("state", "playing")
                    .put("progress", 45)
                    .toString()
            )
        }
        actionButton(root, R.drawable.ic_tab_status, "Nawigacja test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "nav")
                    .put("instruction", "Skrec w prawo")
                    .put("distance", "300 m")
                    .put("street", "ul. Krakowska")
                    .put("direction", "right")
                    .put("priority", 90)
                    .toString()
            )
        }
        actionButton(root, R.drawable.ic_action_bell, "Powiadomienie test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "notification")
                    .put("app", "Debug")
                    .put("title", "Wiadomosc testowa")
                    .put("text", "To jest tylko wiadomosc debug")
                    .put("priority", 80)
                    .put("timeout", 5000)
                    .toString()
            )
        }
        actionButton(root, R.drawable.ic_tab_status, "WiFi test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "wifi")
                    .put("ssid", "KESP32_NET")
                    .put("signal", 82)
                    .put("state", "polaczone")
                    .put("timeout", 6000)
                    .toString()
            )
        }
        actionButton(root, R.drawable.ic_tab_status, "RAM test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "memory")
                    .put("free", "3.2 GB")
                    .put("total", "7.6 GB")
                    .put("usedPercent", 58)
                    .put("timeout", 6000)
                    .toString()
            )
        }
        actionButton(root, R.drawable.ic_tab_status, "Alarm test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "alarm")
                    .put("time", "06:30")
                    .put("label", "Pobudka")
                    .put("timeout", 6000)
                    .toString()
            )
        }
        actionButton(root, R.drawable.ic_action_app, "Telefon test") {
            sendDebugJson(
                JSONObject()
                    .put("type", "system")
                    .put("model", "Android")
                    .put("uptime", "4h 12m")
                    .put("android", "Android")
                    .put("timeout", 6000)
                    .toString()
            )
        }
    }

    private fun sendDebugJson(json: String) {
        if (!settings.debugModeEnabled) {
            Toast.makeText(this, "Najpierw wlacz tryb debug", Toast.LENGTH_SHORT).show()
            return
        }
        oledPreviewView.setJson(json)
        AppBus.publish(AppBus.Message.DebugJson(json))
    }

    private fun savePriorities() {
        priorityInputs.forEach { (type, input) ->
            val value = input.text.toString().toIntOrNull() ?: type.defaultPriority
            settings.setPriority(type, value)
        }
        Toast.makeText(this, "Priorytety zapisane", Toast.LENGTH_SHORT).show()
    }

    private fun saveFirmwareUrl(showToast: Boolean = true) {
        settings.firmwareManifestUrl = firmwareManifestInput.text.toString()
        if (showToast) Toast.makeText(this, "URL firmware zapisany", Toast.LENGTH_SHORT).show()
    }

    private fun saveAppUpdateUrl(showToast: Boolean = true) {
        settings.appUpdateManifestUrl = appUpdateManifestInput.text.toString()
        if (showToast) Toast.makeText(this, "URL APK zapisany", Toast.LENGTH_SHORT).show()
    }

    private fun updateFirmwareStatus(status: AppBus.Message.FirmwareStatus) {
        status.esp32Version?.let {
            currentEsp32Version = it
            firmwareEspVersionView.text = "Obecna wersja ESP32: $it"
        }
        status.latestVersion?.let {
            latestFirmwareVersion = it
            firmwareLatestVersionView.text = "Najnowsza wersja: $it"
        }
        status.changelog?.let {
            latestFirmwareChangelog = it
            firmwareChangelogView.text = if (it.isBlank()) "Changelog: brak" else "Changelog:\n$it"
        }
        status.firmwareUrl?.let { latestFirmwareUrl = it }
        status.sizeBytes?.let {
            latestFirmwareSizeBytes = it
            firmwareFileView.text = "Plik firmware: ${formatBytes(it)}"
        }
        status.status?.let { firmwareStatusText = it }
        status.progress?.let {
            val progress = it.coerceIn(0, 100)
            firmwareProgress.progress = progress
            if (activeUpdateKind == UpdateKind.FIRMWARE) {
                updateDialogProgress?.progress = progress
                updateDialogEtaView?.text = etaText(UpdateKind.FIRMWARE, progress)
            }
        }
        status.updateAvailable?.let { firmwareUpdateAvailable = it }
        status.required?.let { firmwareRequired = it }

        firmwareStatusView.text = buildFirmwareStatusText()
        updateDialogStatusView?.text = firmwareStatusText
        if (firmwareStatusText.startsWith("Blad", ignoreCase = true)) {
            updateDialogInstallButton?.isEnabled = true
        }
        if (shouldDismissFirmwareDialog(status)) {
            dismissUpdateDialog(UpdateKind.FIRMWARE)
        }
        maybeShowFirmwareUpdateDialog()
    }

    private fun updateAppStatus(status: AppBus.Message.AppUpdateStatus) {
        status.currentVersionName?.let { currentAppVersionName = it }
        status.currentVersionCode?.let { currentAppVersionCode = it }
        if (currentAppVersionName != null || currentAppVersionCode != null) {
            appCurrentVersionView.text = "Obecna wersja: ${currentAppVersionName ?: "?"} (${currentAppVersionCode ?: "?"})"
        }
        status.latestVersionName?.let {
            latestAppVersionName = it
            appLatestVersionView.text = "Najnowsza wersja: $it (${status.latestVersionCode ?: latestAppVersionCode ?: "?"})"
        }
        status.latestVersionCode?.let {
            latestAppVersionCode = it
            appLatestVersionView.text = "Najnowsza wersja: ${latestAppVersionName ?: "?"} ($it)"
        }
        status.changelog?.let {
            latestAppChangelog = it
            appChangelogView.text = if (it.isBlank()) "Changelog: brak" else "Changelog:\n$it"
        }
        status.apkUrl?.let { latestAppUrl = it }
        status.sizeBytes?.let {
            latestAppSizeBytes = it
            appFileView.text = "Plik APK: ${formatBytes(it)}"
        }
        status.status?.let { appStatusText = it }
        status.progress?.let {
            val progress = it.coerceIn(0, 100)
            appProgress.progress = progress
            if (activeUpdateKind == UpdateKind.APP) {
                updateDialogProgress?.progress = progress
                updateDialogEtaView?.text = etaText(UpdateKind.APP, progress)
            }
        }
        status.updateAvailable?.let { appUpdateAvailable = it }
        status.required?.let { appRequired = it }

        appStatusView.text = buildAppStatusText()
        updateDialogStatusView?.text = appStatusText
        if (appStatusText.startsWith("Blad", ignoreCase = true)) {
            updateDialogInstallButton?.isEnabled = true
        }
        maybeShowAppUpdateDialog()
    }

    private fun buildFirmwareStatusText(): String {
        return buildString {
            append("Status: ")
            append(firmwareStatusText)
            firmwareUpdateAvailable?.let { append("\nUpdate: ${if (it) "dostepny" else "brak"}") }
            if (firmwareRequired == true) append("\nWymagana aktualizacja.")
            latestFirmwareUrl?.let { append("\nURL: $it") }
        }
    }

    private fun buildAppStatusText(): String {
        return buildString {
            append("Status: ")
            append(appStatusText)
            appUpdateAvailable?.let { append("\nUpdate: ${if (it) "dostepny" else "brak"}") }
            if (appRequired == true) append("\nWymagana aktualizacja.")
            latestAppUrl?.let { append("\nURL: $it") }
        }
    }

    private fun maybeShowFirmwareUpdateDialog() {
        val latestVersion = latestFirmwareVersion ?: return
        if (firmwareUpdateAvailable != true) return
        if (currentEsp32Version.isNullOrBlank()) return
        if (updateDialog?.isShowing == true) return
        if (shownFirmwareDialogVersion == latestVersion) return
        shownFirmwareDialogVersion = latestVersion
        showFirmwareUpdateDialog()
    }

    private fun maybeShowAppUpdateDialog() {
        val latestCode = latestAppVersionCode ?: return
        if (appUpdateAvailable != true) return
        if (updateDialog?.isShowing == true) return
        if (shownAppDialogVersionCode == latestCode) return
        shownAppDialogVersionCode = latestCode
        showAppUpdateDialog()
    }

    private fun showFirmwareUpdateDialog() {
        val modelView = Esp32ModelView(this, palette.surface)
        val content = dialogShell("Nowy firmware ESP32")
        activeUpdateKind = null
        updateDialogKind = UpdateKind.FIRMWARE
        content.addView(modelView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(238)
        ).apply { topMargin = dp(12) })

        content.addView(sectionText(
            "Obecna wersja: ${currentEsp32Version ?: "nie odczytano"}\n" +
                "Nowa wersja: ${latestFirmwareVersion ?: "nieznana"}\n" +
                "Rozmiar: ${latestFirmwareSizeBytes?.let { formatBytes(it) } ?: "?"}"
        ), matchWidth())
        content.addView(sectionText(
            if (latestFirmwareChangelog.isNullOrBlank()) "Changelog: brak" else "Changelog:\n$latestFirmwareChangelog"
        ), matchWidth())
        updateDialogStatusView = sectionText(firmwareStatusText)
        content.addView(updateDialogStatusView, matchWidth())
        updateDialogProgress = progressBar()
        content.addView(updateDialogProgress, matchWidth().apply { topMargin = dp(6) })
        updateDialogEtaView = sectionText("Przewidywany czas: --").apply {
            visibility = View.GONE
        }
        content.addView(updateDialogEtaView, matchWidth())

        val actionButtons = mutableListOf<View>()
        val installButton = actionButton(content, R.drawable.ic_action_install, "Aktualizuj") {
            enterDialogUpdateMode(content, UpdateKind.FIRMWARE, actionButtons) {
                AppBus.publish(AppBus.Message.CancelFirmwareUpdate)
            }
            saveFirmwareUrl(showToast = false)
            updateDialogStatusView?.text = "Startuje aktualizacja"
            AppBus.publish(AppBus.Message.InstallFirmwareUpdate)
        }
        updateDialogInstallButton = installButton
        actionButtons += installButton
        val laterButton = actionButton(content, R.drawable.ic_action_check, "Pozniej") {
            updateDialog?.dismiss()
        }
        actionButtons += laterButton
        showDialog(
            content = content,
            onShow = { modelView.onResume() },
            onDismiss = { modelView.onPause() }
        )
    }

    private fun showAppUpdateDialog() {
        val content = dialogShell("Nowa wersja KESP32")
        activeUpdateKind = null
        updateDialogKind = UpdateKind.APP
        val appIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_action_app)
            setColorFilter(Color.WHITE)
            background = rounded(palette.accent, 0)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(
            appIcon,
            LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        )
        appIcon.scaleX = 0.86f
        appIcon.scaleY = 0.86f
        appIcon.animate().scaleX(1f).scaleY(1f).rotation(4f).setDuration(260L).start()

        content.addView(sectionText(
            "Obecna wersja: ${currentAppVersionName ?: "?"} (${currentAppVersionCode ?: "?"})\n" +
                "Nowa wersja: ${latestAppVersionName ?: "?"} (${latestAppVersionCode ?: "?"})\n" +
                "Rozmiar: ${latestAppSizeBytes?.let { formatBytes(it) } ?: "?"}"
        ), matchWidth())
        content.addView(sectionText(
            if (latestAppChangelog.isNullOrBlank()) "Changelog: brak" else "Changelog:\n$latestAppChangelog"
        ), matchWidth())
        updateDialogStatusView = sectionText(appStatusText)
        content.addView(updateDialogStatusView, matchWidth())
        updateDialogProgress = progressBar()
        content.addView(updateDialogProgress, matchWidth().apply { topMargin = dp(6) })
        updateDialogEtaView = sectionText("Przewidywany czas: --").apply {
            visibility = View.GONE
        }
        content.addView(updateDialogEtaView, matchWidth())

        val actionButtons = mutableListOf<View>()
        val installButton = actionButton(content, R.drawable.ic_action_install, "Aktualizuj aplikacje") {
            enterDialogUpdateMode(content, UpdateKind.APP, actionButtons) {
                AppBus.publish(AppBus.Message.CancelAppUpdate)
            }
            saveAppUpdateUrl(showToast = false)
            updateDialogStatusView?.text = "Pobieram APK"
            appUpdater.installLatest()
        }
        updateDialogInstallButton = installButton
        actionButtons += installButton
        val laterButton = actionButton(content, R.drawable.ic_action_check, "Pozniej") {
            updateDialog?.dismiss()
        }
        actionButtons += laterButton
        showDialog(content)
    }

    private fun enterDialogUpdateMode(
        content: LinearLayout,
        kind: UpdateKind,
        hideButtons: List<View>,
        onCancel: () -> Unit
    ) {
        activeUpdateKind = kind
        val now = System.currentTimeMillis()
        if (kind == UpdateKind.FIRMWARE) {
            firmwareProgressStartedAt = now
        } else {
            appProgressStartedAt = now
        }
        hideButtons.forEach { it.visibility = View.GONE }
        updateDialogEtaView?.visibility = View.VISIBLE
        updateDialogEtaView?.text = "Przewidywany czas: licze..."
        updateDialogInstallButton = null
        actionButton(content, R.drawable.ic_action_check, "Anuluj") {
            onCancel()
            updateDialog?.dismiss()
        }
    }

    private fun dialogShell(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(palette.surface, 0)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.text)
                gravity = Gravity.CENTER
            }, matchWidth())
        }
    }

    private fun showDialog(content: LinearLayout, onShow: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .create()
        updateDialog = dialog
        dialog.setOnShowListener {
            onShow()
            content.alpha = 0f
            content.translationY = dp(18).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(220L).start()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.setOnDismissListener {
            onDismiss()
            if (updateDialog === dialog) {
                updateDialog = null
                updateDialogStatusView = null
                updateDialogProgress = null
                updateDialogInstallButton = null
                updateDialogEtaView = null
                activeUpdateKind = null
                updateDialogKind = null
            }
        }
        dialog.show()
    }

    private fun shouldDismissFirmwareDialog(status: AppBus.Message.FirmwareStatus): Boolean {
        if (updateDialogKind != UpdateKind.FIRMWARE || updateDialog?.isShowing != true) {
            return false
        }
        val finished = status.status?.startsWith("Update OK", ignoreCase = true) == true
        val noLongerAvailable = status.updateAvailable == false
        val currentVersion = currentEsp32Version
        val latestVersion = latestFirmwareVersion
        val alreadyCurrent = currentVersion != null &&
            latestVersion != null &&
            compareVersions(currentVersion, latestVersion) >= 0
        return finished || noLongerAvailable || alreadyCurrent
    }

    private fun dismissUpdateDialog(kind: UpdateKind) {
        if (updateDialogKind == kind) {
            updateDialog?.dismiss()
        }
    }

    private fun animateThemeChange(dark: Boolean) {
        if (settings.darkThemeEnabled == dark) {
            return
        }
        rootView.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                settings.darkThemeEnabled = dark
                recreate()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            .start()
    }

    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Aplikacja juz nie jest usypiana", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun updatePermissionStatus() {
        val missing = runtimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        val notificationAccess = isNotificationListenerEnabled()
        val batteryIgnored = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        permissionView.text = buildString {
            appendLine("Uprawnienia: ${if (missing.isEmpty()) "OK" else "brakuje ${missing.size}"}")
            appendLine("Dostep do powiadomien: ${if (notificationAccess) "OK" else "brak"}")
            append("Bateria: ${if (batteryIgnored) "nie usypiaj" else "domyslnie"}")
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun hasRuntimePermissions(): Boolean {
        return runtimePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(runtimePermissions().toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun runtimePermissions(): List<String> {
        val permissions = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissions += Manifest.permission.READ_PHONE_STATE
        permissions += Manifest.permission.READ_CALL_LOG
        return permissions.toList()
    }

    private fun actionButton(root: LinearLayout, iconRes: Int, text: String, onClick: () -> Unit): LinearLayout {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(palette.accent, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().scaleX(0.98f).scaleY(0.98f).setDuration(70L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                }.start()
                onClick()
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
        }
        val label = TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        button.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        button.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(9)
        })
        root.addView(button, matchWidth().apply {
            topMargin = dp(8)
            height = dp(46)
        })
        return button
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            setPadding(0, dp(2), 0, dp(8))
        }
    }

    private fun sectionText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(palette.mutedText)
            setPadding(0, dp(6), 0, dp(6))
        }
    }

    private fun themedCheckBox(text: String): CheckBox {
        return CheckBox(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        }
    }

    private fun themedEditText(hintText: String): EditText {
        return EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = hintText
            textSize = 13f
            setTextColor(palette.text)
            setHintTextColor(palette.faintText)
            background = rounded(palette.field, palette.border)
            setPadding(dp(12), 0, dp(12), 0)
        }
    }

    private fun progressBar(): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.border)
        }
    }

    private fun appendLog(text: String) {
        logLines.addFirst(text)
        while (logLines.size > 12) {
            logLines.removeLast()
        }
        logView.text = "Zdarzenia:\n" + logLines.joinToString("\n")
    }

    private fun tabRoot(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(palette.surface, palette.border)
        }
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(fill)
            if (stroke != 0) {
                setStroke(dp(1), stroke)
            }
        }
    }

    private fun widgetName(type: WidgetType): String {
        return when (type) {
            WidgetType.CALL -> "Polaczenie"
            WidgetType.NAVIGATION -> "Nawigacja"
            WidgetType.NOTIFICATION -> "Powiadomienia"
            WidgetType.MUSIC -> "Muzyka"
            WidgetType.BATTERY -> "Bateria"
            WidgetType.WEATHER -> "Pogoda"
            WidgetType.WIFI -> "WiFi"
            WidgetType.STORAGE -> "Pamiec plikow"
            WidgetType.MEMORY -> "RAM"
            WidgetType.ALARM -> "Alarm"
            WidgetType.SYSTEM -> "Telefon"
            WidgetType.HOME -> "Ekran glowny"
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val count = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until count) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> {
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '_', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            .ifEmpty { listOf(0) }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB", mb)
    }

    private fun etaText(kind: UpdateKind, progress: Int): String {
        if (progress >= 100) {
            return "Przewidywany czas: zakonczono"
        }
        if (progress <= 0) {
            return "Przewidywany czas: licze..."
        }
        val now = System.currentTimeMillis()
        val startedAt = when (kind) {
            UpdateKind.FIRMWARE -> firmwareProgressStartedAt
            UpdateKind.APP -> appProgressStartedAt
        }.takeIf { it > 0L } ?: now
        val elapsed = (now - startedAt).coerceAtLeast(1L)
        val total = (elapsed.toDouble() * 100.0 / progress.toDouble()).roundToLong()
        val remaining = (total - elapsed).coerceAtLeast(0L)
        return "Przewidywany czas: ${formatDuration(remaining)}"
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val rest = seconds % 60L
        return if (minutes > 0L) {
            "${minutes} min ${rest} s"
        } else {
            "${rest} s"
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun spaced(): LinearLayout.LayoutParams {
        return matchWidth().apply {
            topMargin = dp(12)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class UiPalette(
        val background: Int,
        val surface: Int,
        val field: Int,
        val text: Int,
        val mutedText: Int,
        val faintText: Int,
        val border: Int,
        val accent: Int,
        val accentSoft: Int
    ) {
        companion object {
            fun from(dark: Boolean): UiPalette {
                return if (dark) {
                    UiPalette(
                        background = Color.rgb(10, 12, 15),
                        surface = Color.rgb(22, 25, 30),
                        field = Color.rgb(16, 19, 24),
                        text = Color.rgb(244, 247, 250),
                        mutedText = Color.rgb(178, 186, 198),
                        faintText = Color.rgb(112, 121, 136),
                        border = Color.rgb(43, 49, 59),
                        accent = Color.rgb(24, 190, 178),
                        accentSoft = Color.rgb(17, 48, 52)
                    )
                } else {
                    UiPalette(
                        background = Color.rgb(247, 248, 250),
                        surface = Color.WHITE,
                        field = Color.rgb(250, 251, 252),
                        text = Color.rgb(19, 25, 34),
                        mutedText = Color.rgb(83, 92, 107),
                        faintText = Color.rgb(146, 154, 168),
                        border = Color.rgb(226, 231, 238),
                        accent = Color.rgb(14, 124, 123),
                        accentSoft = Color.rgb(225, 247, 245)
                    )
                }
            }
        }
    }

    private enum class AppTab(val label: String, val iconRes: Int) {
        STATUS("Status", R.drawable.ic_tab_status),
        WIDGETS("Widgety", R.drawable.ic_tab_widgets),
        SETTINGS("Ustaw.", R.drawable.ic_tab_settings),
        UPDATE("Update", R.drawable.ic_tab_update),
        LOGS("Logi", R.drawable.ic_tab_logs)
    }

    private enum class UpdateKind {
        FIRMWARE,
        APP
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}

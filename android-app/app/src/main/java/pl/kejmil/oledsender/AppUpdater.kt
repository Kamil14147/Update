package pl.kejmil.oledsender

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class AppUpdater(
    context: Context,
    private val settings: SettingsStore,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var latestManifest: AppUpdateManifest? = null
    private var downloadedApk: File? = null
    private var checkInProgress = false
    private var installInProgress = false

    fun publishCurrentVersion() {
        publish(
            AppBus.Message.AppUpdateStatus(
                currentVersionName = currentVersionName(),
                currentVersionCode = currentVersionCode()
            )
        )
    }

    fun checkForUpdates(automatic: Boolean) {
        if (checkInProgress) {
            return
        }
        checkInProgress = true
        downloadedApk = null
        publishCurrentVersion()
        publish(
            AppBus.Message.AppUpdateStatus(
                status = if (automatic) "Sprawdzam aktualizacje aplikacji" else "Pobieram manifest aplikacji",
                progress = 0
            )
        )

        executor.execute {
            try {
                val text = fetchText(settings.appUpdateManifestUrl)
                val manifest = AppUpdateManifest.fromJson(text)
                if (manifest.packageName != appContext.packageName) {
                    throw IllegalArgumentException("Manifest jest dla innej aplikacji: ${manifest.packageName}")
                }
                latestManifest = manifest
                val updateAvailable = manifest.versionCode > currentVersionCode()
                val status = if (updateAvailable) {
                    "Dostepna aktualizacja aplikacji ${manifest.versionName}"
                } else {
                    "Aplikacja jest aktualna"
                }
                publish(
                    AppBus.Message.AppUpdateStatus(
                        currentVersionName = currentVersionName(),
                        currentVersionCode = currentVersionCode(),
                        latestVersionName = manifest.versionName,
                        latestVersionCode = manifest.versionCode,
                        changelog = manifest.changelog,
                        apkUrl = manifest.apkUrl,
                        sizeBytes = manifest.sizeBytes,
                        status = status,
                        progress = 0,
                        updateAvailable = updateAvailable,
                        required = manifest.required
                    )
                )
                onLog("App manifest OK: ${manifest.versionName} (${manifest.versionCode})")
            } catch (error: Exception) {
                publish(
                    AppBus.Message.AppUpdateStatus(
                        status = "Blad sprawdzania aplikacji: ${error.message}",
                        progress = 0
                    )
                )
                onLog("App update check failed: ${error.message}")
            } finally {
                checkInProgress = false
            }
        }
    }

    fun installLatest() {
        if (installInProgress) {
            publish(AppBus.Message.AppUpdateStatus(status = "Aktualizacja aplikacji juz trwa"))
            return
        }
        val manifest = latestManifest
        if (manifest == null) {
            publish(AppBus.Message.AppUpdateStatus(status = "Najpierw kliknij Check app"))
            return
        }
        if (manifest.versionCode <= currentVersionCode() && !manifest.required) {
            publish(AppBus.Message.AppUpdateStatus(status = "Ta wersja aplikacji nie jest nowsza"))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            publish(
                AppBus.Message.AppUpdateStatus(
                    status = "Zezwol na instalowanie aplikacji i wroc do KEPS32v1",
                    progress = 0
                )
            )
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return
        }

        installInProgress = true
        publish(AppBus.Message.AppUpdateStatus(status = "Pobieram APK", progress = 0))
        executor.execute {
            try {
                val apkFile = downloadedApk ?: downloadAndVerify(manifest)
                downloadedApk = apkFile
                publish(AppBus.Message.AppUpdateStatus(status = "Otwieram instalator Androida", progress = 100))
                handler.post {
                    openInstaller(apkFile)
                    installInProgress = false
                }
            } catch (error: Exception) {
                publish(
                    AppBus.Message.AppUpdateStatus(
                        status = "Blad aktualizacji aplikacji: ${error.message}",
                        progress = 0
                    )
                )
                onLog("App update failed: ${error.message}")
                installInProgress = false
            }
        }
    }

    private fun downloadAndVerify(manifest: AppUpdateManifest): File {
        val bytes = fetchBytes(manifest.apkUrl, manifest.sizeBytes)
        if (bytes.size.toLong() != manifest.sizeBytes) {
            throw IllegalStateException("Rozmiar APK: ${bytes.size}, oczekiwano ${manifest.sizeBytes}")
        }
        val actualSha = sha256(bytes)
        if (!actualSha.equals(manifest.sha256, ignoreCase = true)) {
            throw IllegalStateException("SHA-256 APK nie zgadza sie z manifestem")
        }
        val dir = File(appContext.cacheDir, ApkProvider.UPDATE_DIR).apply { mkdirs() }
        val safeVersion = manifest.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "KEPS32v1-$safeVersion.apk")
        file.writeBytes(bytes)
        return file
    }

    private fun openInstaller(apkFile: File) {
        val uri = ApkProvider.uriFor(appContext, apkFile.name)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        appContext.startActivity(intent)
    }

    private fun fetchText(url: String): String {
        return openConnection(url).useConnection { connection ->
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun fetchBytes(url: String, expectedSize: Long): ByteArray {
        return openConnection(url).useConnection { connection ->
            val reportedLength = connection.contentLengthLong
            if (reportedLength > MAX_APK_BYTES) {
                throw IllegalStateException("APK jest za duzy: $reportedLength bajtow")
            }
            if (reportedLength > 0 && expectedSize > 0 && reportedLength != expectedSize) {
                throw IllegalStateException("Serwer zwrocil inny rozmiar APK")
            }

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_APK_BYTES) {
                        throw IllegalStateException("APK przekracza limit bezpieczenstwa")
                    }
                    output.write(buffer, 0, read)
                    if (expectedSize > 0) {
                        val progress = ((total.toDouble() / expectedSize.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                        publish(
                            AppBus.Message.AppUpdateStatus(
                                status = "Pobieram APK",
                                progress = progress
                            )
                        )
                    }
                }
            }
            output.toByteArray()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Manifest i APK musza byc pobierane przez HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "KEPS32v1/${appContext.packageName}")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        return connection
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private fun publish(message: AppBus.Message.AppUpdateStatus) {
        handler.post { AppBus.publish(message) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionName(): String {
        return appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    companion object {
        private const val HTTP_TIMEOUT_MS = 15000
        private const val MAX_APK_BYTES = 80L * 1024L * 1024L
    }
}

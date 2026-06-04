package pl.kejmil.oledsender

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class FirmwareUpdater(
    context: Context,
    private val settings: SettingsStore,
    private val onLog: (String) -> Unit,
    private val onFirmwareReady: (FirmwareManifest, ByteArray) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var latestManifest: FirmwareManifest? = null
    private var currentEsp32Version: String? = null
    private var checkInProgress = false
    private var installInProgress = false
    @Volatile private var cancelRequested = false

    fun setCurrentEsp32Version(version: String) {
        currentEsp32Version = version
        publish(AppBus.Message.FirmwareStatus(esp32Version = version))
    }

    fun checkForUpdates(automatic: Boolean) {
        if (checkInProgress) {
            return
        }
        checkInProgress = true
        val manifestUrl = settings.firmwareManifestUrl
        publish(
            AppBus.Message.FirmwareStatus(
                status = if (automatic) "Sprawdzam aktualizacje przy starcie" else "Pobieram manifest z GitHuba",
                progress = null
            )
        )

        executor.execute {
            try {
                val text = fetchText(manifestUrl)
                val manifest = FirmwareManifest.fromJson(text)
                if (manifest.device != FirmwareManifest.DEVICE_ID) {
                    throw IllegalArgumentException("Manifest jest dla innego urzadzenia: ${manifest.device}")
                }
                latestManifest = manifest
                val current = currentEsp32Version
                val updateAvailable = current == null || compareVersions(manifest.version, current) > 0
                val status = when {
                    current == null -> "Manifest pobrany. Polacz ESP32, zeby porownac wersje."
                    updateAvailable -> "Dostepna aktualizacja ${manifest.version}"
                    else -> "Firmware ESP32 jest aktualny"
                }
                publish(
                    AppBus.Message.FirmwareStatus(
                        latestVersion = manifest.version,
                        changelog = manifest.changelog,
                        firmwareUrl = manifest.firmwareUrl,
                        sizeBytes = manifest.sizeBytes,
                        status = status,
                        progress = 0,
                        updateAvailable = updateAvailable,
                        required = manifest.required
                    )
                )
                onLog("Firmware manifest OK: ${manifest.version}")
            } catch (error: Exception) {
                publish(
                    AppBus.Message.FirmwareStatus(
                        status = "Blad sprawdzania aktualizacji: ${error.message}",
                        progress = 0
                    )
                )
                onLog("Firmware check failed: ${error.message}")
            } finally {
                checkInProgress = false
            }
        }
    }

    fun installLatest() {
        if (installInProgress) {
            publish(AppBus.Message.FirmwareStatus(status = "Aktualizacja juz trwa"))
            return
        }
        val manifest = latestManifest
        if (manifest == null) {
            publish(AppBus.Message.FirmwareStatus(status = "Najpierw kliknij Sprawdz aktualizacje"))
            return
        }
        val current = currentEsp32Version
        if (current != null && compareVersions(manifest.version, current) <= 0 && !manifest.required) {
            publish(AppBus.Message.FirmwareStatus(status = "Ta wersja nie jest nowsza od ESP32"))
            return
        }

        installInProgress = true
        cancelRequested = false
        publish(AppBus.Message.FirmwareStatus(status = "Pobieram firmware z GitHuba", progress = 0))
        executor.execute {
            try {
                val bytes = fetchBytes(manifest.firmwareUrl, manifest.sizeBytes)
                if (cancelRequested) {
                    throw InterruptedException("anulowano")
                }
                if (bytes.size.toLong() != manifest.sizeBytes) {
                    throw IllegalStateException("Rozmiar pliku: ${bytes.size}, oczekiwano ${manifest.sizeBytes}")
                }
                val actualSha = sha256(bytes)
                if (!actualSha.equals(manifest.sha256, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 nie zgadza sie z manifestem")
                }
                publish(AppBus.Message.FirmwareStatus(status = "Plik poprawny, start OTA", progress = 0))
                handler.post {
                    onFirmwareReady(manifest, bytes)
                }
            } catch (error: Exception) {
                publish(
                    AppBus.Message.FirmwareStatus(
                        status = "Blad aktualizacji: ${error.message}",
                        progress = 0
                    )
                )
                onLog("Firmware install failed: ${error.message}")
                installInProgress = false
                cancelRequested = false
            }
        }
    }

    fun cancelInstall() {
        if (!installInProgress) {
            publish(AppBus.Message.FirmwareStatus(status = "Brak aktywnej aktualizacji firmware", progress = 0))
            return
        }
        cancelRequested = true
        installInProgress = false
        publish(AppBus.Message.FirmwareStatus(status = "Aktualizacja firmware anulowana", progress = 0))
    }

    fun markBleTransferFinished() {
        installInProgress = false
        cancelRequested = false
    }

    private fun fetchText(url: String): String {
        return openConnection(url).useConnection { connection ->
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun fetchBytes(url: String, expectedSize: Long): ByteArray {
        return openConnection(url).useConnection { connection ->
            val reportedLength = connection.contentLengthLong
            if (reportedLength > MAX_FIRMWARE_BYTES) {
                throw IllegalStateException("Plik jest za duzy: $reportedLength bajtow")
            }
            if (reportedLength > 0 && expectedSize > 0 && reportedLength != expectedSize) {
                throw IllegalStateException("Serwer zwrocil inny rozmiar pliku")
            }

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            connection.inputStream.use { input ->
                while (true) {
                    if (cancelRequested) {
                        throw InterruptedException("anulowano")
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    total += read
                    if (total > MAX_FIRMWARE_BYTES) {
                        throw IllegalStateException("Firmware przekracza limit bezpieczenstwa")
                    }
                    output.write(buffer, 0, read)
                    if (expectedSize > 0) {
                        val progress = ((total.toDouble() / expectedSize.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                        publish(
                            AppBus.Message.FirmwareStatus(
                                status = "Pobieram firmware",
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
        require(url.startsWith("https://")) { "Manifest i firmware musza byc pobierane przez HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "KejmilOLED/${appContext.packageName}")
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

    private fun publish(message: AppBus.Message.FirmwareStatus) {
        handler.post { AppBus.publish(message) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val count = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until count) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    companion object {
        private const val HTTP_TIMEOUT_MS = 15000
        private const val MAX_FIRMWARE_BYTES = 4L * 1024L * 1024L
    }
}

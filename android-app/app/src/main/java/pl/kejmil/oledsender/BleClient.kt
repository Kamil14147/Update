package pl.kejmil.oledsender

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

class BleClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onFirmwareMessage: (String) -> Unit,
    private val onFirmwareTransferFinished: () -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val operations = ArrayDeque<BleOperation>()
    private val uartInput = StringBuilder()
    private val otaInput = StringBuilder()

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var uartRx: BluetoothGattCharacteristic? = null
    private var uartTx: BluetoothGattCharacteristic? = null
    private var otaControl: BluetoothGattCharacteristic? = null
    private var otaData: BluetoothGattCharacteristic? = null
    private var activeOperation: BleOperation? = null
    private var scanning = false
    private var started = false
    private var payloadSize = 20
    private var reconnectDelayMs = 5000L
    private var reconnectScheduled = false
    private var lastStatus = ""
    private var otaTransfer: OtaTransfer? = null

    fun start() {
        started = true
        ensureAdapter()
        reconnectNow()
    }

    fun stop() {
        started = false
        stopScan()
        closeGatt()
        handler.removeCallbacksAndMessages(null)
        setStatus("BLE zatrzymane")
    }

    fun reconnectNow() {
        if (!started) {
            started = true
        }
        stopScan()
        closeGatt()
        startScan()
    }

    fun send(json: String) {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) {
            onLog("JSON za dlugi dla limitu ESP32: ${json.length} znakow")
            return
        }
        if (otaTransfer != null) {
            onLog("Widget pominiety: trwa aktualizacja firmware")
            return
        }

        val characteristic = uartRx
        if (characteristic == null || gatt == null) {
            onLog("BLE niepolaczone, JSON pominiety")
            return
        }

        enqueueCharacteristicChunks(
            characteristic = characteristic,
            bytes = (json + "\n").toByteArray(StandardCharsets.UTF_8),
            description = "widget"
        )
    }

    fun requestFirmwareVersion() {
        sendOtaControl(JSONObject().put("cmd", "version"))
    }

    fun startFirmwareUpdate(manifest: FirmwareManifest, firmware: ByteArray) {
        val control = otaControl
        val data = otaData
        if (gatt == null || control == null || data == null) {
            publishOtaStatus("Blad: ESP32 nie ma gotowego serwisu OTA", progress = 0)
            onFirmwareTransferFinished()
            return
        }
        if (otaTransfer != null) {
            publishOtaStatus("Aktualizacja juz trwa", progress = null)
            return
        }
        if (firmware.size.toLong() != manifest.sizeBytes) {
            publishOtaStatus("Blad: rozmiar firmware nie zgadza sie z manifestem", progress = 0)
            onFirmwareTransferFinished()
            return
        }

        otaTransfer = OtaTransfer(manifest = manifest, firmware = firmware)
        operations.clear()
        publishOtaStatus("Przygotowuje ESP32 do OTA", progress = 0)

        val begin = JSONObject()
            .put("cmd", "begin")
            .put("device", manifest.device)
            .put("version", manifest.version)
            .put("size", firmware.size)
            .put("sha256", manifest.sha256)

        enqueueOperation(
            BleOperation.CharacteristicWrite(
                characteristic = control,
                payload = (begin.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                description = "ota begin",
                afterWrite = { sendNextOtaChunk() }
            )
        )
    }

    private fun sendOtaControl(json: JSONObject) {
        val control = otaControl
        if (control == null || gatt == null) {
            onLog("OTA control niedostepny")
            return
        }
        enqueueOperation(
            BleOperation.CharacteristicWrite(
                characteristic = control,
                payload = (json.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                description = "ota control"
            )
        )
    }

    private fun sendNextOtaChunk() {
        val transfer = otaTransfer ?: return
        val data = otaData ?: return

        if (transfer.offset >= transfer.firmware.size) {
            val finish = JSONObject().put("cmd", "finish")
            enqueueOperation(
                BleOperation.CharacteristicWrite(
                    characteristic = otaControl ?: return,
                    payload = (finish.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    description = "ota finish"
                )
            )
            publishOtaStatus("Firmware wyslany, ESP32 sprawdza plik", progress = 100)
            return
        }

        val end = minOf(transfer.firmware.size, transfer.offset + minOf(payloadSize, OTA_CHUNK_LIMIT))
        val chunk = transfer.firmware.copyOfRange(transfer.offset, end)
        transfer.offset = end
        val progress = ((transfer.offset.toDouble() / transfer.firmware.size.toDouble()) * 100.0).toInt()
            .coerceIn(0, 100)
        if (progress != transfer.lastProgress) {
            transfer.lastProgress = progress
            publishOtaStatus("Wysylam firmware przez BLE", progress)
        }

        enqueueOperation(
            BleOperation.CharacteristicWrite(
                characteristic = data,
                payload = chunk,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                description = "ota data",
                afterWrite = { sendNextOtaChunk() }
            )
        )
    }

    private fun enqueueCharacteristicChunks(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        description: String
    ) {
        var offset = 0
        var parts = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + payloadSize)
            enqueueOperation(
                BleOperation.CharacteristicWrite(
                    characteristic = characteristic,
                    payload = bytes.copyOfRange(offset, end),
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    description = description
                )
            )
            parts++
            offset = end
        }
        onLog("Wysylam ${bytes.size} bajtow BLE w $parts czesciach")
    }

    private fun ensureAdapter() {
        if (adapter != null) {
            return
        }
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        ensureAdapter()
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth niedostepny")
            return
        }
        if (!hasBlePermissions()) {
            setStatus("Brak uprawnien Bluetooth")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            setStatus("Bluetooth wylaczony")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            setStatus("Skaner BLE niedostepny")
            scheduleReconnect()
            return
        }

        scanning = true
        reconnectScheduled = false
        setStatus("Szukam $DEVICE_NAME")
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UART_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        handler.postDelayed({
            if (scanning) {
                stopScan()
                setStatus("Nie znaleziono ESP32")
                scheduleReconnect()
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning || !hasBlePermissions()) {
            scanning = false
            return
        }
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedName = result.scanRecord?.deviceName
            val deviceName = safeDeviceName(result.device)
            if (advertisedName == DEVICE_NAME || deviceName == DEVICE_NAME) {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("Blad skanowania BLE: $errorCode")
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? {
        return if (hasBlePermissions()) device.name else null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasBlePermissions()) {
            setStatus("Brak uprawnienia polaczenia BLE")
            scheduleReconnect()
            return
        }
        setStatus("Lacze z $DEVICE_NAME")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectDelayMs = 5000L
                setStatus("BLE polaczone")
                requestMtu(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelOta("BLE rozlaczone w trakcie aktualizacji")
                if (hasBlePermissions()) {
                    gatt.close()
                }
                clearConnectionState()
                setStatus("BLE rozlaczone")
                scheduleReconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                payloadSize = maxOf(20, mtu - 3)
                onLog("BLE MTU $mtu, payload $payloadSize")
            }
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Blad uslug BLE: $status")
                scheduleReconnect()
                return
            }

            val uartService = gatt.getService(UART_SERVICE_UUID)
            uartRx = uartService?.getCharacteristic(UART_RX_UUID)
            uartTx = uartService?.getCharacteristic(UART_TX_UUID)

            val otaService = gatt.getService(OTA_SERVICE_UUID)
            otaControl = otaService?.getCharacteristic(OTA_CONTROL_UUID)
            otaData = otaService?.getCharacteristic(OTA_DATA_UUID)

            if (uartRx == null) {
                setStatus("Brak UART RX na ESP32")
                scheduleReconnect()
                return
            }
            if (otaControl == null || otaData == null) {
                setStatus("Gotowe bez OTA: $DEVICE_NAME")
                onLog("ESP32 nie udostepnia serwisu OTA")
            } else {
                setStatus("Gotowe: $DEVICE_NAME")
            }

            uartTx?.let { enableNotifications(it, "uart tx") }
            otaControl?.let { enableNotifications(it, "ota control") }
            requestFirmwareVersion()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val completed = activeOperation as? BleOperation.CharacteristicWrite
            activeOperation = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completed?.afterWrite?.invoke()
            } else {
                val description = completed?.description ?: "unknown"
                onLog("Blad zapisu BLE ($description): $status")
                if (description.startsWith("ota")) {
                    cancelOta("Blad zapisu BLE: $status")
                }
            }
            writeNextOperation()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val completed = activeOperation as? BleOperation.DescriptorWrite
            activeOperation = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Blad wlaczania notify (${completed?.description ?: "unknown"}): $status")
            }
            writeNextOperation()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestMtu(currentGatt: BluetoothGatt) {
        if (!hasBlePermissions()) {
            scheduleReconnect()
            return
        }
        if (!currentGatt.requestMtu(185)) {
            discoverServices(currentGatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(currentGatt: BluetoothGatt) {
        if (hasBlePermissions()) {
            currentGatt.discoverServices()
        }
    }

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic, description: String) {
        val currentGatt = gatt ?: return
        if (!hasBlePermissions()) {
            return
        }
        @SuppressLint("MissingPermission")
        currentGatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
        enqueueOperation(
            BleOperation.DescriptorWrite(
                descriptor = descriptor,
                payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                description = description
            )
        )
    }

    private fun enqueueOperation(operation: BleOperation) {
        operations.add(operation)
        writeNextOperation()
    }

    @SuppressLint("MissingPermission")
    private fun writeNextOperation() {
        if (activeOperation != null) {
            return
        }
        val operation = operations.poll() ?: return
        val currentGatt = gatt
        if (currentGatt == null || !hasBlePermissions()) {
            operations.clear()
            activeOperation = null
            return
        }

        activeOperation = operation
        when (operation) {
            is BleOperation.CharacteristicWrite -> {
                operation.characteristic.writeType = operation.writeType
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    currentGatt.writeCharacteristic(
                        operation.characteristic,
                        operation.payload,
                        operation.writeType
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    operation.characteristic.value = operation.payload
                    @Suppress("DEPRECATION")
                    currentGatt.writeCharacteristic(operation.characteristic)
                }
                if (!started) {
                    activeOperation = null
                    onLog("Start zapisu BLE nieudany: ${operation.description}")
                    if (operation.description.startsWith("ota")) {
                        cancelOta("Start zapisu BLE nieudany")
                    }
                    writeNextOperation()
                }
            }
            is BleOperation.DescriptorWrite -> {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    currentGatt.writeDescriptor(operation.descriptor, operation.payload) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    operation.descriptor.value = operation.payload
                    @Suppress("DEPRECATION")
                    currentGatt.writeDescriptor(operation.descriptor)
                }
                if (!started) {
                    activeOperation = null
                    onLog("Start descriptor write nieudany: ${operation.description}")
                    writeNextOperation()
                }
            }
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) {
            return
        }
        val builder = when (uuid) {
            UART_TX_UUID -> uartInput
            OTA_CONTROL_UUID -> otaInput
            else -> return
        }
        builder.append(String(value, StandardCharsets.UTF_8))
        while (true) {
            val newline = builder.indexOf("\n")
            if (newline < 0) {
                break
            }
            val line = builder.substring(0, newline).trim()
            builder.delete(0, newline + 1)
            if (line.isNotBlank()) {
                handleIncomingLine(line)
            }
        }
    }

    private fun handleIncomingLine(line: String) {
        onLog("ESP32: $line")
        if (line.contains("\"event\":\"version\"") || line.contains("\"event\":\"ota\"")) {
            if (line.contains("\"event\":\"ota\"") &&
                (line.contains("\"state\":\"success\"") || line.contains("\"state\":\"error\""))
            ) {
                otaTransfer = null
                onFirmwareTransferFinished()
            }
            onFirmwareMessage(line)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        cancelOta("Polaczenie BLE zamkniete")
        if (hasBlePermissions()) {
            gatt?.close()
        }
        clearConnectionState()
    }

    private fun clearConnectionState() {
        gatt = null
        uartRx = null
        uartTx = null
        otaControl = null
        otaData = null
        activeOperation = null
        operations.clear()
        uartInput.clear()
        otaInput.clear()
    }

    private fun scheduleReconnect() {
        if (!started) {
            return
        }
        if (reconnectScheduled) {
            return
        }
        reconnectScheduled = true
        val delay = reconnectDelayMs
        reconnectDelayMs = minOf(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS)
        onLog("Ponowne polaczenie za ${delay / 1000}s")
        handler.postDelayed({
            reconnectScheduled = false
            if (started) startScan()
        }, delay)
    }

    private fun setStatus(status: String) {
        if (status == lastStatus) {
            return
        }
        lastStatus = status
        onStatus(status)
    }

    private fun cancelOta(reason: String) {
        if (otaTransfer == null) {
            return
        }
        otaTransfer = null
        onFirmwareTransferFinished()
        operations.removeAll { operation ->
            operation.description.startsWith("ota")
        }
        publishOtaStatus(reason, progress = 0)
    }

    private fun publishOtaStatus(text: String, progress: Int?) {
        AppBus.publish(
            AppBus.Message.FirmwareStatus(
                status = text,
                progress = progress
            )
        )
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private sealed class BleOperation(open val description: String) {
        data class CharacteristicWrite(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray,
            val writeType: Int,
            override val description: String,
            val afterWrite: (() -> Unit)? = null
        ) : BleOperation(description)

        data class DescriptorWrite(
            val descriptor: BluetoothGattDescriptor,
            val payload: ByteArray,
            override val description: String
        ) : BleOperation(description)
    }

    private data class OtaTransfer(
        val manifest: FirmwareManifest,
        val firmware: ByteArray,
        var offset: Int = 0,
        var lastProgress: Int = -1
    )

    companion object {
        private const val DEVICE_NAME = "Kejmil OLED"
        private const val SCAN_TIMEOUT_MS = 8000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val MAX_JSON_BYTES = 768
        private const val OTA_CHUNK_LIMIT = 180
        private val UART_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val UART_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val UART_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val OTA_SERVICE_UUID: UUID = UUID.fromString("f00d0001-8b7a-4d2a-9c2f-4f4553503332")
        private val OTA_CONTROL_UUID: UUID = UUID.fromString("f00d0002-8b7a-4d2a-9c2f-4f4553503332")
        private val OTA_DATA_UUID: UUID = UUID.fromString("f00d0003-8b7a-4d2a-9c2f-4f4553503332")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

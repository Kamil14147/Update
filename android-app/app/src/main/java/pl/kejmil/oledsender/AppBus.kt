package pl.kejmil.oledsender

import java.util.concurrent.CopyOnWriteArraySet

object AppBus {
    sealed class Message {
        data class SubmitWidget(val event: WidgetEvent) : Message()
        data class ClearSource(val sourceKey: String) : Message()
        data class ClearType(val type: WidgetType) : Message()
        data class Status(
            val bleStatus: String? = null,
            val activeWidget: String? = null,
            val lastJson: String? = null
        ) : Message()
        data class FirmwareStatus(
            val esp32Version: String? = null,
            val latestVersion: String? = null,
            val changelog: String? = null,
            val firmwareUrl: String? = null,
            val sizeBytes: Long? = null,
            val status: String? = null,
            val progress: Int? = null,
            val updateAvailable: Boolean? = null,
            val required: Boolean? = null
        ) : Message()
        data class AppUpdateStatus(
            val currentVersionName: String? = null,
            val currentVersionCode: Long? = null,
            val latestVersionName: String? = null,
            val latestVersionCode: Long? = null,
            val changelog: String? = null,
            val apkUrl: String? = null,
            val sizeBytes: Long? = null,
            val status: String? = null,
            val progress: Int? = null,
            val updateAvailable: Boolean? = null,
            val required: Boolean? = null
        ) : Message()
        data class Log(val text: String) : Message()
        data object Reconnect : Message()
        data object RequestFirmwareVersion : Message()
        data object CheckFirmwareUpdates : Message()
        data object InstallFirmwareUpdate : Message()
        data object CancelFirmwareUpdate : Message()
        data object CheckAppUpdates : Message()
        data object InstallAppUpdate : Message()
        data object CancelAppUpdate : Message()
        data object RefreshNotificationSources : Message()
        data class DebugJson(val json: String) : Message()
    }

    private val listeners = CopyOnWriteArraySet<(Message) -> Unit>()

    fun subscribe(listener: (Message) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (Message) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(message: Message) {
        listeners.forEach { listener -> listener(message) }
    }

    fun log(text: String) {
        publish(Message.Log(text))
    }
}

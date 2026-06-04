package pl.kejmil.oledsender

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class KejmilNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        AppBus.log("Notification Listener connected")
        activeNotifications?.forEach { sbn -> handlePosted(sbn) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handlePosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "nav")))
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "notification")))
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "weather")))
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "call")))
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val event = SystemEventParsers.parseNotification(this, sbn) ?: return
        AppBus.publish(AppBus.Message.SubmitWidget(event))
        AppBus.log("Notification event: ${event.type.wireType} from ${sbn.packageName}")
    }
}

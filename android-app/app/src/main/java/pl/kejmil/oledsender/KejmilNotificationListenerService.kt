package pl.kejmil.oledsender

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

class KejmilNotificationListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        activeListener = WeakReference(this)
    }

    override fun onListenerConnected() {
        activeListener = WeakReference(this)
        AppBus.log("Notification Listener connected")
        refreshActiveNotifications("listener connected")
    }

    override fun onListenerDisconnected() {
        AppBus.log("Notification Listener disconnected")
        requestRebind(this)
    }

    override fun onDestroy() {
        if (activeListener?.get() === this) {
            activeListener = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handlePosted(sbn, logEvent = true)
    }

    override fun onNotificationRankingUpdate(rankingMap: NotificationListenerService.RankingMap?) {
        refreshActiveNotifications("ranking update")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "nav")))
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "notification")))
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "weather")))
        AppBus.publish(AppBus.Message.ClearSource(SystemEventParsers.sourceKey(sbn, "call")))
        AppBus.publish(AppBus.Message.ClearSource("media_notification:${sbn.packageName}"))
    }

    private fun handlePosted(sbn: StatusBarNotification, logEvent: Boolean) {
        val event = SystemEventParsers.parseNotification(this, sbn) ?: return
        AppBus.publish(AppBus.Message.SubmitWidget(event))
        if (logEvent) {
            AppBus.log("Notification event: ${event.type.wireType} from ${sbn.packageName}")
        }
    }

    private fun refreshActiveNotifications(reason: String) {
        val notifications = activeNotifications.orEmpty()
        val logEvents = reason == "listener connected" || reason == "service start"
        notifications.forEach { sbn -> handlePosted(sbn, logEvent = logEvents) }
        if (logEvents) {
            AppBus.log("Notification snapshot: ${notifications.size} active ($reason)")
        }
    }

    companion object {
        private var activeListener: WeakReference<KejmilNotificationListenerService>? = null

        fun requestActiveSnapshot(reason: String) {
            activeListener?.get()?.refreshActiveNotifications(reason)
        }

        fun requestRebind(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(
                    ComponentName(context, KejmilNotificationListenerService::class.java)
                )
            }
        }
    }
}

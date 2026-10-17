package app.olauncher.helper

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional notification access, used for two things only:
 *  - the music widget, which needs an enabled listener to read active media sessions;
 *  - notification dots, for which it keeps a per-app *count* in memory.
 * Notification content is never read, stored or sent anywhere.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() = recount()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    override fun onListenerDisconnected() {
        NotificationDots.update(emptyMap())
    }

    private fun recount() {
        val active = try {
            activeNotifications.orEmpty()
        } catch (_: SecurityException) {
            emptyArray()
        }
        val counts = HashMap<String, Int>()
        for (sbn in active) {
            if (!isUnread(sbn)) continue
            val key = sbn.packageName + "|" + sbn.user.toString()
            counts[key] = (counts[key] ?: 0) + 1
        }
        NotificationDots.update(counts)
    }

    // Ongoing (music, downloads) and group summaries are not "unread" notifications.
    private fun isUnread(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        return sbn.packageName != packageName &&
            flags and Notification.FLAG_ONGOING_EVENT == 0 &&
            flags and Notification.FLAG_GROUP_SUMMARY == 0
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, MediaNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

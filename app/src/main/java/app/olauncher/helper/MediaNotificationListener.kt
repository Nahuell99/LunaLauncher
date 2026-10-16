package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Exists only so the music widget may read active media sessions
 * (MediaSessionManager.getActiveSessions requires an enabled notification listener).
 * It never reads, stores or acts on notifications.
 */
class MediaNotificationListener : NotificationListenerService() {
    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, MediaNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

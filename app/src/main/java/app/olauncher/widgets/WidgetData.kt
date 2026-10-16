package app.olauncher.widgets

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.helper.MediaNotificationListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class EventInfo(val title: String, val begin: Long, val allDay: Boolean)

data class MediaInfo(val title: String, val artist: String, val app: String, val playing: Boolean)

/** Everything a widget may display, captured once per refresh. */
data class WidgetData(
    val now: Long,
    val battery: Int,
    val charging: Boolean,
    val nextAlarm: Long?,
    val screenTime: String,
    val event: EventInfo?,
    val media: MediaInfo?,
    val mediaPermission: Boolean,
    val greeting: String,
    val is24h: Boolean,
)

/**
 * Collects [WidgetData] from the system. Everything works offline and without extra
 * permissions except calendar events (READ_CALENDAR) and music (notification access),
 * which degrade to empty values when not granted.
 */
class WidgetDataProvider(private val context: Context) {

    var screenTime: String = ""
    private var cachedEvent: EventInfo? = null
    private var eventCheckedAt = 0L

    fun snapshot(): WidgetData {
        val now = System.currentTimeMillis()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val alarm = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
        val controller = activeController()
        return WidgetData(
            now = now,
            battery = if (level >= 0 && scale > 0) level * 100 / scale else 0,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            nextAlarm = alarm,
            screenTime = screenTime,
            event = nextEvent(now),
            media = controller?.let(::mediaInfo),
            mediaPermission = MediaNotificationListener.isEnabled(context),
            greeting = greeting(),
            is24h = DateFormat.is24HourFormat(context),
        )
    }

    private fun greeting(): String = context.getString(
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.greeting_morning
            in 12..19 -> R.string.greeting_afternoon
            in 20..23 -> R.string.greeting_evening
            else -> R.string.greeting_night
        }
    )

    fun calendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // Next upcoming (or ongoing) event in the coming week; cached for five minutes.
    private fun nextEvent(now: Long): EventInfo? {
        if (!calendarPermission()) return null
        if (now - eventCheckedAt < TimeUnit.MINUTES.toMillis(5)) return cachedEvent?.takeIf { it.begin > now - TimeUnit.HOURS.toMillis(12) }
        eventCheckedAt = now
        cachedEvent = try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, now)
                ContentUris.appendId(it, now + TimeUnit.DAYS.toMillis(7))
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                var found: EventInfo? = null
                while (c.moveToNext() && found == null) {
                    val end = c.getLong(2)
                    if (end <= now) continue
                    found = EventInfo(c.getString(0).orEmpty(), c.getLong(1), c.getInt(3) == 1)
                }
                found
            }
        } catch (_: Exception) {
            null
        }
        return cachedEvent
    }

    fun invalidateEvents() {
        eventCheckedAt = 0L
    }

    fun activeController(): MediaController? {
        if (!MediaNotificationListener.isEnabled(context)) return null
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(context, MediaNotificationListener::class.java))
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun mediaInfo(controller: MediaController): MediaInfo {
        val meta = controller.metadata
        val appLabel = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        } catch (_: Exception) {
            controller.packageName
        }
        return MediaInfo(
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            app = appLabel,
            playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }
}

/**
 * Expands `{token}`, `{token:argument}` and `{token|fallback}` placeholders.
 * Unknown tokens are left untouched so typos stay visible while designing a widget.
 */
object WidgetTokens {
    private val pattern = Regex("""\{([a-z_]+)(?::([^}|]*))?(?:\|([^}]*))?\}""")

    fun resolve(text: String, data: WidgetData, itemData: String): String = pattern.replace(text) { match ->
        val token = match.groupValues[1]
        val arg = match.groupValues[2]
        val fallback = match.groupValues[3]
        val value = value(token, arg, data, itemData)
        when {
            value == null -> match.value
            value.isEmpty() -> fallback
            else -> value
        }
    }

    private fun format(pattern: String, time: Long): String = try {
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
    } catch (_: IllegalArgumentException) {
        pattern
    }

    private fun daysUntil(date: String, now: Long): String {
        val target = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(date.trim())
        } catch (_: Exception) {
            null
        } ?: return ""
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Rounded, not truncated: a daylight-saving shift must not cost a day.
        return Math.round((target.time - today) / TimeUnit.DAYS.toMillis(1).toDouble()).toString()
    }

    private fun value(token: String, arg: String, d: WidgetData, itemData: String): String? = when (token) {
        "time" -> format(arg.ifEmpty { if (d.is24h) "HH:mm" else "h:mm" }, d.now)
        "date" -> format(arg.ifEmpty { "EEE, d MMM" }, d.now)
        "battery" -> d.battery.toString()
        "charging" -> if (d.charging) (arg.ifEmpty { "⚡" }) else ""
        "alarm" -> d.nextAlarm?.let { format(arg.ifEmpty { if (d.is24h) "EEE HH:mm" else "EEE h:mm a" }, it) }.orEmpty()
        "greeting" -> d.greeting
        "screentime" -> d.screenTime
        "days_until" -> daysUntil(arg.ifEmpty { itemData.substringBefore(' ') }, d.now)
        "data" -> itemData
        "data_rest" -> if (itemData.contains(' ')) itemData.substringAfter(' ').trim() else ""
        "event" -> d.event?.title.orEmpty()
        "event_time" -> d.event?.let { e ->
            if (e.allDay) "" else format(arg.ifEmpty { if (d.is24h) "EEE HH:mm" else "EEE h:mm a" }, e.begin)
        }.orEmpty()
        "media_title" -> d.media?.title.orEmpty()
        "media_artist" -> d.media?.artist.orEmpty()
        "media_app" -> d.media?.app.orEmpty()
        else -> null
    }
}

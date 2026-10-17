package app.olauncher.helper

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.olauncher.data.Prefs

/** In-memory notification counts per "package|user", fed by [MediaNotificationListener]. */
object NotificationDots {
    const val OFF = 0
    const val DOT = 1
    const val COUNT = 2

    /** Badge value for views: 0 = none, [BADGE_DOT] = plain dot, n > 0 = count. */
    const val BADGE_DOT = -1

    @Volatile
    private var counts: Map<String, Int> = emptyMap()
    private val _changes = MutableLiveData(0)

    /** Emits whenever any count changes; observers redraw their badges. */
    val changes: LiveData<Int> get() = _changes

    fun update(newCounts: Map<String, Int>) {
        if (newCounts == counts) return
        counts = newCounts
        _changes.postValue((_changes.value ?: 0) + 1)
    }

    fun count(packageName: String, user: String): Int = counts["$packageName|$user"] ?: 0

    fun badge(prefs: Prefs, packageName: String, user: String): Int {
        val mode = prefs.notificationDots
        if (mode == OFF || packageName.isEmpty()) return 0
        val n = count(packageName, user)
        return when {
            n == 0 -> 0
            mode == COUNT -> n
            else -> BADGE_DOT
        }
    }

    /** Suffix for text rows (home list, drawer). */
    fun textSuffix(prefs: Prefs, packageName: String, user: String): String =
        when (val b = badge(prefs, packageName, user)) {
            0 -> ""
            BADGE_DOT -> " •"
            else -> " ($b)"
        }
}

package app.olauncher.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import app.olauncher.data.Prefs

/**
 * Draws the selected clock style in place of the classic clock + date and keeps it
 * current (minute ticks, time zone, battery, next alarm). Inactive when no style is set.
 */
class ClockStyleController(
    private val container: FrameLayout,
    private val classicViews: List<View>,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onLongTap: () -> Unit,
) {
    private val appContext = container.context.applicationContext
    private val data = WidgetDataProvider(appContext)
    private var view: CustomWidgetView? = null
    private var ticking = false
    private var gravity = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    /** Applies the current style; returns true when a custom style is shown. */
    fun render(visible: Boolean, horizontalGravity: Int): Boolean {
        val def = prefs.clockStyleId.takeIf { it.isNotEmpty() }?.let { WidgetDef.CLOCKS.find(appContext, prefs, it) }
        if (def == null || !visible) {
            container.visibility = View.GONE
            container.removeAllViews()
            view = null
            stop()
            return false
        }
        classicViews.forEach { it.visibility = View.GONE }
        val current = view
        if (current == null || current.def.json.toString() != def.json.toString() || gravity != horizontalGravity) {
            gravity = horizontalGravity
            container.removeAllViews()
            view = CustomWidgetView(
                container.context, def, "",
                onTap = { onTap() },
                onMediaAction = {},
                forcedGravity = horizontalGravity,
            ).also {
                // Hosted widgets are clickable only when they declare a tap; the clock always is.
                it.isClickable = true
                it.setOnClickListener { onTap() }
                it.setOnLongClickListener { onLongTap(); true }
                container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        container.visibility = View.VISIBLE
        refresh()
        start()
        return true
    }

    fun setScreenTime(value: String) {
        data.screenTime = value
        refresh()
    }

    fun refresh() {
        view?.refresh(data.snapshot())
    }

    fun start() {
        if (ticking || view == null) return
        ticking = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        if (!ticking) return
        ticking = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}

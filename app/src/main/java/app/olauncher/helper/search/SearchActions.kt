package app.olauncher.helper.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.showToast

/** Turns a drawer query into calculator / conversion / command rows, and runs them. */
object SearchActions {

    const val COMMAND_OPEN_LAUNCHER_SETTINGS = "launcher"

    fun extrasFor(context: Context, prefs: Prefs, query: String): List<AppModel> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<AppModel>()
        if (prefs.searchCommands) {
            for (command in SearchExtras.commands(q)) {
                val label = label(context, command) ?: continue
                out += AppModel.SearchExtra(label, AppModel.SearchExtra.KIND_COMMAND, command.id + "\u0000" + command.argument)
            }
        }
        if (prefs.searchCalculator && !q.startsWith(">")) {
            SearchExtras.calculate(q)?.let { value ->
                val result = SearchExtras.formatNumber(value)
                out += AppModel.SearchExtra("= $result", AppModel.SearchExtra.KIND_COPY, result)
            }
            SearchExtras.convert(q)?.let { text ->
                out += AppModel.SearchExtra(text, AppModel.SearchExtra.KIND_COPY, text.substringAfter("= "))
            }
        }
        return out
    }

    private fun label(context: Context, command: SearchExtras.Command): String? {
        val arg = command.argument
        return when (command.id) {
            "wifi" -> context.getString(R.string.cmd_wifi)
            "bluetooth" -> context.getString(R.string.cmd_bluetooth)
            "internet" -> context.getString(R.string.cmd_internet)
            "volume" -> context.getString(R.string.cmd_volume)
            "torch" -> context.getString(R.string.cmd_torch)
            "alarm" -> SearchExtras.parseTime(arg)?.let { (h, m) -> context.getString(R.string.cmd_alarm_at, "%d:%02d".format(h, m)) }
                ?: context.getString(R.string.cmd_alarms)
            "timer" -> SearchExtras.parseDuration(arg)?.let { context.getString(R.string.cmd_timer_for, durationLabel(it)) }
                ?: context.getString(R.string.cmd_timer)
            "battery" -> context.getString(R.string.cmd_battery)
            "display" -> context.getString(R.string.cmd_display)
            "notifications" -> context.getString(R.string.cmd_notifications)
            "settings" -> context.getString(R.string.cmd_settings)
            COMMAND_OPEN_LAUNCHER_SETTINGS -> context.getString(R.string.cmd_launcher_settings)
            else -> null
        }?.let { "> $it" }
    }

    private fun durationLabel(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}h" }, m.takeIf { it > 0 }?.let { "${it}m" }, s.takeIf { it > 0 }?.let { "${it}s" })
            .joinToString(" ")
    }

    /**
     * Runs a search extra. Returns the command id when the caller has to handle it (opening
     * the launcher's own settings needs navigation), null otherwise.
     */
    fun run(context: Context, extra: AppModel.SearchExtra): String? {
        if (extra.kind == AppModel.SearchExtra.KIND_COPY) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(extra.appLabel, extra.payload))
            context.showToast(context.getString(R.string.copied))
            return null
        }
        val id = extra.payload.substringBefore('\u0000')
        val arg = extra.payload.substringAfter('\u0000', "")
        val intent: Intent? = when (id) {
            "wifi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "internet" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "volume" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_VOLUME) else Intent(Settings.ACTION_SOUND_SETTINGS)
            "alarm" -> SearchExtras.parseTime(arg)?.let { (h, m) ->
                Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            } ?: Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "timer" -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                SearchExtras.parseDuration(arg)?.let { putExtra(AlarmClock.EXTRA_LENGTH, it) }
            }
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "torch" -> {
                toggleTorch(context)
                null
            }
            "notifications" -> {
                expandNotificationDrawer(context)
                null
            }
            else -> return id
        }
        intent?.let {
            try {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                context.showToast(context.getString(R.string.unable_to_open_app))
            }
        }
        return null
    }

    // Torch state is only reported through a callback, so keep the last known value.
    @Volatile
    private var torchOn = false
    private var torchCallbackRegistered = false

    private fun toggleTorch(context: Context) {
        val camera = context.getSystemService(CameraManager::class.java) ?: return
        try {
            if (!torchCallbackRegistered) {
                torchCallbackRegistered = true
                camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchOn = enabled
                    }
                }, null)
            }
            val id = camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return context.showToast(context.getString(R.string.cmd_no_torch))
            torchOn = !torchOn
            camera.setTorchMode(id, torchOn)
        } catch (_: Exception) {
            context.showToast(context.getString(R.string.cmd_no_torch))
        }
    }
}

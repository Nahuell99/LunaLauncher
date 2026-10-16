package app.olauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.GridItem
import app.olauncher.data.GridOps
import app.olauncher.data.Prefs

class PinItemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set window to be transparent
        window.setBackgroundDrawable(null)

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            finish()
            return
        }

        val launcherApps = getSystemService(LauncherApps::class.java)
        val pinItemRequest = launcherApps.getPinItemRequest(intent)

        when (pinItemRequest != null) {
            true -> handleRequestType(pinItemRequest)
            false -> showToast(getString(R.string.invalid_pin_request))
        }

        finish()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun handleRequestType(pinItemRequest: LauncherApps.PinItemRequest) {
        when (pinItemRequest.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ->
                handleShortcutRequest(pinItemRequest)

            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET ->
                handleWidgetRequest(pinItemRequest)

            else -> showToast(getString(R.string.unknown_action_not_supported))
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun handleShortcutRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        val shortcutInfo = pinItemRequest.shortcutInfo
        if (shortcutInfo != null) {
            val success = pinItemRequest.accept()
            // With the home grid on, a pinned shortcut also lands on it, like on a classic launcher.
            val prefs = Prefs(this)
            if (success && prefs.gridEnabled) {
                GridOps.addAnywhere(
                    prefs,
                    AppModel.PinnedShortcut(
                        appLabel = shortcutInfo.shortLabel?.toString().orEmpty(),
                        key = null,
                        appPackage = shortcutInfo.`package`,
                        shortcutId = shortcutInfo.id,
                        user = shortcutInfo.userHandle,
                    ),
                )
            }
            val message = when (success) {
                true -> getString(R.string.shortcut_pinned_successfully)
                false -> getString(R.string.failed_to_pin_shortcut)
            }
            showToast(message)
        } else {
            showToast(getString(R.string.invalid_shortcut_info))
        }
    }

    // Apps can offer their widget directly ("Add to home screen"); only the grid can host it.
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun handleWidgetRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        val prefs = Prefs(this)
        val info = pinItemRequest.getAppWidgetProviderInfo(this)
        if (!prefs.gridEnabled || info == null) {
            showToast(getString(if (prefs.gridEnabled) R.string.widgets_not_supported else R.string.grid_disabled_hint))
            return
        }
        val host = AppWidgetHost(applicationContext, Constants.WIDGET_HOST_ID)
        val manager = AppWidgetManager.getInstance(this)
        val id = host.allocateAppWidgetId()
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        val accepted = bound && pinItemRequest.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })
        val placed = accepted && GridOps.add(
            prefs,
            GridItem(
                type = GridItem.TYPE_APP_WIDGET, col = -1, row = -1,
                spanX = 4.coerceAtMost(prefs.gridColumns), spanY = 2.coerceAtMost(prefs.gridRows),
                appWidgetId = id, provider = info.provider.flattenToString(), label = info.loadLabel(packageManager),
            ),
        )
        if (!placed) {
            host.deleteAppWidgetId(id)
            showToast(getString(if (accepted) R.string.grid_full else R.string.widget_bind_failed))
        } else {
            showToast(getString(R.string.grid_added))
        }
    }
}

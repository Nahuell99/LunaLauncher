package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import app.olauncher.R

/** Separate, versioned disclosure: existing users must also consent to app limits. */
object AccessibilityConsent {
    private const val VERSION = 1
    private fun preferences(context: Context) =
        context.getSharedPreferences("accessibility_consent", Context.MODE_PRIVATE)

    fun hasConsent(context: Context) = preferences(context).getInt("accepted_version", 0) == VERSION

    fun showOnLaunch(context: Context) {
        if (!hasConsent(context) && preferences(context).getInt("shown_version", 0) != VERSION) {
            show(context)
        }
    }

    fun openSettings(context: Context) {
        if (hasConsent(context)) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            show(context)
        }
    }

    private fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_message)
            .setPositiveButton(R.string.accessibility_disclosure_accept) { _, _ ->
                preferences(context).edit().putInt("accepted_version", VERSION)
                    .putInt("shown_version", VERSION).commit()
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.accessibility_disclosure_decline) { _, _ ->
                preferences(context).edit().putInt("shown_version", VERSION).apply()
            }
            .setCancelable(false)
            .show()
    }
}

package app.olauncher.ui.settings

import android.os.Build
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.IconSources
import app.olauncher.helper.IconManager
import app.olauncher.helper.MediaNotificationListener
import app.olauncher.helper.NotificationDots
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.icons.IconPack
import app.olauncher.helper.icons.IconShapes
import app.olauncher.helper.showToast
import app.olauncher.ui.IconPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Icon source (system / themed / Lawnicons / installed packs), shape, size and tint. */
class IconSettingsFragment : SubSettingsFragment() {

    override val titleRes = R.string.icon_settings_title

    private val sampleApps by lazy {
        // A few real launcher apps to preview the current source with.
        val ctx = requireContext()
        val launcherApps = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
        launcherApps.getActivityList(null, android.os.Process.myUserHandle())
            .sortedBy { it.label.toString().lowercase() }
            .take(PREVIEW_COUNT)
    }

    override fun build(builder: SettingsBuilder) {
        val ctx = requireContext()
        builder.card(getString(R.string.icon_source)) {
            custom(preview())
            val source = prefs.iconSource
            fun option(label: String, value: String) = valueRow(label, null, if (source == value) "✓" else "") {
                selectSource(value)
            }
            option(getString(R.string.icon_source_system), IconSources.SYSTEM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                option(getString(R.string.icon_source_themed), IconSources.THEMED)
            option(getString(R.string.icon_source_lawnicons), IconSources.LAWNICONS)
        }

        builder.card(getString(R.string.icon_packs_installed)) {
            val packs = IconPack.installedPacks(ctx)
            if (packs.isEmpty()) hint(getString(R.string.icon_pack_none_installed))
            for (pack in packs) {
                val value = IconSources.pack(pack.packageName)
                valueRow(pack.label, null, if (prefs.iconSource == value) "✓" else "") { selectSource(value) }
            }
        }

        builder.card(getString(R.string.icon_where)) {
            switchRow(getString(R.string.settings_drawer_icons), R.drawable.ic_sc_grid, prefs.showDrawerIcons) {
                prefs.showDrawerIcons = it
            }
            switchRow(getString(R.string.settings_home_icons), R.drawable.ic_sc_star, prefs.showHomeIcons) {
                prefs.showHomeIcons = it
                if (!it) prefs.showHomeOnlyIcons = false
                viewModel.refreshHome(false)
            }
            switchRow(getString(R.string.icon_shortcut_column_real), R.drawable.ic_sc_heart, prefs.shortcutIconsUseAppIcon) {
                prefs.shortcutIconsUseAppIcon = it
                viewModel.refreshHome(false)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.card(getString(R.string.colors_title)) {
            switchRow(getString(R.string.dynamic_colors), R.drawable.ic_contrast, prefs.dynamicColors) {
                prefs.dynamicColors = it
                // The colours are part of the theme: recreate to re-inflate with them.
                requireActivity().recreate()
            }
            hint(getString(R.string.dynamic_colors_hint))
        }

        builder.card(getString(R.string.dots_title)) {
            val modes = listOf(R.string.dots_off, R.string.dots_dot, R.string.dots_count).map(::getString)
            valueRow(getString(R.string.dots_mode), R.drawable.ic_bell, modes[prefs.notificationDots.coerceIn(0, modes.lastIndex)]) {
                IconPickerDialog.showList(ctx, getString(R.string.dots_title), modes) { index ->
                    prefs.notificationDots = index
                    viewModel.refreshHome(false)
                    rebuild()
                }
            }
            hint(getString(R.string.dots_hint))
            if (prefs.notificationDots != NotificationDots.OFF && !MediaNotificationListener.isEnabled(ctx)) {
                actionRow(getString(R.string.dots_grant), R.drawable.ic_shield) {
                    runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            }
        }

        builder.card(getString(R.string.icon_shape)) {
            val names = listOf(
                R.string.icon_shape_system, R.string.icon_shape_circle, R.string.icon_shape_squircle,
                R.string.icon_shape_rounded, R.string.icon_shape_square, R.string.icon_shape_teardrop,
            ).map(::getString)
            valueRow(getString(R.string.icon_shape), R.drawable.ic_tune, names[prefs.iconShape.coerceIn(0, names.lastIndex)]) {
                IconPickerDialog.showList(ctx, getString(R.string.icon_shape), names) { index ->
                    prefs.iconShape = IconShapes.ALL[index]
                    applyIconChange()
                }
            }
            hint(getString(R.string.icon_shape_hint))
            sliderRow(
                getString(R.string.icon_size), R.drawable.ic_text_size, 5, 25,
                (prefs.iconSizeScale * 10).roundToInt(), { "%.1f".format(java.util.Locale.ROOT, it / 10f) },
            ) { value ->
                prefs.iconSizeScale = value / 10f
                applyIconChange()
            }
            switchRow(getString(R.string.icon_tint_mono), R.drawable.ic_contrast, prefs.iconTintMono) {
                prefs.iconTintMono = it
                applyIconChange()
            }
        }

        builder.card(getString(R.string.icon_overrides_reset)) {
            hint(getString(R.string.icon_override_hint))
            // Picks from packs that are no longer installed silently fall back to automatic.
            val missing = prefs.iconOverrides().filterValues { !isInstalled(it.substringBefore('/')) }
            if (missing.isNotEmpty()) {
                hint(resources.getQuantityString(R.plurals.icon_overrides_missing, missing.size, missing.size))
                actionRow(getString(R.string.icon_overrides_clear_missing), R.drawable.ic_delete) {
                    missing.keys.forEach(prefs::removeIconOverride)
                    applyIconChange()
                }
            }
            actionRow(getString(R.string.icon_overrides_reset), R.drawable.ic_delete) {
                prefs.clearIconOverrides()
                applyIconChange()
                ctx.showToast(getString(R.string.icon_overrides_reset_done))
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        requireContext().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun preview(): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
        }
        val tint = ctx.getColorFromAttr(R.attr.primaryColor)
        val size = 44.dpToPx()
        for (app in sampleApps) {
            row.addView(ImageView(ctx).apply {
                setImageDrawable(IconManager.getAppIcon(ctx, app.componentName.packageName, app.user, tint, app.componentName.className))
            }, LinearLayout.LayoutParams(size, size).apply { marginStart = 6.dpToPx(); marginEnd = 6.dpToPx() })
        }
        return row
    }

    private fun selectSource(value: String) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Parse a pack before switching so the home screen never stalls on it.
            IconSources.packPackage(value)?.let { pkg ->
                withContext(Dispatchers.IO) { IconManager.pack(appContext, pkg).load() }
            }
            prefs.iconSourceRaw = value
            applyIconChange()
        }
    }

    private fun applyIconChange() {
        IconManager.clearCache()
        viewModel.refreshHome(false)
        if (view != null) rebuild()
    }

    companion object {
        private const val PREVIEW_COUNT = 5
    }
}

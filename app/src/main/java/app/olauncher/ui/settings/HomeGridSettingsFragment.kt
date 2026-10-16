package app.olauncher.ui.settings

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.GridItem
import app.olauncher.data.HomeGrid
import app.olauncher.helper.readBoundedBytes
import app.olauncher.helper.showToast
import app.olauncher.ui.IconPickerDialog
import app.olauncher.widgets.WidgetDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grid on/off, size, labels, editing entry point and the custom widget library. */
class HomeGridSettingsFragment : SubSettingsFragment() {

    override val titleRes = R.string.grid_settings_title

    private var exporting: WidgetDef? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWidget(uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val def = exporting
        exporting = null
        if (uri != null && def != null) exportWidget(uri, def)
    }

    override fun build(builder: SettingsBuilder) {
        builder.card(getString(R.string.grid_settings_title)) {
            switchRow(getString(R.string.grid_enabled), R.drawable.ic_sc_grid, prefs.gridEnabled) {
                prefs.gridEnabled = it
                viewModel.refreshHome(false)
                rebuild()
            }
            if (!prefs.gridEnabled) return@card
            sliderRow(
                getString(R.string.grid_columns), null, HomeGrid.MIN_COLUMNS, HomeGrid.MAX_COLUMNS,
                prefs.gridColumns, { it.toString() },
            ) { value ->
                prefs.gridColumns = value
                viewModel.refreshHome(false)
            }
            sliderRow(
                getString(R.string.grid_rows), null, HomeGrid.MIN_ROWS, HomeGrid.MAX_ROWS,
                prefs.gridRows, { it.toString() },
            ) { value ->
                prefs.gridRows = value
                viewModel.refreshHome(false)
            }
            sliderRow(
                getString(R.string.grid_icon_size), R.drawable.ic_text_size, 4, 20,
                Math.round(prefs.gridIconScale * 10), { "%.1f".format(java.util.Locale.ROOT, it / 10f) },
            ) { value ->
                prefs.gridIconScale = value / 10f
                viewModel.refreshHome(false)
            }
            switchRow(getString(R.string.grid_show_labels), R.drawable.ic_font, prefs.gridShowLabels) {
                prefs.gridShowLabels = it
                viewModel.refreshHome(false)
            }
            actionRow(getString(R.string.grid_edit), R.drawable.ic_move) {
                viewModel.enterGridEditMode.call()
                findNavController().popBackStack(R.id.mainFragment, false)
            }
            hint(getString(R.string.grid_hint))
            actionRow(getString(R.string.grid_clear), R.drawable.ic_delete) { confirmClear() }
        }

        builder.card(getString(R.string.widget_custom_title)) {
            hint(getString(R.string.widget_custom_hint))
            actionRow(getString(R.string.widget_import), R.drawable.ic_sc_download) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            val ctx = requireContext()
            for (def in WidgetDef.Library.all(ctx, prefs)) {
                valueRow(def.name(), R.drawable.ic_widget, getString(if (def.builtIn) R.string.widget_built_in else R.string.widget_imported)) {
                    widgetOptions(def)
                }
            }
        }
    }

    private fun widgetOptions(def: WidgetDef) {
        val options = mutableListOf(getString(R.string.widget_export))
        if (!def.builtIn) options += getString(R.string.widget_delete)
        IconPickerDialog.showList(requireContext(), def.name(), options) { index ->
            when (index) {
                0 -> {
                    exporting = def
                    exportLauncher.launch(def.id.substringAfter('.') + ".json")
                }

                1 -> {
                    WidgetDef.Library.delete(prefs, def.id)
                    // Grid items pointing at the deleted definition go too.
                    prefs.gridItems = prefs.gridItems.filterNot {
                        it.type == GridItem.TYPE_CUSTOM_WIDGET && it.widgetDefId == def.id
                    }
                    viewModel.refreshHome(false)
                    rebuild()
                }
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.grid_clear_confirm)
            .setPositiveButton(R.string.reset) { _, _ ->
                // Android widget ids are released by the home screen when it re-renders.
                viewModel.clearGrid.call()
                requireContext().showToast(getString(R.string.grid_cleared))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importWidget(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBoundedBytes(WidgetDef.MAX_BYTES) }
                        ?: error("Unreadable")
                    WidgetDef.Library.import(ctx, prefs, String(bytes, Charsets.UTF_8))
                }
            }
            result.onSuccess { def ->
                ctx.showToast(getString(R.string.widget_import_ok, def.name()))
                if (view != null) rebuild()
            }.onFailure {
                ctx.showToast(getString(R.string.widget_import_failed))
            }
        }
    }

    private fun exportWidget(uri: Uri, def: WidgetDef) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(def.export().toByteArray(Charsets.UTF_8)) }
                        ?: error("Unwritable")
                }.isSuccess
            }
            ctx.showToast(getString(if (ok) R.string.widget_exported else R.string.widget_import_failed))
        }
    }
}

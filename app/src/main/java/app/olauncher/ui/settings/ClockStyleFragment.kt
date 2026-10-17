package app.olauncher.ui.settings

import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.readBoundedBytes
import app.olauncher.helper.showToast
import app.olauncher.ui.IconPickerDialog
import app.olauncher.widgets.CustomWidgetView
import app.olauncher.widgets.WidgetDataProvider
import app.olauncher.widgets.WidgetDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gallery of clock styles with live previews; entry point to the editor, import and export. */
class ClockStyleFragment : SubSettingsFragment() {

    override val titleRes = R.string.clock_style_title

    private var exporting: WidgetDef? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importStyle(uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val def = exporting
        exporting = null
        if (uri != null && def != null) exportStyle(uri, def)
    }

    override fun build(builder: SettingsBuilder) {
        val ctx = requireContext()
        val snapshot = WidgetDataProvider(ctx.applicationContext).snapshot()
        builder.card(getString(R.string.clock_style_gallery)) {
            valueRow(getString(R.string.clock_style_classic), R.drawable.ic_sc_clock, if (prefs.clockStyleId.isEmpty()) "✓" else "") {
                select("")
            }
            for (def in WidgetDef.CLOCKS.all(ctx, prefs)) custom(previewRow(def, snapshot))
            hint(getString(R.string.clock_style_hint))
        }
        builder.card(getString(R.string.clock_style_actions)) {
            actionRow(getString(R.string.clock_style_customize), R.drawable.ic_rename) {
                openEditor(prefs.clockStyleId.ifEmpty { DEFAULT_STYLE }, copy = false)
            }
            actionRow(getString(R.string.clock_style_new), R.drawable.ic_font) { openEditor(BLANK, copy = true) }
            actionRow(getString(R.string.clock_style_import), R.drawable.ic_sc_download) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
    }

    // Name + check on top, the real style rendered underneath at home-screen size.
    private fun previewRow(def: WidgetDef, snapshot: app.olauncher.widgets.WidgetData): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
            val ripple = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            isClickable = true
            setOnClickListener { select(def.id) }
            setOnLongClickListener { options(def); true }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextSmall)
                text = def.name()
                alpha = 0.8f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextSmallBold)
                text = if (prefs.clockStyleId == def.id) "✓" else ""
            })
        })
        // Fixed height so the view's fit-to-size logic scales big styles down.
        val preview = CustomWidgetView(ctx, def, "", onTap = {}, onMediaAction = {}, forcedGravity = Gravity.START).apply {
            isClickable = false
            refresh(snapshot)
        }
        row.addView(FrameLayout(ctx).apply {
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (PREVIEW_ROW_DP * def.spanY.coerceIn(1, 3)).dpToPx()))
        return row
    }

    private fun select(id: String) {
        prefs.clockStyleId = id
        viewModel.toggleDateTime()
        rebuild()
    }

    private fun options(def: WidgetDef) {
        val entries = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.clock_style_customize) to { openEditor(def.id, copy = !WidgetDef.CLOCKS.isUserId(def.id)) },
            getString(R.string.widget_export) to {
                exporting = def
                exportLauncher.launch(def.id.substringAfter('.') + ".json")
            },
        )
        if (WidgetDef.CLOCKS.isUserId(def.id)) entries += getString(R.string.widget_delete) to {
            WidgetDef.CLOCKS.delete(prefs, def.id)
            if (prefs.clockStyleId == def.id) prefs.clockStyleId = ""
            viewModel.toggleDateTime()
            rebuild()
        }
        IconPickerDialog.showList(requireContext(), def.name(), entries.map { it.first }) { entries[it].second() }
    }

    private fun openEditor(sourceId: String, copy: Boolean) {
        findNavController().navigate(
            R.id.action_clockStyleFragment_to_clockStyleEditorFragment,
            bundleOf(ClockStyleEditorFragment.ARG_SOURCE to sourceId, ClockStyleEditorFragment.ARG_COPY to copy),
        )
    }

    private fun importStyle(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBoundedBytes(WidgetDef.MAX_BYTES) }
                        ?: error("Unreadable")
                    WidgetDef.CLOCKS.import(ctx, prefs, String(bytes, Charsets.UTF_8))
                }
            }
            result.onSuccess { def ->
                ctx.showToast(getString(R.string.widget_import_ok, def.name()))
                select(def.id)
            }.onFailure { ctx.showToast(getString(R.string.widget_import_failed)) }
        }
    }

    private fun exportStyle(uri: Uri, def: WidgetDef) {
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

    companion object {
        const val DEFAULT_STYLE = "clock.thin"
        const val BLANK = "blank"
        private const val PREVIEW_ROW_DP = 64
    }
}

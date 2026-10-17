package app.olauncher.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showToast
import app.olauncher.ui.IconPickerDialog
import app.olauncher.widgets.CustomWidgetView
import app.olauncher.widgets.WidgetDataProvider
import app.olauncher.widgets.WidgetDef
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Visual editor for a clock style. Works on a JSON copy, shows it live in a pinned
 * preview and only writes it (as a user style) on "Save and use".
 */
class ClockStyleEditorFragment : SubSettingsFragment() {

    override val titleRes = R.string.clock_editor_title

    private lateinit var style: JSONObject
    private val lines: JSONArray get() = style.getJSONArray("lines")
    private val snapshot by lazy { WidgetDataProvider(requireContext().applicationContext).snapshot() }

    override fun onReady(savedInstanceState: Bundle?) {
        style = savedInstanceState?.getString(STATE_STYLE)?.let(::JSONObject) ?: initialStyle()
        binding.subSettingsPinned.visibility = View.VISIBLE
        updatePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::style.isInitialized) outState.putString(STATE_STYLE, style.toString())
    }

    private fun initialStyle(): JSONObject {
        val args = requireArguments()
        val source = args.getString(ARG_SOURCE).orEmpty()
        val copy = args.getBoolean(ARG_COPY)
        if (source == ClockStyleFragment.BLANK) return blankStyle()
        val def = WidgetDef.CLOCKS.find(requireContext(), prefs, source) ?: return blankStyle()
        val json = JSONObject(def.json.toString())
        if (copy || !WidgetDef.CLOCKS.isUserId(def.id)) {
            // Editing a bundled style (or duplicating) produces a new user style.
            json.remove("id")
            json.put("name", getString(R.string.clock_style_custom_copy, def.name()))
            json.keys().asSequence().filter { it.startsWith("name_") }.toList().forEach(json::remove)
        }
        return json
    }

    private fun blankStyle() = JSONObject()
        .put("format", WidgetDef.FORMAT).put("version", 1)
        .put("name", getString(R.string.clock_style_custom_copy, getString(R.string.clock_style_title)))
        .put("spanX", 5).put("spanY", 2).put("padding", 0).put("tap", WidgetDef.TAP_CLOCK)
        .put("lines", JSONArray()
            .put(JSONObject().put("text", "{time}").put("size", 64).put("font", "sans-serif-light"))
            .put(JSONObject().put("text", "{date:EEE, d MMM}").put("size", 16).put("alpha", 0.8)))

    // ---------------------------------------------------------------------------------

    private fun updatePreview() {
        val pinned = binding.subSettingsPinned
        pinned.removeAllViews()
        val def = runCatching { WidgetDef.parse(style.toString()) }.getOrNull() ?: return
        val preview = CustomWidgetView(requireContext(), def, "", onTap = {}, onMediaAction = {}, forcedGravity = prefs.clockAlignment)
        preview.isClickable = false
        preview.refresh(snapshot)
        pinned.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // Cap the preview so the editor below stays usable with huge styles.
        pinned.layoutParams = pinned.layoutParams.apply { height = LinearLayout.LayoutParams.WRAP_CONTENT }
        pinned.post {
            val max = PREVIEW_MAX_DP.dpToPx()
            if (pinned.height > max) pinned.layoutParams = pinned.layoutParams.apply { height = max }
        }
    }

    /** Applies a change to the working JSON; [relayout] rebuilds the cards (labels changed). */
    private fun change(relayout: Boolean = false, block: () -> Unit) {
        block()
        updatePreview()
        if (relayout) rebuild()
    }

    override fun build(builder: SettingsBuilder) {
        builder.card(getString(R.string.clock_editor_general)) {
            valueRow(getString(R.string.clock_editor_name), R.drawable.ic_rename, style.optString("name")) {
                editText(getString(R.string.clock_editor_name), style.optString("name"), withTokens = false) { value ->
                    change(relayout = true) { style.put("name", value.take(60)) }
                }
            }
            val formats = listOf(R.string.hour_format_system, R.string.hour_format_12, R.string.hour_format_24).map(::getString)
            val current = when {
                !style.has("hour24") -> 0
                style.optBoolean("hour24") -> 2
                else -> 1
            }
            valueRow(getString(R.string.clock_editor_hour_format), R.drawable.ic_sc_clock, formats[current]) {
                IconPickerDialog.showList(requireContext(), getString(R.string.clock_editor_hour_format), formats) { index ->
                    change(relayout = true) {
                        when (index) {
                            0 -> style.remove("hour24")
                            1 -> style.put("hour24", false)
                            else -> style.put("hour24", true)
                        }
                    }
                }
            }
            hint(getString(R.string.clock_editor_align_hint))
        }

        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i)
            builder.card(getString(R.string.clock_editor_line, i + 1)) {
                val type = line.optString("type", "text")
                if (type == "text") textLineControls(this, line)
                else hint(getString(R.string.clock_editor_special_line, type))
                if (i > 0) actionRow(getString(R.string.clock_editor_move_up), null) { change(relayout = true) { swap(i, i - 1) } }
                if (i < lines.length() - 1) actionRow(getString(R.string.clock_editor_move_down), null) { change(relayout = true) { swap(i, i + 1) } }
                if (lines.length() > 1) actionRow(getString(R.string.clock_editor_delete_line), R.drawable.ic_delete) {
                    change(relayout = true) { lines.remove(i) }
                }
            }
        }

        builder.card(getString(R.string.grid_edit_done)) {
            if (lines.length() < MAX_LINES) {
                actionRow(getString(R.string.clock_editor_add_text), R.drawable.ic_font) {
                    change(relayout = true) { lines.put(JSONObject().put("text", "{date:EEEE}").put("size", 16)) }
                }
                actionRow(getString(R.string.clock_editor_add_bar), R.drawable.ic_sc_star) {
                    change(relayout = true) {
                        lines.put(JSONObject().put("type", "bar").put("value", "{battery}").put("max", 100).put("height", 3))
                    }
                }
            }
            actionRow(getString(R.string.clock_editor_save), R.drawable.ic_check) { save() }
            actionRow(getString(R.string.clock_editor_discard), R.drawable.ic_close) { findNavController().popBackStack() }
        }
    }

    private fun textLineControls(card: SettingsBuilder.Card, line: JSONObject) = with(card) {
        valueRow(getString(R.string.clock_editor_text), R.drawable.ic_rename, line.optString("text")) {
            editText(getString(R.string.clock_editor_text), line.optString("text"), withTokens = true) { value ->
                change(relayout = true) {
                    line.put("text", value)
                    // Hand-edited text replaces any per-language variants of the original.
                    line.keys().asSequence().filter { it.startsWith("text_") }.toList().forEach(line::remove)
                }
            }
        }
        sliderRow(getString(R.string.clock_editor_size), R.drawable.ic_text_size, 8, 140, line.optDouble("size", 16.0).roundToInt(),
            { "$it sp" }, onLive = { v -> change { line.put("size", v) } }) { v -> change { line.put("size", v) } }

        val fonts = FONTS.map { (family, label) -> family to getString(label) }
        val font = line.optString("font")
        valueRow(getString(R.string.clock_editor_font), R.drawable.ic_font, fonts.firstOrNull { it.first == font }?.second ?: font) {
            IconPickerDialog.showList(requireContext(), getString(R.string.clock_editor_font), fonts.map { it.second }) { index ->
                change(relayout = true) {
                    if (fonts[index].first.isEmpty()) line.remove("font") else line.put("font", fonts[index].first)
                }
            }
        }
        switchRow(getString(R.string.clock_editor_bold), null, line.optBoolean("bold")) { v -> change { line.put("bold", v) } }
        switchRow(getString(R.string.clock_editor_italic), null, line.optBoolean("italic")) { v -> change { line.put("italic", v) } }
        switchRow(getString(R.string.clock_editor_uppercase), null, line.optBoolean("uppercase")) { v -> change { line.put("uppercase", v) } }
        sliderRow(getString(R.string.clock_editor_spacing), null, 0, 60, (line.optDouble("letterSpacing", 0.0) * 100).roundToInt(),
            { "%.2f".format(java.util.Locale.ROOT, it / 100f) },
            onLive = { v -> change { line.put("letterSpacing", v / 100.0) } }) { v -> change { line.put("letterSpacing", v / 100.0) } }

        val colors = listOf(
            "primary" to R.string.color_text, "secondary" to R.string.color_secondary,
            "accent" to R.string.color_accent, "inverse" to R.string.color_inverse,
        ).map { (value, label) -> value to getString(label) }
        val color = line.optString("color", "primary")
        valueRow(getString(R.string.clock_editor_color), R.drawable.ic_contrast, colors.firstOrNull { it.first == color }?.second ?: color) {
            IconPickerDialog.showList(requireContext(), getString(R.string.clock_editor_color), colors.map { it.second } + getString(R.string.color_custom)) { index ->
                if (index < colors.size) change(relayout = true) { line.put("color", colors[index].first) }
                else editText(getString(R.string.color_custom), color.takeIf { it.startsWith("#") } ?: "#FFFFFF", withTokens = false) { value ->
                    if (runCatching { value.trim().toColorInt() }.isSuccess) change(relayout = true) { line.put("color", value.trim()) }
                    else requireContext().showToast(getString(R.string.clock_editor_invalid_color))
                }
            }
        }
        sliderRow(getString(R.string.clock_editor_opacity), R.drawable.ic_opacity, 10, 100, (line.optDouble("alpha", 1.0) * 100).roundToInt(),
            { "$it%" }, onLive = { v -> change { line.put("alpha", v / 100.0) } }) { v -> change { line.put("alpha", v / 100.0) } }
        sliderRow(getString(R.string.clock_editor_margin), null, -40, 60, line.optInt("marginTop", 0),
            { "$it dp" }, onLive = { v -> change { line.put("marginTop", v) } }) { v -> change { line.put("marginTop", v) } }
    }

    private fun swap(a: Int, b: Int) {
        val first = lines.get(a)
        lines.put(a, lines.get(b))
        lines.put(b, first)
    }

    /** Text dialog; with [withTokens], chips insert tokens at the cursor. */
    private fun editText(title: String, value: String, withTokens: Boolean, onDone: (String) -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(value)
            setSelection(text.length)
            setTextColor(ctx.getColorFromAttr(R.attr.primaryColor))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 0)
            addView(input)
        }
        if (withTokens) {
            root.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.clock_editor_insert)
                alpha = 0.7f
                setTextColor(ctx.getColorFromAttr(R.attr.primaryColor))
                setPadding(0, 12.dpToPx(), 0, 0)
            })
            val chips = ChipGroup(ctx).apply { setPadding(0, 4.dpToPx(), 0, 0) }
            for ((label, token) in TOKENS) {
                chips.addView(Chip(ctx).apply {
                    text = getString(label)
                    setOnClickListener {
                        val start = input.selectionStart.coerceAtLeast(0)
                        input.text.insert(start, token)
                    }
                })
            }
            root.addView(chips)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        val def = runCatching { WidgetDef.CLOCKS.upsert(prefs, style.toString()) }.getOrNull()
        if (def == null) {
            requireContext().showToast(getString(R.string.widget_import_failed))
            return
        }
        prefs.clockStyleId = def.id
        viewModel.toggleDateTime()
        requireContext().showToast(getString(R.string.clock_editor_saved))
        findNavController().popBackStack()
    }

    companion object {
        const val ARG_SOURCE = "source"
        const val ARG_COPY = "copy"
        private const val STATE_STYLE = "style"
        private const val MAX_LINES = 24
        private const val PREVIEW_MAX_DP = 220

        // Font family → label. Empty = the launcher font chosen in Settings.
        private val FONTS = listOf(
            "" to R.string.font_launcher,
            "sans-serif-thin" to R.string.font_sans_serif_thin,
            "sans-serif-light" to R.string.font_sans_serif_light,
            "sans-serif" to R.string.font_sans_serif,
            "sans-serif-medium" to R.string.font_sans_serif_medium,
            "sans-serif-black" to R.string.font_sans_serif_black,
            "sans-serif-condensed" to R.string.font_sans_serif_condensed,
            "serif" to R.string.font_serif,
            "monospace" to R.string.font_monospace,
            "casual" to R.string.font_casual,
            "cursive" to R.string.font_cursive,
        )

        private val TOKENS = listOf(
            R.string.token_time to "{time}",
            R.string.token_time24 to "{time:HH:mm}",
            R.string.token_date to "{date:d MMM}",
            R.string.token_weekday to "{date:EEEE}",
            R.string.token_month to "{date:MMMM}",
            R.string.token_words to "{time_words}",
            R.string.token_battery to "{battery}%",
            R.string.token_alarm to "{alarm|—}",
            R.string.token_greeting to "{greeting}",
            R.string.token_event to "{event}",
            R.string.token_binary to "{binary_h}  {binary_m}",
        )
    }
}

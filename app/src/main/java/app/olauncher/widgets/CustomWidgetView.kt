package app.olauncher.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import app.olauncher.R
import app.olauncher.helper.FontHelper
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Built in code only (never inflated), hence no XML constructors.
 *
 * Renders a [WidgetDef] as native views. Text is re-resolved on every [refresh]
 * (driven by the home screen's time/battery/media ticks); layout is built once.
 * When the content is taller than the cells it occupies, text is scaled down to fit.
 */
@SuppressLint("ViewConstructor")
class CustomWidgetView(
    context: Context,
    val def: WidgetDef,
    private var itemData: String,
    private val onTap: (String) -> Unit,
    private val onMediaAction: (Int) -> Unit,
    // Horizontal Gravity imposed by the host (the home clock follows Settings → alignment).
    private val forcedGravity: Int? = null,
) : LinearLayout(context) {

    private class TextBinding(val view: TextView, val template: String, val hideIfEmpty: Boolean, val uppercase: Boolean, val baseSizePx: Float)
    private class BarBinding(val view: BarView, val value: String, val max: Float)

    private val texts = ArrayList<TextBinding>()
    private val bars = ArrayList<BarBinding>()
    private var playPause: ImageView? = null
    private val primary = context.getColorFromAttr(R.attr.primaryColor)
    private val shadow = context.getColorFromAttr(R.attr.primaryTextShadowColor)
    private val language = Locale.getDefault().language
    private var fitScale = 1f

    init {
        val json = def.json
        orientation = VERTICAL
        gravity = (forcedGravity ?: horizontalGravity(json.optString("gravity", "start"))) or
            verticalGravity(json.optString("vgravity", "center"))
        val pad = json.optInt("padding", 8).coerceIn(0, 48).dpToPx()
        setPadding(pad, pad, pad, pad)
        json.optJSONObject("background")?.let { background = backgroundOf(it) }
        buildLines(json.getJSONArray("lines"), this)
        isClickable = def.tap != WidgetDef.TAP_NONE
        if (isClickable) setOnClickListener { onTap(def.tap) }
    }

    fun updateItemData(data: String) {
        itemData = data
    }

    // ---- Building ----

    private fun buildLines(lines: JSONArray, parent: LinearLayout) {
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val view = when (line.optString("type", "text")) {
                "bar" -> buildBar(line)
                "space" -> View(context).also {
                    it.layoutParams = LayoutParams(1, line.optInt("height", 8).coerceIn(0, 200).dpToPx())
                }
                "media_controls" -> buildMediaControls(line)
                "row" -> LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or horizontalGravity(line.optString("gravity", "start"))
                    buildLines(line.getJSONArray("items"), this)
                }
                else -> buildText(line, parent.orientation == HORIZONTAL)
            }
            if (parent.orientation == HORIZONTAL && i > 0) {
                (view.layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                    .also { it.marginStart = line.optInt("marginStart", 8).dpToPx(); view.layoutParams = it }
            }
            parent.addView(view)
        }
    }

    private fun localized(line: JSONObject, key: String): String =
        line.optString("${key}_$language").ifEmpty { line.optString(key) }

    private fun buildText(line: JSONObject, inRow: Boolean): TextView {
        val sizeSp = line.optDouble("size", 16.0).toFloat().coerceIn(6f, 220f)
        val view = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(colorOf(line.optString("color", "primary"), line.optDouble("alpha", 1.0).toFloat()))
            typeface = typefaceOf(line.optString("font"), line.optBoolean("bold"), line.optBoolean("italic"))
            letterSpacing = line.optDouble("letterSpacing", 0.0).toFloat().coerceIn(-0.2f, 1f)
            includeFontPadding = line.optBoolean("fontPadding", false)
            maxLines = line.optInt("maxLines", 2).coerceIn(1, 10)
            ellipsize = TextUtils.TruncateAt.END
            if (line.optBoolean("shadow", true)) setShadowLayer(4f, 0f, 1f, shadow)
            if (forcedGravity == null) line.optString("gravity").takeIf { it.isNotEmpty() }?.let { gravity = horizontalGravity(it) }
            layoutParams = if (inRow) LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also { lp ->
                // Text lines follow the widget's horizontal alignment unless they set their own.
                if (forcedGravity != null || line.optString("gravity").isEmpty())
                    gravity = this@CustomWidgetView.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                lp.topMargin = line.optInt("marginTop", 0).coerceIn(-40, 100).dpToPx()
            }
        }
        texts.add(TextBinding(view, localized(line, "text"), line.optBoolean("hideIfEmpty"), line.optBoolean("uppercase"), view.textSize))
        return view
    }

    private fun buildBar(line: JSONObject): BarView {
        val bar = BarView(context, colorOf(line.optString("color", "primary"), line.optDouble("alpha", 1.0).toFloat()))
        val height = line.optInt("height", 4).coerceIn(1, 40).dpToPx()
        bar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height).apply {
            topMargin = line.optInt("marginTop", 6).dpToPx()
        }
        bars.add(BarBinding(bar, line.optString("value", "{battery}"), line.optDouble("max", 100.0).toFloat().coerceAtLeast(1f)))
        return bar
    }

    private fun buildMediaControls(line: JSONObject): LinearLayout {
        val size = line.optInt("size", 36).coerceIn(20, 96).dpToPx()
        val color = colorOf(line.optString("color", "primary"), 1f)
        fun button(icon: Int, action: Int) = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
            val p = size / 5
            setPadding(p, p, p, p)
            val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onMediaAction(action) }
            layoutParams = LayoutParams(size, size)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or horizontalGravity(line.optString("gravity", "start"))
            addView(button(R.drawable.ic_media_prev, MEDIA_PREVIOUS))
            playPause = button(R.drawable.ic_sc_play, MEDIA_PLAY_PAUSE)
            addView(playPause)
            addView(button(R.drawable.ic_media_next, MEDIA_NEXT))
        }
    }

    private fun backgroundOf(bg: JSONObject) = GradientDrawable().apply {
        setColor(colorOf(bg.optString("color", "#00000000"), bg.optDouble("alpha", 1.0).toFloat()))
        cornerRadius = bg.optInt("radius", 16).coerceIn(0, 200).dpToPx().toFloat()
        val stroke = bg.optString("stroke")
        if (stroke.isNotEmpty()) setStroke(bg.optInt("strokeWidth", 1).coerceIn(1, 8).dpToPx(), colorOf(stroke, 1f))
    }

    private fun colorOf(spec: String, alpha: Float): Int {
        val base = when (spec.lowercase(Locale.ROOT)) {
            "primary", "" -> primary
            "secondary", "primary50" -> context.getColorFromAttr(R.attr.primaryColorTrans50)
            "accent" -> accentColor(context)
            "inverse" -> context.getColorFromAttr(R.attr.primaryInverseColor)
            else -> try { spec.toColorInt() } catch (_: IllegalArgumentException) { primary }
        }
        val a = (Color.alpha(base) * alpha.coerceIn(0f, 1f)).toInt()
        return (base and 0x00FFFFFF) or (a shl 24)
    }

    private fun typefaceOf(font: String, bold: Boolean, italic: Boolean): Typeface? {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val family = when (font) {
            "", "launcher" -> FontHelper.current(context)
            else -> runCatching { Typeface.create(font, Typeface.NORMAL) }.getOrNull()
        }
        return Typeface.create(family ?: Typeface.DEFAULT, style)
    }

    private fun horizontalGravity(value: String) = when (value) {
        "center" -> Gravity.CENTER_HORIZONTAL
        "end" -> Gravity.END
        else -> Gravity.START
    }

    private fun verticalGravity(value: String) = when (value) {
        "top" -> Gravity.TOP
        "bottom" -> Gravity.BOTTOM
        else -> Gravity.CENTER_VERTICAL
    }

    // ---- Refresh ----

    fun refresh(snapshot: WidgetData) {
        // "hour24": true / false forces the clock format; absent = follow the system.
        val data = if (def.json.has("hour24")) snapshot.copy(is24h = def.json.optBoolean("hour24")) else snapshot
        for (binding in texts) {
            var text = WidgetTokens.resolve(binding.template, data, itemData)
            if (binding.uppercase) text = text.uppercase(Locale.getDefault())
            val visibility = if (binding.hideIfEmpty && text.isBlank()) GONE else VISIBLE
            if (binding.view.text.toString() != text || binding.view.visibility != visibility) contentDirty = true
            binding.view.text = text
            binding.view.visibility = visibility
        }
        for (binding in bars) {
            val value = WidgetTokens.resolve(binding.value, data, itemData).toFloatOrNull() ?: 0f
            binding.view.fraction = (value / binding.max).coerceIn(0f, 1f)
        }
        playPause?.setImageResource(if (data.media?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_sc_play)
        if (def.usesMedia && !data.mediaPermission) {
            texts.firstOrNull()?.view?.apply {
                text = context.getString(R.string.widget_media_permission)
                visibility = VISIBLE
            }
        }
    }

    // Fitting changes text sizes, which requests another layout; only refit when the
    // available size or the content changed, so layout settles instead of looping.
    private var fittedWidth = -1
    private var fittedHeight = -1
    private var contentDirty = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val sizeChanged = width != fittedWidth || height != fittedHeight
        if ((contentDirty || sizeChanged) && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            contentDirty = false
            fittedWidth = width
            fittedHeight = height
            applyScale(1f)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val content = measuredHeight - paddingTop - paddingBottom
            val room = height - paddingTop - paddingBottom
            if (content > room && content > 0) applyScale((room.toFloat() / content).coerceIn(0.3f, 1f))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyScale(scale: Float) {
        if (scale == fitScale) return
        fitScale = scale
        for (binding in texts) binding.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.baseSizePx * scale)
    }

    /** A thin rounded progress bar (battery, countdowns...). */
    @SuppressLint("ViewConstructor")
    class BarView(context: Context, color: Int) : View(context) {
        var fraction = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x33000000 }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, track)
            rect.right = width * fraction
            if (rect.right > 0f) canvas.drawRoundRect(rect, r, r, fill)
        }
    }

    companion object {
        /** Blue by default; the wallpaper accent when Material You colours are on. */
        fun accentColor(context: Context): Int =
            if (app.olauncher.data.Prefs(context).dynamicColors) ContextCompat.getColor(context, R.color.dynamic_accent)
            else "#3B82F6".toColorInt()

        const val MEDIA_PREVIOUS = 0
        const val MEDIA_PLAY_PAUSE = 1
        const val MEDIA_NEXT = 2

        /** Small, theme-coloured preview icon for lists of widgets. */
        fun previewIcon(context: Context) = ContextCompat.getDrawable(context, R.drawable.ic_widget)
    }
}

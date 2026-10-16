package app.olauncher.ui.grid

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import app.olauncher.R
import app.olauncher.helper.dpToPx
import kotlin.math.min

/** An app / shortcut / folder icon on the home grid: icon centered in its cells, optional label. */
class GridIconView(context: Context) : LinearLayout(context) {

    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val label = TextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextSmall)
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    var iconScale = 1f
    var showLabel = true
        set(value) {
            field = value
            label.visibility = if (value) VISIBLE else GONE
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(icon)
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dpToPx()
        })
    }

    fun bind(drawable: Drawable?, text: String, tint: Int?) {
        icon.setImageDrawable(drawable)
        icon.imageTintList = tint?.let { android.content.res.ColorStateList.valueOf(it) }
        label.text = text
        contentDescription = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // Base icon fills ~55% of the smaller side of the item area, scaled by the user.
        val labelSpace = if (showLabel) (label.textSize * 1.6f).toInt() else 0
        val base = min(w, h - labelSpace).coerceAtLeast(0) * 0.62f
        val maxSize = min(w, h - labelSpace).coerceAtLeast(1)
        val size = (base * iconScale).toInt().coerceIn(min(12.dpToPx(), maxSize), maxSize)
        (icon.layoutParams as LayoutParams).apply {
            width = size
            height = size
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

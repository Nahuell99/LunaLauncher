package app.olauncher.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.widget.TextViewCompat
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * Builds settings cards in code with the same look as fragment_settings.xml
 * (glass card, section title, icon + label rows, switches, sliders, dividers).
 * Keeps the secondary settings screens declarative and short.
 */
class SettingsBuilder(private val context: Context, private val root: LinearLayout) {

    fun card(title: String, content: Card.() -> Unit): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_rect_shade_color_glass)
            setPadding(14.dpToPx(), 18.dpToPx(), 14.dpToPx(), 18.dpToPx())
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 0) }
        )
        card.addView(TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextSectionTitle)
            text = title
            setPadding(8.dpToPx(), 8.dpToPx(), 0, 12.dpToPx())
        })
        Card(context, card).content()
        return card
    }

    class Card(private val context: Context, val container: LinearLayout) {
        private var rows = 0
        private val primary = context.getColorFromAttr(R.attr.primaryColor)

        private fun label(text: String, icon: Int?): TextView = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextSmall)
            setTextColor(primary)
            this.text = text
            setPadding(8.dpToPx(), 8.dpToPx(), 0, 8.dpToPx())
            if (icon != null) {
                val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setTint(primary) }
                setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
                compoundDrawablePadding = 12.dpToPx()
            }
        }

        private fun addRow(view: View) {
            if (rows++ > 0) divider()
            container.addView(view)
        }

        fun divider() {
            container.addView(View(context).apply {
                setBackgroundResource(R.drawable.divider_light)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, halfDp()).apply {
                setMargins(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
            })
        }

        // Matches the 0.5dp dividers of fragment_settings.xml.
        private fun halfDp(): Int = (context.resources.displayMetrics.density / 2f).toInt().coerceAtLeast(1)

        private fun horizontal(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dpToPx(), 0, 0)
        }

        private fun ripple(view: View) {
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            view.setBackgroundResource(value.resourceId)
        }

        fun switchRow(text: String, icon: Int?, checked: Boolean, onToggle: (Boolean) -> Unit): SwitchCompat {
            val row = horizontal()
            row.addView(label(text, icon), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val switch = SwitchCompat(context).apply {
                isChecked = checked
                this.text = ""
                textOn = ""
                textOff = ""
                thumbTintList = ContextCompat.getColorStateList(context, R.color.switch_thumb)
                trackTintList = ContextCompat.getColorStateList(context, R.color.switch_track)
                setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
            }
            row.addView(switch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 4.dpToPx()
            })
            addRow(row)
            return switch
        }

        /** A tappable row with a value on the right. Returns the value view so callers can update it. */
        fun valueRow(text: String, icon: Int?, value: String, onClick: () -> Unit): TextView {
            val row = horizontal()
            ripple(row)
            row.isClickable = true
            row.setOnClickListener { onClick() }
            row.addView(label(text, icon), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val valueView = TextView(context).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextSmallBold)
                this.text = value
                maxLines = 1
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            }
            row.addView(valueView)
            addRow(row)
            return valueView
        }

        fun actionRow(text: String, icon: Int?, onClick: () -> Unit): TextView {
            val view = label(text, icon)
            ripple(view)
            view.setOnClickListener { onClick() }
            addRow(view)
            return view
        }

        fun sliderRow(
            text: String,
            icon: Int?,
            min: Int,
            max: Int,
            value: Int,
            format: (Int) -> String,
            onLive: (Int) -> Unit = {},
            onStop: (Int) -> Unit,
        ): SeekBar {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val header = horizontal()
            header.addView(label(text, icon), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val valueView = TextView(context).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextSmallBold)
                this.text = format(value)
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            }
            header.addView(valueView)
            column.addView(header)
            val accent = "#3B82F6".toColorInt()
            val seekBar = SeekBar(context).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList = ColorStateList.valueOf("#619E9E9E".toColorInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        valueView.text = format(progress + min)
                        if (fromUser) onLive(progress + min)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = onStop(seekBar.progress + min)
                })
            }
            column.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 6.dpToPx()
                marginEnd = 6.dpToPx()
            })
            addRow(column)
            return seekBar
        }

        fun hint(text: String): TextView {
            val view = TextView(context).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextSmallLight)
                textSize = 13f
                this.text = text
                alpha = 0.7f
                setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
            }
            container.addView(view)
            return view
        }

        /** Any custom view (previews etc.), counted as a row for divider placement. */
        fun custom(view: View) = addRow(view)
    }
}

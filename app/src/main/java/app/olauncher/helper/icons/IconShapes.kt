package app.olauncher.helper.icons

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** Icon shapes applied to adaptive system icons. Stored as an Int in prefs. */
object IconShapes {
    const val SYSTEM = 0
    const val CIRCLE = 1
    const val SQUIRCLE = 2
    const val ROUNDED_SQUARE = 3
    const val SQUARE = 4
    const val TEARDROP = 5

    val ALL = intArrayOf(SYSTEM, CIRCLE, SQUIRCLE, ROUNDED_SQUARE, SQUARE, TEARDROP)

    fun path(shape: Int, size: Float): Path = Path().apply {
        val rect = RectF(0f, 0f, size, size)
        when (shape) {
            CIRCLE -> addOval(rect, Path.Direction.CW)
            ROUNDED_SQUARE -> addRoundRect(rect, size * 0.22f, size * 0.22f, Path.Direction.CW)
            SQUARE -> addRect(rect, Path.Direction.CW)
            TEARDROP -> {
                val r = size / 2f
                val radii = floatArrayOf(r, r, r, r, size * 0.12f, size * 0.12f, r, r)
                addRoundRect(rect, radii, Path.Direction.CW)
            }

            else -> addSuperellipse(this, size) // SQUIRCLE (and fallback)
        }
    }

    // |x|^n + |y|^n = 1 with n = 4: the classic "squircle".
    private fun addSuperellipse(path: Path, size: Float) {
        val r = size / 2f
        val steps = 96
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val c = kotlin.math.cos(t)
            val s = kotlin.math.sin(t)
            val x = r + r * sign(c) * abs(c).pow(0.5)
            val y = r + r * sign(s) * abs(s).pow(0.5)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
    }

    /**
     * Redraws [icon] clipped to [shape]. Only adaptive icons can be reshaped faithfully;
     * legacy bitmaps are returned untouched. Returns null when nothing has to change.
     */
    fun reshape(icon: Drawable, shape: Int, sizePx: Int, res: Resources): Drawable? {
        if (shape == SYSTEM || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (icon !is AdaptiveIconDrawable) return null
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        canvas.clipPath(path(shape, sizePx.toFloat()))
        drawLayer(icon.background, canvas, sizePx)
        drawLayer(icon.foreground, canvas, sizePx)
        return bitmap.toDrawable(res)
    }

    /**
     * Android 13+ themed icon: the adaptive icon's monochrome layer tinted with [tint].
     * Returns null when the app ships no monochrome layer.
     */
    fun monochrome(icon: Drawable, tint: Int, sizePx: Int, res: Resources): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (icon !is AdaptiveIconDrawable) return null
        val mono = icon.monochrome ?: return null
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val layer = mono.mutate()
        layer.setTint(tint)
        drawLayer(layer, canvas, sizePx)
        return bitmap.toDrawable(res)
    }

    // Adaptive layers are 108dp with a 72dp visible core: draw them 1.5x larger,
    // centered, so the visible core fills the target square.
    private fun drawLayer(layer: Drawable?, canvas: Canvas, sizePx: Int) {
        if (layer == null) return
        val inset = sizePx / 4
        layer.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
        layer.draw(canvas)
    }
}

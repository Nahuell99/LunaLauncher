package app.olauncher.helper.icons

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Calendar

data class IconPackInfo(val packageName: String, val label: String)

/**
 * A third-party icon pack following the ADW / Nova "appfilter" convention, which is
 * what virtually every pack on the Play Store and F-Droid ships.
 *
 * appfilter.xml maps components to drawable names and may define iconback / iconmask /
 * iconupon / scale, used to theme apps the pack does not cover. Everything is read
 * lazily through the pack's own [Resources]; nothing is copied into our storage.
 */
class IconPack(context: Context, val packageName: String) {

    private val appContext = context.applicationContext

    @Volatile
    private var loaded = false
    private val lock = Any()

    private var res: Resources? = null
    private val componentMap = HashMap<String, String>()
    private val packageMap = HashMap<String, String>()
    private val calendarMap = HashMap<String, String>()
    private val iconBacks = ArrayList<String>()
    private var iconMask: String? = null
    private var iconUpon: String? = null
    private var scale = 1f

    val hasMask: Boolean
        get() {
            load()
            return iconBacks.isNotEmpty() || iconMask != null || iconUpon != null
        }

    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                val packContext = appContext.createPackageContext(packageName, 0)
                res = packContext.resources
                parseAppFilter(packContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            loaded = true
        }
    }

    @SuppressLint("DiscouragedApi") // Icon packs can only be read by resource name.
    private fun openAppFilter(packContext: Context): XmlPullParser? {
        val r = packContext.resources
        val id = r.getIdentifier("appfilter", "xml", packageName)
        if (id != 0) return r.getXml(id)
        return try {
            val stream = packContext.assets.open("appfilter.xml")
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, null) }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAppFilter(packContext: Context) {
        val parser = openAppFilter(packContext) ?: return
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && !drawable.isNullOrBlank()) addComponent(component, drawable, componentMap)
                    }

                    "calendar" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val prefix = parser.getAttributeValue(null, "prefix")
                        if (component != null && !prefix.isNullOrBlank()) addComponent(component, prefix, calendarMap)
                    }

                    "iconback" -> for (i in 0 until parser.attributeCount) {
                        val value = parser.getAttributeValue(i)
                        if (parser.getAttributeName(i).startsWith("img") && !value.isNullOrBlank()) iconBacks.add(value)
                    }

                    "iconmask" -> iconMask = parser.getAttributeValue(null, "img1")?.takeIf { it.isNotBlank() }
                    "iconupon" -> iconUpon = parser.getAttributeValue(null, "img1")?.takeIf { it.isNotBlank() }
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull()?.coerceIn(0.1f, 1.5f) ?: 1f
                }
            }
            event = parser.next()
        }
    }

    // "ComponentInfo{pkg/cls}" → "pkg/cls"; also tolerates a bare package name.
    private fun addComponent(raw: String, drawable: String, target: HashMap<String, String>) {
        val clean = raw.substringAfter("{", raw).substringBefore("}").trim()
        if (clean.isEmpty()) return
        val pkg = clean.substringBefore("/")
        var key = clean
        if (clean.contains('/')) {
            // Relative activity names (".Main") are expanded so lookups are exact.
            val cls = clean.substringAfter("/")
            if (cls.startsWith(".")) key = "$pkg/$pkg$cls"
        }
        target.putIfAbsent(key, drawable)
        if (target === componentMap) packageMap.putIfAbsent(pkg, drawable)
    }

    /** Icon the pack defines for [component], or null when the pack does not theme it. */
    fun iconFor(component: ComponentName): Drawable? {
        load()
        val key = "${component.packageName}/${component.className}"
        calendarMap[key]?.let { prefix ->
            val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            drawableByName("$prefix$day")?.let { return it }
        }
        val name = componentMap[key] ?: packageMap[component.packageName] ?: return null
        return drawableByName(name)
    }

    @SuppressLint("DiscouragedApi")
    fun drawableByName(name: String): Drawable? {
        load()
        val r = res ?: return null
        var id = r.getIdentifier(name, "drawable", packageName)
        if (id == 0) id = r.getIdentifier(name, "mipmap", packageName)
        if (id == 0) return null
        return try {
            ResourcesCompat.getDrawable(r, id, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Themes an icon the pack does not cover with its iconback / iconmask / iconupon.
     * [seed] keeps the chosen back stable for a given app between redraws.
     */
    fun applyMask(base: Drawable, sizePx: Int, seed: Int): Drawable? {
        if (!hasMask) return null
        val output = createBitmap(sizePx, sizePx)
        val canvas = Canvas(output)

        if (iconBacks.isNotEmpty()) {
            val back = drawableByName(iconBacks[Math.floorMod(seed, iconBacks.size)])
            back?.setBounds(0, 0, sizePx, sizePx)
            back?.draw(canvas)
        }

        val inner = createBitmap(sizePx, sizePx)
        val innerCanvas = Canvas(inner)
        val scaled = (sizePx * scale).toInt()
        val offset = (sizePx - scaled) / 2
        base.setBounds(offset, offset, offset + scaled, offset + scaled)
        base.draw(innerCanvas)

        iconMask?.let { drawableByName(it) }?.let { mask ->
            val maskBitmap = createBitmap(sizePx, sizePx)
            mask.setBounds(0, 0, sizePx, sizePx)
            mask.draw(Canvas(maskBitmap))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            innerCanvas.drawBitmap(maskBitmap, 0f, 0f, paint)
            maskBitmap.recycle()
        }
        canvas.drawBitmap(inner, 0f, 0f, null)
        inner.recycle()

        iconUpon?.let { drawableByName(it) }?.let { upon ->
            upon.setBounds(0, 0, sizePx, sizePx)
            upon.draw(canvas)
        }
        return output.toDrawable(appContext.resources)
    }

    /**
     * Every drawable name the pack offers, for the manual "change icon" picker.
     * Prefers the pack's curated drawable.xml; falls back to the appfilter targets.
     */
    @SuppressLint("DiscouragedApi")
    fun allDrawableNames(): List<String> {
        load()
        val r = res ?: return emptyList()
        val names = LinkedHashSet<String>()
        try {
            val id = r.getIdentifier("drawable", "xml", packageName)
            if (id != 0) {
                val parser = r.getXml(id)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let(names::add)
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (names.isEmpty()) names.addAll(componentMap.values.sorted())
        return names.toList()
    }

    companion object {
        // Intent actions / categories that icon packs register so launchers can find them.
        private val PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.dlto.atom.launcher.THEME",
        )
        private val PACK_CATEGORIES = listOf(
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK",
        )

        fun installedPacks(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            val intents = PACK_ACTIONS.map { Intent(it) } +
                PACK_CATEGORIES.map { Intent(Intent.ACTION_MAIN).addCategory(it) }
            val found = LinkedHashMap<String, IconPackInfo>()
            for (intent in intents) {
                val resolved = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                    else
                        @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
                } catch (_: Exception) {
                    emptyList()
                }
                for (info in resolved) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == context.packageName || found.containsKey(pkg)) continue
                    val label = try {
                        info.activityInfo.applicationInfo.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    found[pkg] = IconPackInfo(pkg, label)
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }
    }
}

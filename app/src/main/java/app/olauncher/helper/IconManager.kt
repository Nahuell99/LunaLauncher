package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.util.Xml
import android.util.LruCache
import androidx.lifecycle.MutableLiveData
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.IconSources
import app.olauncher.data.Prefs
import app.olauncher.helper.icons.IconPack
import app.olauncher.helper.icons.IconShapes
import com.caverock.androidsvg.SVG
import org.xmlpull.v1.XmlPullParser

/**
 * Resolves the icon shown for an app according to the user's icon source:
 *  - system:    the app's own launcher icon (optionally reshaped)
 *  - themed:    Android 13+ monochrome layer, falling back to Lawnicons, then system
 *  - lawnicons: the bundled monochrome Lawnicons set, falling back to system
 *  - pack:<p>:  an installed ADW/Nova icon pack, falling back to its mask, then system
 * A per-app manual override (any drawable of any installed pack) wins over all of them.
 */
object IconManager {
    @Volatile
    private var appFilterMap: Map<String, String>? = null

    @Volatile
    private var initialized = false
    private val initLock = Any()
    private var initializing = false
    val ready = MutableLiveData(false)

    // Built from the application context only, so caching it cannot leak an activity.
    @SuppressLint("StaticFieldLeak")
    private var prefsRef: Prefs? = null
    private fun prefs(context: Context): Prefs =
        prefsRef ?: Prefs(context.applicationContext).also { prefsRef = it }

    private val packs = HashMap<String, IconPack>()
    fun pack(context: Context, packageName: String): IconPack = synchronized(packs) {
        packs.getOrPut(packageName) { IconPack(context, packageName) }
    }

    private data class CachedIcon(val state: Drawable.ConstantState, val bytes: Int)
    private val iconCache = object : LruCache<String, CachedIcon>(
        (Runtime.getRuntime().maxMemory() / 10).toInt().coerceIn(8 * 1024 * 1024, 48 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: CachedIcon) = value.bytes
    }

    private fun cached(context: Context, key: String): Drawable? =
        iconCache.get(key)?.state?.newDrawable(context.resources)?.mutate()

    private fun cache(key: String, drawable: Drawable) {
        val state = drawable.constantState ?: return
        val bytes = (drawable as? BitmapDrawable)?.bitmap?.allocationByteCount ?: (32 * 1024)
        iconCache.put(key, CachedIcon(state, bytes))
    }

    val hasLawnicons: Boolean
        get() = true

    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized || initializing) return
            initializing = true
            val appContext = context.applicationContext
            Thread {
                try {
                    loadAppFilter(appContext)
                    // Parse the selected pack up front so the first drawer frame does not pay for it.
                    IconSources.packPackage(prefs(appContext).iconSource)?.let { pack(appContext, it).load() }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                initialized = true
                ready.postValue(true)
            }.start()
        }
    }

    private fun loadAppFilter(context: Context) {
        val map = mutableMapOf<String, String>()
        try {
            context.assets.open("lawnicons/appfilter.xml").use { inputStream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(inputStream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) {
                            val cleanComponent = component.substringAfter("{").substringBefore("}")
                            map[cleanComponent] = drawable
                        }
                    }
                    eventType = parser.next()
                }
            }
            appFilterMap = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun renderSizePx(context: Context): Int = (RENDER_SIZE_DP * context.resources.displayMetrics.density).toInt()

    private fun getLawnicon(context: Context, packageName: String, activityName: String?): Drawable? {
        val filterMap = appFilterMap ?: return null
        val actName = activityName ?: ""

        val key = "$packageName/$actName"
        var drawableName = filterMap[key]
        if (drawableName == null) {
            // Fallback: match any key starting with "$packageName/"
            val prefix = "$packageName/"
            val firstMatch = filterMap.keys.firstOrNull { it.startsWith(prefix) }
            if (firstMatch != null) {
                drawableName = filterMap[firstMatch]
            }
        }

        if (drawableName != null) {
            try {
                val svgAssetPath = "lawnicons/svgs/$drawableName.svg"
                context.assets.open(svgAssetPath).use { inputStream ->
                    val svg = SVG.getFromInputStream(inputStream)
                    val size = renderSizePx(context)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    svg.renderToCanvas(canvas)
                    return BitmapDrawable(context.resources, bitmap)
                }
            } catch (e: Exception) {
                // SVG not found or fails to load/parse
            }
        }
        return null
    }

    private data class SystemIcon(val component: ComponentName?, val drawable: Drawable?)

    private fun systemIcon(context: Context, packageName: String, userHandle: UserHandle, activityName: String?): SystemIcon {
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val activities = launcherApps.getActivityList(packageName, userHandle)
            val info = activities.firstOrNull { !activityName.isNullOrEmpty() && it.componentName.className == activityName }
                ?: activities.firstOrNull()
            if (info != null) SystemIcon(info.componentName, info.getIcon(0))
            else SystemIcon(null, context.packageManager.getApplicationIcon(packageName))
        } catch (e: Exception) {
            SystemIcon(null, null)
        }
    }

    /** Draws a drawable from another pack chosen by hand: "<pack>/<drawable>". */
    private fun overrideIcon(context: Context, override: String): Drawable? {
        val packPkg = override.substringBefore('/', "")
        val name = override.substringAfter('/', "")
        if (packPkg.isEmpty() || name.isEmpty()) return null
        return pack(context, packPkg).drawableByName(name)
    }

    fun getAppIcon(
        context: Context,
        packageName: String,
        userHandle: UserHandle,
        tintColor: Int? = null,
        activityName: String? = null,
    ): Drawable? {
        init(context)
        val prefs = prefs(context)
        val source = prefs.iconSource
        val shape = prefs.iconShape
        val monoTint = prefs.iconTintMono
        val override = prefs.getIconOverride(packageName, userHandle.toString())
        val cacheKey = "$packageName|$activityName|$userHandle|$tintColor|$source|$shape|$monoTint|$override|$initialized"
        cached(context, cacheKey)?.let { return it }

        val res = context.resources
        val size = renderSizePx(context)
        var alreadyTinted = false
        var drawable: Drawable? = if (override.isNotEmpty()) overrideIcon(context, override) else null

        if (drawable == null) {
            val system = systemIcon(context, packageName, userHandle, activityName)
            val activity = system.component?.className ?: activityName
            fun lawnicon(): Drawable? = getLawnicon(context, packageName, activity)?.also {
                if (tintColor != null) it.mutate().setTint(tintColor)
                alreadyTinted = true
            }
            fun shapedSystem(): Drawable? = system.drawable?.let { IconShapes.reshape(it, shape, size, res) ?: it }

            val packPkg = IconSources.packPackage(source)
            drawable = when {
                packPkg != null -> {
                    val pack = pack(context, packPkg)
                    val component = system.component ?: ComponentName(packageName, activity.orEmpty())
                    pack.iconFor(component)
                        ?: system.drawable?.let { base ->
                            val reshaped = IconShapes.reshape(base, shape, size, res) ?: base
                            pack.applyMask(reshaped, size, packageName.hashCode())
                        }
                        ?: shapedSystem()
                }

                source == IconSources.THEMED -> {
                    val mono = system.drawable?.let { IconShapes.monochrome(it, tintColor ?: DEFAULT_TINT, size, res) }
                    if (mono != null) alreadyTinted = true
                    mono ?: lawnicon() ?: shapedSystem()
                }

                source == IconSources.SYSTEM -> shapedSystem()
                else -> lawnicon() ?: shapedSystem()
            }
        }

        if (drawable != null && monoTint && !alreadyTinted && tintColor != null) {
            drawable = drawable.mutate().also { it.setTint(tintColor) }
        }
        if (drawable != null) cache(cacheKey, drawable)
        return drawable
    }

    fun getShortcutIcon(context: Context, packageName: String, shortcutId: String, userHandle: UserHandle): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val cacheKey = "$packageName|shortcut|$shortcutId|$userHandle"
        cached(context, cacheKey)?.let { return it }

        var drawable: Drawable? = null
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setShortcutIds(listOf(shortcutId))
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            }
            val shortcuts = launcherApps.getShortcuts(query, userHandle)
            val shortcutInfo = shortcuts?.firstOrNull()
            if (shortcutInfo != null) {
                drawable = launcherApps.getShortcutIconDrawable(shortcutInfo, context.resources.displayMetrics.densityDpi)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (drawable != null) {
            cache(cacheKey, drawable)
        }
        return drawable
    }

    fun getModelIcon(context: Context, appModel: AppModel, tintColor: Int): Drawable? {
        return when (appModel) {
            is AppModel.App -> {
                getAppIcon(context, appModel.appPackage, appModel.user, tintColor, appModel.activityClassName)
            }
            is AppModel.PinnedShortcut -> {
                var icon = getShortcutIcon(context, appModel.appPackage, appModel.shortcutId, appModel.user)
                if (icon == null) {
                    icon = getAppIcon(context, appModel.appPackage, appModel.user, tintColor)
                }
                icon
            }
            is AppModel.SettingTile -> {
                val icon = ContextCompat.getDrawable(context, R.drawable.ic_sc_settings)
                icon?.mutate()?.apply { setTint(tintColor) }
            }
            is AppModel.Contact -> {
                val icon = ContextCompat.getDrawable(context, R.drawable.ic_sc_person)
                icon?.mutate()?.apply { setTint(tintColor) }
            }
            is AppModel.FolderHeader -> {
                val icon = ContextCompat.getDrawable(context, R.drawable.ic_sc_folder)
                icon?.mutate()?.apply { setTint(tintColor) }
            }
            else -> null
        }
    }

    /** Icon size for a list row, honouring the user's icon-size scale. */
    fun listIconSize(context: Context, textSizePx: Float): Int =
        (textSizePx * 1.2f * prefs(context).iconSizeScale.coerceIn(0.5f, 2.5f)).toInt()

    fun clearCache() {
        iconCache.evictAll()
    }

    // Large enough for enlarged grid icons, small enough to keep ~100 icons cached.
    private const val RENDER_SIZE_DP = 64
    private const val DEFAULT_TINT = -0x1 // white
}

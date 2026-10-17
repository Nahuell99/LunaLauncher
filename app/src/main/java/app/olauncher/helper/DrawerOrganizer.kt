package app.olauncher.helper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Orders the main drawer list (A–Z, most used, recently used) and optionally groups it
 * under category headers. Categories come from the app's manifest category (Android 8+)
 * unless the user assigned one by hand.
 */
object DrawerOrganizer {
    const val SORT_ALPHA = 0
    const val SORT_MOST_USED = 1
    const val SORT_RECENT = 2

    private val systemCategory = ConcurrentHashMap<String, Int>()

    fun organize(context: Context, prefs: Prefs, list: List<AppModel>): List<AppModel> {
        val sort = prefs.drawerSort
        val group = prefs.drawerGroupByCategory
        if (sort == SORT_ALPHA && !group) return list

        // Private-space header and its apps stay at the end, untouched.
        val privateIndex = list.indexOfFirst { it is AppModel.PrivateSpaceHeader }
        val head = if (privateIndex >= 0) list.subList(0, privateIndex) else list
        val tail = if (privateIndex >= 0) list.subList(privateIndex, list.size) else emptyList()

        val key = { app: AppModel -> app.appPackage + "|" + app.user }
        val comparator: Comparator<AppModel> = when (sort) {
            SORT_MOST_USED -> compareByDescending<AppModel> { prefs.getUsageCount(key(it)) }.then(naturalOrder())
            SORT_RECENT -> compareByDescending<AppModel> { prefs.getLastUsed(key(it)) }.then(naturalOrder())
            else -> naturalOrder()
        }
        // Rows without a package (picker defaults) keep their place at the top.
        val fixed = head.takeWhile { it.appPackage.isEmpty() }
        val apps = head.drop(fixed.size)

        if (!group) return fixed + apps.sortedWith(comparator) + tail

        val other = context.getString(R.string.category_other)
        val grouped = apps.groupBy { categoryOf(context, prefs, it.appPackage) ?: other }
        val out = ArrayList<AppModel>(apps.size + grouped.size + fixed.size + tail.size)
        out += fixed
        grouped.keys.sortedWith(compareBy<String> { it == other }.thenBy { it.lowercase() }).forEach { name ->
            out += AppModel.CategoryHeader(name)
            out += grouped.getValue(name).sortedWith(comparator)
        }
        return out + tail
    }

    /** Category title for an app: manual pick, else Android's, else null. */
    fun categoryOf(context: Context, prefs: Prefs, packageName: String): String? {
        if (packageName.isEmpty()) return null
        prefs.getAppCategory(packageName).takeIf { it.isNotEmpty() }?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val category = systemCategory.getOrPut(packageName) {
            try {
                context.packageManager.getApplicationInfo(packageName, 0).category
            } catch (_: Exception) {
                ApplicationInfo.CATEGORY_UNDEFINED
            }
        }
        if (category == ApplicationInfo.CATEGORY_UNDEFINED) return null
        return ApplicationInfo.getCategoryTitle(context, category)?.toString()
    }

    /** Every category name currently in use, for the manual picker. */
    fun knownCategories(context: Context, prefs: Prefs, packages: List<String>): List<String> =
        (packages.mapNotNull { categoryOf(context, prefs, it) } + prefs.customCategories())
            .distinct().sortedBy { it.lowercase() }
}

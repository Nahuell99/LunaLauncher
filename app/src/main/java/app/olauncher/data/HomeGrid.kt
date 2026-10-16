package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One element placed on the free-form home grid. Positions are in cells; [spanX] and
 * [spanY] let widgets (and enlarged icons) cover several cells.
 */
data class GridItem(
    val id: String = UUID.randomUUID().toString(),
    val type: Int,
    val col: Int,
    val row: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val pkg: String = "",
    val cls: String = "",
    val user: String = "",
    val label: String = "",
    val shortcutId: String = "",
    val folderId: String = "",
    // TYPE_APP_WIDGET: bound id (device-local) plus the provider used to re-bind it.
    val appWidgetId: Int = -1,
    val provider: String = "",
    // TYPE_CUSTOM_WIDGET: id of a definition in [Prefs.customWidgetDefs].
    val widgetDefId: String = "",
    // Free-form per-item payload (e.g. the text of a note widget).
    val data: String = "",
) {
    val isIcon: Boolean get() = type == TYPE_APP || type == TYPE_SHORTCUT || type == TYPE_FOLDER

    companion object {
        const val TYPE_APP = 0
        const val TYPE_SHORTCUT = 1
        const val TYPE_FOLDER = 2
        const val TYPE_APP_WIDGET = 3
        const val TYPE_CUSTOM_WIDGET = 4
    }
}

object HomeGrid {
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 10
    const val MIN_ROWS = 2
    const val MAX_ROWS = 14

    fun toJson(items: List<GridItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type)
                    .put("col", item.col)
                    .put("row", item.row)
                    .put("spanX", item.spanX)
                    .put("spanY", item.spanY)
                    .put("pkg", item.pkg)
                    .put("cls", item.cls)
                    .put("user", item.user)
                    .put("label", item.label)
                    .put("shortcutId", item.shortcutId)
                    .put("folderId", item.folderId)
                    .put("appWidgetId", item.appWidgetId)
                    .put("provider", item.provider)
                    .put("widgetDefId", item.widgetDefId)
                    .put("data", item.data)
            )
        }
        return array.toString()
    }

    fun fromJson(json: String): List<GridItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                GridItem(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    type = o.optInt("type", GridItem.TYPE_APP),
                    col = o.optInt("col").coerceAtLeast(0),
                    row = o.optInt("row").coerceAtLeast(0),
                    spanX = o.optInt("spanX", 1).coerceIn(1, MAX_COLUMNS),
                    spanY = o.optInt("spanY", 1).coerceIn(1, MAX_ROWS),
                    pkg = o.optString("pkg"),
                    cls = o.optString("cls"),
                    user = o.optString("user"),
                    label = o.optString("label"),
                    shortcutId = o.optString("shortcutId"),
                    folderId = o.optString("folderId"),
                    appWidgetId = o.optInt("appWidgetId", -1),
                    provider = o.optString("provider"),
                    widgetDefId = o.optString("widgetDefId"),
                    data = o.optString("data"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun overlaps(a: GridItem, col: Int, row: Int, spanX: Int, spanY: Int): Boolean =
        a.col < col + spanX && col < a.col + a.spanX && a.row < row + spanY && row < a.row + a.spanY

    /** True when the rectangle fits inside the grid and touches no other item. */
    fun isFree(
        items: List<GridItem>, columns: Int, rows: Int,
        col: Int, row: Int, spanX: Int, spanY: Int, ignoreId: String? = null,
    ): Boolean {
        if (col < 0 || row < 0 || col + spanX > columns || row + spanY > rows) return false
        return items.none { it.id != ignoreId && overlaps(it, col, row, spanX, spanY) }
    }

    /** First free top-left cell (row-major) for a [spanX]×[spanY] item, or null if full. */
    fun findFree(items: List<GridItem>, columns: Int, rows: Int, spanX: Int, spanY: Int): Pair<Int, Int>? {
        for (row in 0..rows - spanY) for (col in 0..columns - spanX) {
            if (isFree(items, columns, rows, col, row, spanX, spanY)) return col to row
        }
        return null
    }

    fun itemAt(items: List<GridItem>, col: Int, row: Int): GridItem? =
        items.lastOrNull { overlaps(it, col, row, 1, 1) }
}

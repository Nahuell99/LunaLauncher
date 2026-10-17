package app.olauncher.data

/** Where an item lives: the free-form grid or the dock (one row of icons). */
enum class GridSurface { GRID, DOCK }

/** Small, UI-free operations on the stored home grid and dock. */
object GridOps {

    private const val DOCK_PREFIX = "dock:"

    fun items(prefs: Prefs, surface: GridSurface): List<GridItem> =
        if (surface == GridSurface.DOCK) prefs.dockItems else prefs.gridItems

    private fun store(prefs: Prefs, surface: GridSurface, items: List<GridItem>) {
        if (surface == GridSurface.DOCK) prefs.dockItems = items else prefs.gridItems = items
    }

    fun columns(prefs: Prefs, surface: GridSurface): Int =
        if (surface == GridSurface.DOCK) prefs.dockColumns.coerceIn(3, 8) else prefs.gridColumns

    fun rows(prefs: Prefs, surface: GridSurface): Int =
        if (surface == GridSurface.DOCK) 1 else prefs.gridRows

    fun surfaceOf(prefs: Prefs, id: String): GridSurface =
        if (prefs.dockItems.any { it.id == id }) GridSurface.DOCK else GridSurface.GRID

    fun itemFor(appModel: AppModel, col: Int, row: Int): GridItem? = when (appModel) {
        is AppModel.App -> GridItem(
            type = GridItem.TYPE_APP, col = col, row = row,
            pkg = appModel.appPackage, cls = appModel.activityClassName.orEmpty(),
            user = appModel.user.toString(), label = appModel.appLabel,
        )

        is AppModel.PinnedShortcut -> GridItem(
            type = GridItem.TYPE_SHORTCUT, col = col, row = row,
            pkg = appModel.appPackage, user = appModel.user.toString(),
            label = appModel.appLabel, shortcutId = appModel.shortcutId,
        )

        else -> null
    }

    /** Adds [item] at its own cell when free, otherwise at the first free cell. */
    fun add(prefs: Prefs, item: GridItem, surface: GridSurface = GridSurface.GRID): Boolean {
        val items = items(prefs, surface)
        val columns = columns(prefs, surface)
        val rows = rows(prefs, surface)
        val placed = if (HomeGrid.isFree(items, columns, rows, item.col, item.row, item.spanX, item.spanY)) {
            item
        } else {
            val (col, row) = HomeGrid.findFree(items, columns, rows, item.spanX, item.spanY) ?: return false
            item.copy(col = col, row = row)
        }
        store(prefs, surface, items + placed)
        return true
    }

    /** Value for [Prefs.gridPendingCell]: the cell the user tapped on a surface. */
    fun pendingCell(surface: GridSurface, col: Int, row: Int): String =
        (if (surface == GridSurface.DOCK) DOCK_PREFIX else "") + "$col,$row"

    /** Puts an app/shortcut at the cell the user tapped (see [Prefs.gridPendingCell]). */
    fun placeAtPendingCell(prefs: Prefs, appModel: AppModel): Boolean {
        val pending = prefs.gridPendingCell
        prefs.gridPendingCell = ""
        val surface = if (pending.startsWith(DOCK_PREFIX)) GridSurface.DOCK else GridSurface.GRID
        val (col, row) = parseCell(pending.removePrefix(DOCK_PREFIX)) ?: (0 to 0)
        val item = itemFor(appModel, col, row) ?: return false
        return add(prefs, item, surface)
    }

    fun addAnywhere(prefs: Prefs, appModel: AppModel, surface: GridSurface = GridSurface.GRID): Boolean {
        val item = itemFor(appModel, -1, -1) ?: return false
        return add(prefs, item, surface)
    }

    /** Replaces the item with the same id, on whichever surface holds it. */
    fun update(prefs: Prefs, item: GridItem) {
        val surface = surfaceOf(prefs, item.id)
        store(prefs, surface, items(prefs, surface).map { if (it.id == item.id) item else it })
    }

    fun remove(prefs: Prefs, id: String) {
        prefs.gridItems = prefs.gridItems.filterNot { it.id == id }
        prefs.dockItems = prefs.dockItems.filterNot { it.id == id }
    }

    fun parseCell(value: String): Pair<Int, Int>? {
        val parts = value.split(',')
        if (parts.size < 2) return null
        val col = parts[0].toIntOrNull() ?: return null
        val row = parts[1].toIntOrNull() ?: return null
        return col to row
    }
}

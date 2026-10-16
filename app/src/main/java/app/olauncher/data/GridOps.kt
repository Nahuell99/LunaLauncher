package app.olauncher.data

/** Small, UI-free operations on the stored home grid. */
object GridOps {

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
    fun add(prefs: Prefs, item: GridItem): Boolean {
        val items = prefs.gridItems
        val placed = if (HomeGrid.isFree(items, prefs.gridColumns, prefs.gridRows, item.col, item.row, item.spanX, item.spanY)) {
            item
        } else {
            val (col, row) = HomeGrid.findFree(items, prefs.gridColumns, prefs.gridRows, item.spanX, item.spanY) ?: return false
            item.copy(col = col, row = row)
        }
        prefs.gridItems = items + placed
        return true
    }

    /** Puts an app/shortcut at the cell the user tapped (see [Prefs.gridPendingCell]). */
    fun placeAtPendingCell(prefs: Prefs, appModel: AppModel): Boolean {
        val (col, row) = parseCell(prefs.gridPendingCell) ?: (0 to 0)
        prefs.gridPendingCell = ""
        val item = itemFor(appModel, col, row) ?: return false
        return add(prefs, item)
    }

    fun addAnywhere(prefs: Prefs, appModel: AppModel): Boolean {
        val item = itemFor(appModel, -1, -1) ?: return false
        return add(prefs, item)
    }

    fun update(prefs: Prefs, item: GridItem) {
        prefs.gridItems = prefs.gridItems.map { if (it.id == item.id) item else it }
    }

    fun remove(prefs: Prefs, id: String) {
        prefs.gridItems = prefs.gridItems.filterNot { it.id == id }
    }

    fun parseCell(value: String): Pair<Int, Int>? {
        val parts = value.split(',')
        if (parts.size < 2) return null
        val col = parts[0].toIntOrNull() ?: return null
        val row = parts[1].toIntOrNull() ?: return null
        return col to row
    }
}

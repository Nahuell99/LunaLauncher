package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 5.1: the grid, dock, widgets, clock styles and icon picks survive a backup. */
class PreferenceBackupTest {

    // Delegate-declared keys get their type from Prefs at runtime; mirror that here.
    private val types = mapOf("DRAWER_SORT" to "i", "DOCK_COLUMNS" to "i", "CLOCK_STYLE_ID" to "s")

    private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)

    private fun backup(extra: JSONObject.() -> Unit) = JSONObject()
        .put("BACKUP_FORMAT_VERSION", entry("i", 1))
        .apply(extra)
        .toString()

    private val grid = HomeGrid.toJson(
        listOf(
            GridItem(id = "a", type = GridItem.TYPE_APP, col = 0, row = 0, pkg = "p.a", user = "UserHandle{0}"),
            GridItem(id = "w", type = GridItem.TYPE_APP_WIDGET, col = 1, row = 0, spanX = 2, appWidgetId = 7, provider = "x/.Y"),
        )
    )

    @Test
    fun `new keys round trip with their types`() {
        val json = backup {
            put("GRID_ITEMS", entry("s", grid))
            put("DOCK_ITEMS", entry("s", "[]"))
            put("CLOCK_STYLE_DEFS", entry("s", JSONArray().toString()))
            put("CLOCK_STYLE_ID", entry("s", "clock.thin"))
            put("ICON_OVERRIDE_p.a|UserHandle{0}", entry("s", "pack.x/icon_a"))
            put("LAST_USED_p.a|UserHandle{0}", entry("l", 1_790_000_000_000L))
            put("DRAWER_SORT", entry("i", 2))
            put("DOCK_COLUMNS", entry("i", 5))
        }
        val parsed = PreferenceBackup.parse(json, types).preferences
        assertEquals(grid, parsed["GRID_ITEMS"])
        assertEquals("pack.x/icon_a", parsed["ICON_OVERRIDE_p.a|UserHandle{0}"])
        assertEquals(1_790_000_000_000L, parsed["LAST_USED_p.a|UserHandle{0}"])
        assertEquals(2, parsed["DRAWER_SORT"])
    }

    @Test
    fun `corrupt grid or out of range values reject the whole backup`() {
        val broken = listOf(
            backup { put("GRID_ITEMS", entry("s", "{not an array")) },
            backup { put("CLOCK_STYLE_DEFS", entry("s", "nope")) },
            backup { put("DOCK_COLUMNS", entry("i", 42)) },
            backup { put("LAST_USED_x", entry("s", "yesterday")) },
        )
        for (json in broken) assertTrue(json, runCatching { PreferenceBackup.parse(json, types) }.isFailure)
    }
}

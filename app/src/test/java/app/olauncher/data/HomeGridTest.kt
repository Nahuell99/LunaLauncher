package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {

    private fun app(col: Int, row: Int, spanX: Int = 1, spanY: Int = 1, id: String = "$col,$row") =
        GridItem(id = id, type = GridItem.TYPE_APP, col = col, row = row, spanX = spanX, spanY = spanY, pkg = "p.$id")

    @Test
    fun `json round trip keeps every field`() {
        val items = listOf(
            app(0, 0),
            GridItem(
                id = "w", type = GridItem.TYPE_APP_WIDGET, col = 1, row = 2, spanX = 3, spanY = 2,
                appWidgetId = 42, provider = "a.b/.C", label = "Clock",
            ),
            GridItem(id = "c", type = GridItem.TYPE_CUSTOM_WIDGET, col = 0, row = 5, widgetDefId = "builtin.note", data = "hi\n\"there\""),
        )
        assertEquals(items, HomeGrid.fromJson(HomeGrid.toJson(items)))
    }

    @Test
    fun `corrupt json reads as an empty grid`() {
        assertEquals(emptyList<GridItem>(), HomeGrid.fromJson("{not json"))
        assertEquals(emptyList<GridItem>(), HomeGrid.fromJson(""))
    }

    @Test
    fun `overlapping and out of bounds rectangles are not free`() {
        val items = listOf(app(1, 1, spanX = 2, spanY = 2, id = "big"))
        assertFalse(HomeGrid.isFree(items, 4, 4, 2, 2, 1, 1))
        assertFalse(HomeGrid.isFree(items, 4, 4, 0, 0, 2, 2))
        assertTrue(HomeGrid.isFree(items, 4, 4, 3, 0, 1, 4))
        assertFalse(HomeGrid.isFree(items, 4, 4, 3, 0, 2, 1)) // past the right edge
        assertFalse(HomeGrid.isFree(items, 4, 4, -1, 0, 1, 1))
        // An item never collides with itself while being moved or resized.
        assertTrue(HomeGrid.isFree(items, 4, 4, 1, 1, 3, 3, ignoreId = "big"))
    }

    @Test
    fun `findFree scans row by row and reports a full grid`() {
        val items = listOf(app(0, 0), app(1, 0))
        assertEquals(2 to 0, HomeGrid.findFree(items, 3, 2, 1, 1))
        assertEquals(0 to 1, HomeGrid.findFree(items, 3, 2, 2, 1))
        assertNull(HomeGrid.findFree(items, 2, 1, 1, 1))
    }

    @Test
    fun `itemAt finds spanned items`() {
        val items = listOf(app(1, 1, spanX = 2, spanY = 2, id = "big"))
        assertEquals("big", HomeGrid.itemAt(items, 2, 2)?.id)
        assertNull(HomeGrid.itemAt(items, 0, 0))
    }

    @Test
    fun `pending cell parsing`() {
        assertEquals(3 to 4, GridOps.parseCell("3,4"))
        assertNull(GridOps.parseCell(""))
        assertNull(GridOps.parseCell("x,1"))
    }
}

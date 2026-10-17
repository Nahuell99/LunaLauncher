package app.olauncher.helper.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchExtrasTest {

    @Test
    fun `calculator follows precedence and ignores non math`() {
        assertEquals(40.0, SearchExtras.calculate("12*3+4")!!, 1e-9)
        assertEquals(1024.0, SearchExtras.calculate("=2^10")!!, 1e-9)
        assertEquals(-2.0, SearchExtras.calculate("-(1+1)")!!, 1e-9)
        assertEquals(2.5, SearchExtras.calculate("5 / 2")!!, 1e-9)
        assertEquals(3.0, SearchExtras.calculate("=sqrt 9")!!, 1e-9)
        assertEquals(1.5, SearchExtras.calculate("1,5*1")!!, 1e-9)
        assertNull(SearchExtras.calculate("2048"))   // a number alone is not a sum
        assertNull(SearchExtras.calculate("maps"))
        assertNull(SearchExtras.calculate("1+"))
        assertNull(SearchExtras.calculate("1/0"))     // not finite
    }

    @Test
    fun `numbers are formatted compactly`() {
        assertEquals("40", SearchExtras.formatNumber(40.0))
        assertEquals("0.3333333333", SearchExtras.formatNumber(1.0 / 3))
    }

    @Test
    fun `unit conversion in spanish and english`() {
        assertEquals("10 km = 6.213711922 mi", SearchExtras.convert("10 km a mi"))
        assertEquals("30 c = 86 f", SearchExtras.convert("30 c to f"))
        assertEquals("1 kg = 2.204622622 lb", SearchExtras.convert("1 kg en lb"))
        assertEquals("90 min = 1.5 h", SearchExtras.convert("90 min h"))
        assertNull(SearchExtras.convert("10 km a kg"))  // different quantities
        assertNull(SearchExtras.convert("hola mundo"))
    }

    @Test
    fun `commands match by prefix and keep the argument`() {
        val alarm = SearchExtras.commands("> alarma 7:30")
        assertEquals(listOf(SearchExtras.Command("alarm", "7:30")), alarm)
        assertTrue(SearchExtras.commands(">").size > 5)
        assertTrue(SearchExtras.commands("wifi").isEmpty())  // only with ">"
        assertEquals("torch", SearchExtras.commands(">linterna").single().id)
    }

    @Test
    fun `time and duration parsing`() {
        assertEquals(7 to 30, SearchExtras.parseTime("7:30"))
        assertEquals(22 to 0, SearchExtras.parseTime("22"))
        assertNull(SearchExtras.parseTime("25:00"))
        assertEquals(600, SearchExtras.parseDuration("10m"))
        assertEquals(5400, SearchExtras.parseDuration("1h30m"))
        assertEquals(300, SearchExtras.parseDuration("5"))
        assertNull(SearchExtras.parseDuration("diez"))
    }
}

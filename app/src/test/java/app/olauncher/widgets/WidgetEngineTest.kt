package app.olauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class WidgetEngineTest {

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 9, 5, 0) }.timeInMillis

    private fun data(
        alarm: Long? = null,
        media: MediaInfo? = null,
    ) = WidgetData(
        now = now, battery = 73, charging = true, nextAlarm = alarm, screenTime = "1h 2m",
        event = null, media = media, mediaPermission = true, greeting = "Hola", is24h = true,
    )

    @Test
    fun `tokens expand with arguments and fallbacks`() {
        assertEquals("09:05", WidgetTokens.resolve("{time}", data(), ""))
        assertEquals("2026", WidgetTokens.resolve("{date:yyyy}", data(), ""))
        assertEquals("73% ⚡", WidgetTokens.resolve("{battery}% {charging}", data(), ""))
        assertEquals("Sin alarma", WidgetTokens.resolve("{alarm|Sin alarma}", data(), ""))
        assertEquals("Nada", WidgetTokens.resolve("{media_title|Nada}", data(), ""))
        assertEquals("Song", WidgetTokens.resolve("{media_title|Nada}", data(media = MediaInfo("Song", "", "", true)), ""))
    }

    @Test
    fun `unknown tokens stay visible`() {
        assertEquals("{nope}", WidgetTokens.resolve("{nope}", data(), ""))
    }

    @Test
    fun `item data feeds notes and countdowns`() {
        assertEquals("70", WidgetTokens.resolve("{days_until}", data(), "2026-12-25 Navidad"))
        assertEquals("Navidad", WidgetTokens.resolve("{data_rest}", data(), "2026-12-25 Navidad"))
        assertEquals("?", WidgetTokens.resolve("{days_until|?}", data(), "not a date"))
        assertEquals("Comprar pan", WidgetTokens.resolve("{data|vacío}", data(), "Comprar pan"))
    }

    @Test
    fun `parse rejects anything that is not a widget`() {
        val invalid = listOf(
            """{"lines":[{"text":"x"}]}""",
            """{"format":"lunalauncher-widget","lines":[]}""",
            """{"format":"lunalauncher-widget","lines":[{"type":"script","text":"x"}]}""",
            """{"format":"lunalauncher-widget","version":2,"lines":[{"text":"x"}]}""",
            "not json",
        )
        for (json in invalid) assertTrue(json, runCatching { WidgetDef.parse(json) }.isFailure)
    }

    @Test
    fun `bundled widgets are valid and media detection works`() {
        val dir = File("src/main/assets/widgets")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no bundled widgets found in ${dir.absolutePath}", files.isNotEmpty())
        for (file in files) {
            val def = WidgetDef.parse(file.readText())
            assertTrue(file.name, def.id.startsWith("builtin."))
        }
        assertTrue(WidgetDef.parse(File(dir, "08_now_playing.json").readText()).usesMedia)
        assertFalse(WidgetDef.parse(File(dir, "01_clock_big.json").readText()).usesMedia)
    }

    private fun at(hour: Int, minute: Int) =
        Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, hour, minute, 0) }.timeInMillis

    @Test
    fun `time in words rounds to five minutes in both languages`() {
        assertEquals("diez y cuarto", TimeWords.format(at(10, 14), "es"))
        assertEquals("Son las once menos veinte", TimeWords.format(at(10, 41), "es", full = true))
        assertEquals("Es la una en punto", TimeWords.format(at(12, 58), "es", full = true))
        assertEquals("doce en punto", TimeWords.format(at(23, 58), "es"))
        assertEquals("quarter past ten", TimeWords.format(at(22, 15), "en"))
        assertEquals("twenty-five to eight", TimeWords.format(at(7, 35), "en"))
        assertEquals("It's nine o'clock", TimeWords.format(at(9, 1), "en", full = true))
    }

    @Test
    fun `binary clock pads to fixed width`() {
        // 09:05 -> hours 01001, minutes 000101
        assertEquals("○ ● ○ ○ ●", WidgetTokens.resolve("{binary_h}", data(), ""))
        assertEquals("0 0 0 1 0 1", WidgetTokens.resolve("{binary_m:10}", data(), ""))
    }

    @Test
    fun `bundled clock styles are valid`() {
        val files = File("src/main/assets/clock_styles").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.size >= 8)
        for (file in files) assertTrue(file.name, WidgetDef.parse(file.readText()).id.startsWith("clock."))
    }

    @Test
    fun `files written before the rename still import`() {
        val legacy = """{"format":"olauncher-widget","lines":[{"text":"{time}"}]}"""
        assertEquals(WidgetDef.FORMAT, WidgetDef.parse(legacy).json.getString("format"))
    }
}

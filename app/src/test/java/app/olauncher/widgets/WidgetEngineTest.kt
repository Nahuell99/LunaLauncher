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
            """{"format":"olauncher-widget","lines":[]}""",
            """{"format":"olauncher-widget","lines":[{"type":"script","text":"x"}]}""",
            """{"format":"olauncher-widget","version":2,"lines":[{"text":"x"}]}""",
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
}

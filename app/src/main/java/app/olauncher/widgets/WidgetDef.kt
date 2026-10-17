package app.olauncher.widgets

import android.content.Context
import app.olauncher.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * A declarative home widget ("lunalauncher-widget" JSON). Widgets are data only — text
 * lines with tokens, bars, spacers and media buttons — never code, so importing one
 * from anywhere is safe. The format is documented in docs/widgets.md.
 */
class WidgetDef private constructor(val json: JSONObject, val builtIn: Boolean) {

    val id: String get() = json.optString("id")
    val spanX: Int get() = json.optInt("spanX", 4).coerceIn(1, 10)
    val spanY: Int get() = json.optInt("spanY", 1).coerceIn(1, 14)
    val tap: String get() = json.optString("tap", TAP_NONE)

    fun name(locale: Locale = Locale.getDefault()): String =
        json.optString("name_${locale.language}").ifEmpty { json.optString("name").ifEmpty { id } }

    /** True when any text in the widget references `{token...}`. */
    fun uses(token: String): Boolean = json.toString().contains("{$token")
    val usesMedia: Boolean get() = uses("media") || hasLineType(json.optJSONArray("lines"), "media_controls")

    private fun hasLineType(lines: JSONArray?, type: String): Boolean {
        lines ?: return false
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            if (line.optString("type") == type) return true
            if (hasLineType(line.optJSONArray("items"), type)) return true
        }
        return false
    }

    fun export(): String = JSONObject(json.toString()).apply { remove("builtIn") }.toString(2)

    companion object {
        const val FORMAT = "lunalauncher-widget"
        // Files written before the rename to LunaLauncher.
        private const val LEGACY_FORMAT = "olauncher-widget"
        const val MAX_BYTES = 64 * 1024

        const val TAP_NONE = "none"
        const val TAP_CLOCK = "clock"
        const val TAP_CALENDAR = "calendar"
        const val TAP_EDIT = "edit"
        const val TAP_MEDIA = "media"
        const val TAP_APP_PREFIX = "app:"

        private val LINE_TYPES = setOf("text", "bar", "space", "media_controls", "row")

        /** Widgets for the home grid. */
        val WIDGETS = Library("widgets", "builtin.", "user.", { it.customWidgetDefs }, { p, v -> p.customWidgetDefs = v })

        /** Styles for the home clock / date block (Fase 4). Also usable as grid widgets. */
        val CLOCKS = Library("clock_styles", "clock.", "userclock.", { it.clockStyleDefs }, { p, v -> p.clockStyleDefs = v })

        fun findAny(context: Context, prefs: Prefs, id: String): WidgetDef? =
            WIDGETS.find(context, prefs, id) ?: CLOCKS.find(context, prefs, id)

        /** Parses and validates a definition; throws on anything malformed. */
        fun parse(text: String, builtIn: Boolean = false): WidgetDef {
            require(text.length <= MAX_BYTES) { "Too large" }
            val json = JSONObject(text)
            require(json.optString("format") in setOf(FORMAT, LEGACY_FORMAT)) { "Not a $FORMAT file" }
            json.put("format", FORMAT)
            require(json.optInt("version", 1) == 1) { "Unsupported version" }
            val lines = json.optJSONArray("lines")
            require(lines != null && lines.length() in 1..24) { "A widget needs 1..24 lines" }
            validateLines(lines, depth = 0)
            if (json.optString("name").length > 60) json.put("name", json.optString("name").take(60))
            return WidgetDef(json, builtIn)
        }

        private fun validateLines(lines: JSONArray, depth: Int) {
            require(depth <= 1) { "Rows cannot be nested" }
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                val type = line.optString("type", "text")
                require(type in LINE_TYPES) { "Unknown line type $type" }
                require(line.optString("text").length <= 400) { "Line too long" }
                if (type == "row") validateLines(line.getJSONArray("items"), depth + 1)
            }
        }
    }

    /**
     * Bundled definitions (an assets folder) plus the user's imported / edited ones (a JSON
     * array in prefs). Grid widgets and home clock styles are two libraries of the same format.
     */
    class Library(
        private val assetDir: String,
        private val builtInPrefix: String,
        private val userPrefix: String,
        private val read: (Prefs) -> String,
        private val write: (Prefs, String) -> Unit,
    ) {
        @Volatile
        private var builtInCache: List<WidgetDef>? = null

        fun builtIns(context: Context): List<WidgetDef> {
            builtInCache?.let { return it }
            val assets = context.assets
            val defs = (assets.list(assetDir) ?: emptyArray())
                .filter { it.endsWith(".json") }
                .sorted()
                .mapNotNull { file ->
                    runCatching {
                        val text = assets.open("$assetDir/$file").bufferedReader().use { it.readText() }
                        parse(text, builtIn = true).also {
                            if (it.id.isEmpty()) it.json.put("id", builtInPrefix + file.removeSuffix(".json"))
                        }
                    }.getOrNull()
                }
            builtInCache = defs
            return defs
        }

        fun userDefs(prefs: Prefs): List<WidgetDef> {
            val raw = read(prefs)
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    runCatching { parse(array.getJSONObject(i).toString()) }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }

        fun all(context: Context, prefs: Prefs): List<WidgetDef> = builtIns(context) + userDefs(prefs)

        fun find(context: Context, prefs: Prefs, id: String): WidgetDef? =
            all(context, prefs).firstOrNull { it.id == id }

        fun isUserId(id: String) = id.startsWith(userPrefix)

        /** Validates [text] and stores it as a new user definition. Returns the stored definition. */
        fun import(context: Context, prefs: Prefs, text: String): WidgetDef {
            val def = parse(text)
            val existing = all(context, prefs).map { it.id }.toSet()
            // Imported ids live in their own namespace and never shadow bundled definitions.
            if (!isUserId(def.id) || def.id in existing) def.json.put("id", newId())
            save(prefs, userDefs(prefs) + def)
            return def
        }

        /**
         * Stores an edited definition: replaces the user definition with the same id, or saves
         * a copy under a new user id when it started from a bundled one.
         */
        fun upsert(prefs: Prefs, text: String): WidgetDef {
            val def = parse(text)
            val defs = userDefs(prefs)
            if (!isUserId(def.id) || defs.none { it.id == def.id }) {
                if (!isUserId(def.id)) def.json.put("id", newId())
                save(prefs, defs + def)
            } else {
                save(prefs, defs.map { if (it.id == def.id) def else it })
            }
            return def
        }

        fun delete(prefs: Prefs, id: String) = save(prefs, userDefs(prefs).filterNot { it.id == id })

        private fun newId() = userPrefix + UUID.randomUUID().toString().take(8)

        private fun save(prefs: Prefs, defs: List<WidgetDef>) {
            write(prefs, JSONArray().apply { defs.forEach { put(it.json) } }.toString())
        }
    }
}

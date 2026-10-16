package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject

/** Validates the complete document before Prefs changes any device state. */
internal data class PreferenceBackup(
    val preferences: Map<String, Any>,
    val fontBase64: String,
) {
    companion object {
        const val MAX_FONT_BYTES = 5 * 1024 * 1024
        const val MAX_BACKUP_BYTES = 10 * 1024 * 1024
        private const val VERSION_KEY = "BACKUP_FORMAT_VERSION"
        private val homeString = Regex("(?:APP_NAME|APP_PACKAGE|APP_ACTIVITY_CLASS_NAME|APP_USER|SHORTCUT_ID|FOLDER_ID)_[1-8]")
        private val homeBoolean = Regex("(?:IS_SHORTCUT|IS_FOLDER)_[1-8]")
        private val iconString = Regex("SHORTCUT_(?:PKG|CLS|USR|LBL)_[0-7]")
        private val iconIndex = Regex("SHORTCUT_ICON_INDEX_[0-7]")
        private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

        fun parse(json: String, types: Map<String, String>): PreferenceBackup {
            require(json.length <= MAX_BACKUP_BYTES)
            val root = JSONObject(json)
            val values = mutableMapOf<String, Any>()
            var font = ""
            for (key in root.keys()) {
                val entry = root.getJSONObject(key)
                val type = entry.getString("t")
                if (key == VERSION_KEY) {
                    require(type == "i" && entry.get("v") == 1)
                    continue
                }
                val expected = when {
                    key == "CUSTOM_FONT_BASE64" -> "s"
                    key == "FOLDERS" -> "s"
                    types.containsKey(key) -> types.getValue(key)
                    homeString.matches(key) || iconString.matches(key) -> "s"
                    homeBoolean.matches(key) -> "b"
                    iconIndex.matches(key) -> "i"
                    key.startsWith("LIMIT_LEVEL_") || key.startsWith("LIMIT_RETRY_COUNT_") || key.startsWith("USAGE_") -> "i"
                    key.startsWith("LIMIT_UNTIL_") || key.startsWith("LIMIT_LAST_OPEN_DAY_") || key.startsWith("LIMIT_LAST_OPEN_") -> "l"
                    packageName.matches(key) -> "s" // Historical package-keyed app renames.
                    else -> "s" // Legacy shortcut renames use arbitrary shortcut IDs.
                }
                require(type == expected) { "Wrong type for $key" }
                val raw = entry.get("v")
                val value: Any = when (type) {
                    "b" -> { require(raw is Boolean); raw }
                    "s" -> { require(raw is String); raw }
                    "i" -> {
                        require(raw is Int || raw is Long)
                        val number = (raw as Number).toLong()
                        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
                        number.toInt()
                    }
                    "l" -> { require(raw is Int || raw is Long); (raw as Number).toLong() }
                    "f" -> {
                        require(raw is Number)
                        raw.toFloat().also { require(it.isFinite()) }
                    }
                    "set" -> {
                        require(raw is JSONArray)
                        (0 until raw.length()).mapTo(mutableSetOf()) { index ->
                            raw.get(index).also { require(it is String) } as String
                        }
                    }
                    else -> error("Unknown type")
                }
                when {
                    key == "TEXT_SIZE_SCALE" -> require(value as Float in 0.5f..1.5f)
                    key == "OPACITY_HOME" || key == "OPACITY_DRAWER" -> require(value as Float in 0f..1f)
                    key == "HOME_APPS_NUM" -> require(value as Int in 0..8)
                    key == "HOME_SHORTCUT_ICONS_NUM" -> require(value as Int in 0..Constants.SHORTCUT_COUNT)
                    iconIndex.matches(key) -> require(value as Int in Constants.SHORTCUT_ICONS.indices)
                    key == "SEARCH_MODE" -> require(value as Int in 0..2)
                    key == "GRID_COLUMNS" -> require(value as Int in HomeGrid.MIN_COLUMNS..HomeGrid.MAX_COLUMNS)
                    key == "GRID_ROWS" -> require(value as Int in HomeGrid.MIN_ROWS..HomeGrid.MAX_ROWS)
                    key == "ICON_SHAPE" -> require(value as Int in 0..5)
                    key == "ICON_SIZE_SCALE" || key == "GRID_ICON_SCALE" -> require(value as Float in 0.2f..3f)
                    key.startsWith("LIMIT_") || key.startsWith("USAGE_") -> require((value as Number).toLong() >= 0)
                }
                if (key == "CUSTOM_FONT_BASE64") font = value as String
                else values[key] = value
            }
            return PreferenceBackup(values, font)
        }
    }
}

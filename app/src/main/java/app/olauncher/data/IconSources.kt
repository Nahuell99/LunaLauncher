package app.olauncher.data

/** Values stored in [Prefs.iconSource]. */
object IconSources {
    const val SYSTEM = "system"
    const val THEMED = "themed"
    const val LAWNICONS = "lawnicons"
    const val PACK_PREFIX = "pack:"

    fun pack(packageName: String) = PACK_PREFIX + packageName
    fun packPackage(source: String): String? =
        if (source.startsWith(PACK_PREFIX)) source.removePrefix(PACK_PREFIX).takeIf { it.isNotBlank() } else null
}

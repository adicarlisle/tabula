package app.tabula.core

enum class Accent(val label: String, val argb: Long?) {
    SIGNAL_RED("Signal red", 0xFFFF4A3D),
    AMBER("Amber", 0xFFFFB020),
    MOSS("Moss", 0xFF5BD27A),
    SKY("Sky", 0xFF4DA8FF),
    VIOLET("Violet", 0xFFA88BFF),
    /** Monochrome: the accent takes the text colour. */
    NONE("No colour", null),
}

enum class Theme { BLACK, GRAPHITE, PAPER }

enum class WordFont { MONO, GROTESK, SERIF }

enum class WordSize(val sp: Int) { SMALL(26), MEDIUM(34), LARGE(42) }

enum class Corners(val searchBarRadiusDp: Int) { SQUARE(4), SOFT(14), ROUND(28) }

/** The look the user picks in Settings. */
data class LauncherStyle(
    val accent: Accent,
    val theme: Theme,
    val font: WordFont,
    val size: WordSize,
    val corners: Corners,
) {
    fun encode(): Map<String, String> = mapOf(
        "accent" to accent.name, "theme" to theme.name, "font" to font.name,
        "size" to size.name, "corners" to corners.name,
    )

    companion object {
        val DEFAULT = LauncherStyle(Accent.SIGNAL_RED, Theme.BLACK, WordFont.MONO, WordSize.MEDIUM, Corners.ROUND)

        /** Unknown or damaged values fall back to the default for that one setting. */
        fun decode(saved: Map<String, String?>): LauncherStyle = LauncherStyle(
            accent = pick(saved["accent"], DEFAULT.accent),
            theme = pick(saved["theme"], DEFAULT.theme),
            font = pick(saved["font"], DEFAULT.font),
            size = pick(saved["size"], DEFAULT.size),
            corners = pick(saved["corners"], DEFAULT.corners),
        )

        private inline fun <reified E : Enum<E>> pick(value: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: fallback
    }
}

/** Colours as 0xAARRGGBB. */
data class Palette(val background: Long, val text: Long, val muted: Long, val faint: Long, val accent: Long) {
    companion object {
        fun of(style: LauncherStyle): Palette {
            val base = when (style.theme) {
                Theme.BLACK -> Palette(0xFF000000, 0xFFF2F2F2, 0xFF9A9A9A, 0xFF5A5A5A, 0)
                Theme.GRAPHITE -> Palette(0xFF1B1B1D, 0xFFEDEDED, 0xFFA0A0A6, 0xFF626268, 0)
                Theme.PAPER -> Palette(0xFFF2EFE8, 0xFF141414, 0xFF5E5B55, 0xFF9C988F, 0)
            }
            return base.copy(accent = style.accent.argb ?: base.text)
        }
    }
}

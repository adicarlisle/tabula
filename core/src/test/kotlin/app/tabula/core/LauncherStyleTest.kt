package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Examples: the look the user picks in Settings, how it's saved, and what it turns into on screen. */
class LauncherStyleTest {
    @Test
    fun `defaults match the current look`() {
        assertEquals(
            LauncherStyle(Accent.SIGNAL_RED, Theme.BLACK, WordFont.MONO, WordSize.MEDIUM, Corners.ROUND),
            LauncherStyle.DEFAULT,
        )
    }

    @Test
    fun `saving then loading gives the same style back`() {
        val style = LauncherStyle(Accent.MOSS, Theme.PAPER, WordFont.SERIF, WordSize.LARGE, Corners.SQUARE)
        assertEquals(style, LauncherStyle.decode(style.encode()))
    }

    @Test
    fun `damaged or unknown saved values fall back one setting at a time`() {
        val saved = mapOf("accent" to "SKY", "theme" to "NEON", "font" to "", "size" to "small")
        assertEquals(
            LauncherStyle.DEFAULT.copy(accent = Accent.SKY, size = WordSize.SMALL),
            LauncherStyle.decode(saved),
        )
        assertEquals(LauncherStyle.DEFAULT, LauncherStyle.decode(emptyMap()))
    }

    @Test
    fun `each theme has its own colours`() {
        assertEquals(0xFF000000, Palette.of(LauncherStyle.DEFAULT).background)
        assertEquals(0xFFF2F2F2, Palette.of(LauncherStyle.DEFAULT).text)
        val paper = Palette.of(LauncherStyle.DEFAULT.copy(theme = Theme.PAPER))
        assertEquals(0xFFF2EFE8, paper.background)
        assertEquals(0xFF141414, paper.text)
    }

    @Test
    fun `no colour accent follows the text colour so it always shows`() {
        val none = LauncherStyle.DEFAULT.copy(accent = Accent.NONE)
        assertEquals(0xFFF2F2F2, Palette.of(none).accent)
        assertEquals(0xFF141414, Palette.of(none.copy(theme = Theme.PAPER)).accent)
        assertEquals(0xFFFF4A3D, Palette.of(LauncherStyle.DEFAULT).accent)
    }

    @Test
    fun `sizes and corners`() {
        assertEquals(listOf(26, 34, 42), WordSize.entries.map { it.sp })
        assertEquals(listOf(4, 14, 28), Corners.entries.map { it.searchBarRadiusDp })
    }
}

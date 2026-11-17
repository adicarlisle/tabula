package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Examples: typing filters installed apps instantly, best match first. */
class AppSearchTest {
    private val apps = listOf("Maps", "Messages", "Camera", "Google Maps Go", "Calendar", "Home Assistant", "Smash")
        .map { AppEntry(label = it, id = it.lowercase()) }

    private fun labels(q: String) = AppSearch.search(apps, q).map { it.label }

    @Test
    fun `empty query lists everything A to Z`() =
        assertEquals(listOf("Calendar", "Camera", "Google Maps Go", "Home Assistant", "Maps", "Messages", "Smash"), labels(""))

    @Test
    fun `name starting with the query beats a word starting with it, which beats anywhere`() =
        assertEquals(listOf("Maps", "Google Maps Go", "Smash"), labels("ma"))

    @Test
    fun `case and surrounding spaces do not matter`() =
        assertEquals(listOf("Calendar", "Camera"), labels("  CA "))

    @Test
    fun `initials find multi-word apps`() =
        assertEquals(listOf("Home Assistant"), labels("ha"))

    @Test
    fun `no match is an empty list`() = assertEquals(emptyList<String>(), labels("zzz"))
}

package io.github.towkirahmedrony.salah.data.mosque

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Geoapify style URL handed to MapLibre.
 *
 * MapLibre is a renderer with no map data of its own, so a missing key must produce no URL at all
 * rather than a request that can only fail. The theme decides which of the two documented styles is
 * requested.
 */
class MosqueMapStyleTest {

    @Test
    fun `a blank key has no map style`() {
        assertNull(MosqueMapStyle.styleUrl("", darkTheme = false))
        assertNull(MosqueMapStyle.styleUrl("   ", darkTheme = true))
    }

    @Test
    fun `the light and dark themes request their corresponding Geoapify styles`() {
        val light = MosqueMapStyle.styleUrl("key", darkTheme = false)!!
        val dark = MosqueMapStyle.styleUrl("key", darkTheme = true)!!

        assertTrue(light.contains("/${MosqueMapStyle.LIGHT_STYLE}/style.json"))
        assertTrue(dark.contains("/${MosqueMapStyle.DARK_STYLE}/style.json"))
        assertFalse(light.contains(MosqueMapStyle.DARK_STYLE))
        assertFalse(dark.contains(MosqueMapStyle.LIGHT_STYLE))
    }

    @Test
    fun `the key is encoded as one query component`() {
        val url = MosqueMapStyle.styleUrl("a b&c", darkTheme = false)!!

        assertTrue(url.startsWith(MosqueMapStyle.BASE_URL))
        assertTrue(url.endsWith("?apiKey=a+b%26c"))
    }

    @Test
    fun `a key with surrounding whitespace is trimmed before use`() {
        val trimmed = MosqueMapStyle.styleUrl("  key  ", darkTheme = false)
        val plain = MosqueMapStyle.styleUrl("key", darkTheme = false)

        assertEquals(plain, trimmed)
    }
}

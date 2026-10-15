package io.github.towkirahmedrony.salah.core.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version comparison contract: equality, and only equality.
 *
 * These tests exist mostly to pin down what the comparison deliberately does *not* do. Salah's shipped
 * version identifies a release of an external dataset (`BGD-COD-AB v03 (2023-05-21)`), not a number, so
 * inventing an ordering over such strings would be guessing — and an ordering that guessed permissively
 * would let an older publication replace newer data. Equality cannot fail in that direction.
 */
class DatasetVersionTest {

    @Test
    fun `the factory trims, so equality and isSameAs always agree`() {
        val trimmed = DatasetVersion.of("v03")
        val padded = DatasetVersion.of("  v03\t")

        assertEquals(trimmed, padded)
        assertTrue(trimmed.isSameAs(padded))
        assertEquals("v03", trimmed.value)
    }

    @Test
    fun `versions differing only by surrounding whitespace are the same dataset`() {
        // A version read from a text file can carry a trailing tab; that is the same version.
        assertTrue(DatasetVersion.of("v03").isSameAs(DatasetVersion("v03 ")))
    }

    @Test
    fun `case is significant`() {
        // A publisher may treat v03 and V03 as different releases, and folding case would silently
        // equate two different publications.
        assertFalse(DatasetVersion.of("v03").isSameAs(DatasetVersion.of("V03")))
    }

    @Test
    fun `no normalisation of numbers is inferred`() {
        // Nothing here decides that v3 and v03 are the same release, or that 2023-5-21 is 2023-05-21.
        assertFalse(DatasetVersion.of("v03").isSameAs(DatasetVersion.of("v3")))
        assertFalse(DatasetVersion.of("2023-05-21").isSameAs(DatasetVersion.of("2023-5-21")))
    }

    @Test
    fun `a longer version is a different version`() {
        assertFalse(DatasetVersion.of("v03").isSameAs(DatasetVersion.of("v03.1")))
        assertNotEquals(DatasetVersion.of("v03"), DatasetVersion.of("v03.1"))
    }

    @Test
    fun `a blank version is reported as blank`() {
        assertTrue(DatasetVersion.of("").isBlank)
        assertTrue(DatasetVersion.of("   ").isBlank)
        assertFalse(DatasetVersion.of("v03").isBlank)
    }

    @Test
    fun `the shipped version format round-trips unchanged`() {
        val shipped = "BGD-COD-AB v03 (2023-05-21)"

        val version = DatasetVersion.of(shipped)

        assertEquals(shipped, version.value)
        assertEquals(shipped, version.toString())
        assertTrue(version.isSameAs(DatasetVersion.of(shipped)))
    }
}

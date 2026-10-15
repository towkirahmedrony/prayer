package io.github.towkirahmedrony.salah.core.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload integrity value, locked to a known answer.
 *
 * The published manifest's `checksum` is produced by the offline tool
 * `tools/publish_bd_location_dataset.py`, computed over the payload's logical parts with an algorithm
 * transcribed from [DatasetChecksum]. If the two ever diverged, the app would reject every legitimate
 * publication with a checksum mismatch — a failure that is invisible until the first real publication
 * and expensive to diagnose. The golden vectors below are the values that tool produced for two fixed
 * inputs, so a change to either implementation fails here instead of in the field.
 *
 * The properties alongside them cover what a checksum must be for the design to hold: deterministic,
 * sensitive to every part, and sensitive to the *split* of content between parts (which is why the part
 * name is length-prefixed into the digest rather than simply concatenated).
 */
class DatasetChecksumTest {

    @Test
    fun `the algorithm matches the offline publisher for a representative payload`() {
        val parts = listOf(
            DatasetPart("dataset.version", "v1"),
            DatasetPart("bd_areas.tsv", "DIVISION\tBD10\tBD\tDhaka\tঢাকা\t23.7\t90.4\n"),
            DatasetPart("bd_boundary.tsv", "0\t23.7\t90.4\n"),
            DatasetPart("bd_localities.tsv", ""),
        )

        assertEquals(
            "52519ac477174492e8f21f4aad44e56e4e9f9d4f83e188ff42abbb3bdf46fb5f",
            DatasetChecksum.of(parts),
        )
    }

    @Test
    fun `the algorithm matches the offline publisher for a minimal payload`() {
        assertEquals(
            "9bc3b7cd31fb5cc1e7e29139329cfe15c84542584292b906e82d43ee974ac858",
            DatasetChecksum.of(listOf(DatasetPart("a", ""))),
        )
    }

    @Test
    fun `the digest is lower-case hex of the expected length`() {
        val checksum = DatasetChecksum.of(listOf(DatasetPart("a", "b")))

        assertEquals(64, checksum.length)
        assertTrue(checksum.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `the digest is deterministic`() {
        val parts = listOf(DatasetPart("name", "content"))

        assertEquals(DatasetChecksum.of(parts), DatasetChecksum.of(parts))
    }

    @Test
    fun `changing a part's content changes the digest`() {
        val base = listOf(DatasetPart("a", "one"), DatasetPart("b", "two"))

        assertNotEquals(DatasetChecksum.of(base), DatasetChecksum.of(listOf(DatasetPart("a", "ONE"), DatasetPart("b", "two"))))
    }

    @Test
    fun `changing a part's name changes the digest`() {
        val base = listOf(DatasetPart("a", "x"))

        assertNotEquals(DatasetChecksum.of(base), DatasetChecksum.of(listOf(DatasetPart("b", "x"))))
    }

    @Test
    fun `reordering parts changes the digest`() {
        val first = listOf(DatasetPart("a", "1"), DatasetPart("b", "2"))

        assertNotEquals(DatasetChecksum.of(first), DatasetChecksum.of(first.reversed()))
    }

    @Test
    fun `moving content between parts changes the digest`() {
        // The encoding is length-prefixed and part-named, so the same bytes split differently cannot
        // collide — which is what stops a publisher from moving a row between tables undetected.
        val together = DatasetChecksum.of(listOf(DatasetPart("a", "xy"), DatasetPart("b", "")))
        val split = DatasetChecksum.of(listOf(DatasetPart("a", "x"), DatasetPart("b", "y")))

        assertNotEquals(together, split)
    }
}

package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.testing.areaOverride
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical writer, which is what makes a stored payload self-describing.
 *
 * The one property that matters is round-trip: the same parser a download uses must read back exactly the
 * snapshot that was written, so there is one format and one parser rather than a second on-disk encoding.
 */
class PrayerConfigurationWriterTest {

    @Test
    fun `a snapshot round-trips through the writer and the parser`() {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.EGYPTIAN,
            asrMethod = AsrCalculationMethod.STANDARD,
            adjustments = mapOf(PrayerName.FAJR to 3, PrayerName.ISHA to -2),
            areaOverrides = listOf(
                areaOverride("BD10", PrayerConfigurationScope.DIVISION),
                areaOverride("BD1001", PrayerConfigurationScope.DISTRICT, method = PrayerCalculationMethod.KUWAIT),
            ),
        )

        val text = PrayerConfigurationWriter.toJson(snapshot)
        val parsed = PrayerConfigurationParser.parseSnapshot(text)

        assertTrue(parsed.toString(), parsed is SnapshotParseResult.Parsed)
        assertEquals(snapshot, (parsed as SnapshotParseResult.Parsed).snapshot)
    }

    @Test
    fun `the writer is deterministic and emits numeric adjustments`() {
        val snapshot = sampleSnapshot(adjustments = mapOf(PrayerName.ASR to 5))

        val first = PrayerConfigurationWriter.toJson(snapshot)
        val second = PrayerConfigurationWriter.toJson(snapshot)

        assertEquals("the same snapshot must produce identical bytes", first, second)
        // Adjustments are the engine's input, so they must be numbers, not strings.
        assertTrue("expected a numeric adjustment in $first", first.contains("\"ASR\":5"))
    }
}

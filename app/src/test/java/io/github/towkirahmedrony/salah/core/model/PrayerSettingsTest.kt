package io.github.towkirahmedrony.salah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerSettingsTest {

    @Test
    fun `starts from the documented Bangladesh-oriented default`() {
        val settings = PrayerSettings.DEFAULT

        assertEquals(PrayerCalculationMethod.KARACHI, settings.method)
        assertEquals(AsrCalculationMethod.HANAFI, settings.asrMethod)
        assertEquals(HighLatitudeRule.AUTOMATIC, settings.highLatitudeRule)
        assertEquals(TimeFormatPreference.SYSTEM, settings.timeFormat)
        assertTrue(settings.adjustmentsMinutes.isEmpty())
        assertFalse(settings.hasAdjustments)
    }

    @Test
    fun `reports a zero adjustment for a prayer that has none`() {
        assertEquals(0, PrayerSettings.DEFAULT.adjustmentFor(PrayerName.ASR))
    }

    @Test
    fun `stores an adjustment and reports it`() {
        val settings = PrayerSettings.DEFAULT.withAdjustment(PrayerName.FAJR, 3)

        assertEquals(3, settings.adjustmentFor(PrayerName.FAJR))
        assertTrue(settings.hasAdjustments)
    }

    @Test
    fun `clamps an adjustment to the supported range`() {
        val tooLate = PrayerSettings.DEFAULT.withAdjustment(PrayerName.DHUHR, 500)
        val tooEarly = PrayerSettings.DEFAULT.withAdjustment(PrayerName.DHUHR, -500)

        assertEquals(PrayerSettings.MAX_ADJUSTMENT_MINUTES, tooLate.adjustmentFor(PrayerName.DHUHR))
        assertEquals(-PrayerSettings.MAX_ADJUSTMENT_MINUTES, tooEarly.adjustmentFor(PrayerName.DHUHR))
    }

    @Test
    fun `setting an adjustment back to zero removes it entirely`() {
        val settings = PrayerSettings.DEFAULT
            .withAdjustment(PrayerName.ISHA, 4)
            .withAdjustment(PrayerName.ISHA, 0)

        assertFalse(settings.adjustmentsMinutes.containsKey(PrayerName.ISHA))
        assertFalse(settings.hasAdjustments)
    }

    @Test
    fun `replaces one prayer's adjustment without touching the others`() {
        val settings = PrayerSettings.DEFAULT
            .withAdjustment(PrayerName.FAJR, 1)
            .withAdjustment(PrayerName.ASR, -2)
            .withAdjustment(PrayerName.FAJR, 5)

        assertEquals(5, settings.adjustmentFor(PrayerName.FAJR))
        assertEquals(-2, settings.adjustmentFor(PrayerName.ASR))
        assertEquals(0, settings.adjustmentFor(PrayerName.MAGHRIB))
    }

    @Test
    fun `clears every adjustment`() {
        val settings = PrayerSettings.DEFAULT
            .withAdjustment(PrayerName.FAJR, 1)
            .withAdjustment(PrayerName.ISHA, 2)
            .withoutAdjustments()

        assertTrue(settings.adjustmentsMinutes.isEmpty())
    }

    @Test
    fun `maps onto the calculation profile the engine consumes`() {
        val settings = PrayerSettings(
            method = PrayerCalculationMethod.EGYPTIAN,
            asrMethod = AsrCalculationMethod.STANDARD,
            highLatitudeRule = HighLatitudeRule.SEVENTH_OF_THE_NIGHT,
            adjustmentsMinutes = mapOf(PrayerName.FAJR to 2),
            timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR,
        )

        val profile = settings.toProfile()

        assertEquals(PrayerCalculationMethod.EGYPTIAN, profile.method)
        assertEquals(AsrCalculationMethod.STANDARD, profile.asrMethod)
        assertEquals(HighLatitudeRule.SEVENTH_OF_THE_NIGHT, profile.highLatitudeRule)
        assertEquals(2, profile.adjustmentFor(PrayerName.FAJR))
        // The display preference is not a calculation input, so it must not reach the engine.
        assertEquals(0, profile.adjustmentFor(PrayerName.ISHA))
    }

    @Test
    fun `the profile default and the settings default agree`() {
        assertEquals(PrayerSettings.DEFAULT.toProfile(), PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI)
    }

    @Test
    fun `every calculation method declares a usable configuration`() {
        PrayerCalculationMethod.entries.forEach { method ->
            assertTrue(
                "${method.name} must define either a twilight angle or a fixed Isha interval",
                method.ishaIntervalMinutes > 0 || method.ishaAngleDegrees > 0.0,
            )
            assertTrue("${method.name} must define a Fajr angle", method.fajrAngleDegrees > 0.0)
        }
    }
}

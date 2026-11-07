package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPrayerDefaultsTest {

    private val resolver = PrayerConfigResolver()

    @Test
    fun `the bundled profile is the default method with only Dhuhr and Maghrib offsets`() {
        val base = PrayerSettings.DEFAULT.toProfile()
        val bundled = BundledPrayerDefaults.PROFILE

        assertEquals(base.method, bundled.method)
        assertEquals(base.asrMethod, bundled.asrMethod)
        assertEquals(base.highLatitudeRule, bundled.highLatitudeRule)
        assertEquals(
            mapOf(PrayerName.DHUHR to 3, PrayerName.MAGHRIB to 4),
            bundled.adjustmentsMinutes,
        )
    }

    @Test
    fun `Fajr, Asr and Isha carry no bundled offset`() {
        listOf(PrayerName.FAJR, PrayerName.ASR, PrayerName.ISHA).forEach { prayer ->
            assertEquals(0, BundledPrayerDefaults.PROFILE.adjustmentFor(prayer))
        }
    }

    @Test
    fun `the offsets stay inside the supported adjustment range`() {
        assertTrue(BundledPrayerDefaults.PROFILE.adjustmentsMinutes.values.all {
            it in -PrayerSettings.MAX_ADJUSTMENT_MINUTES..PrayerSettings.MAX_ADJUSTMENT_MINUTES
        })
    }

    @Test
    fun `with nothing remote and nothing owned the bundled offsets apply`() {
        val effective = resolver.resolve(null, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, null)

        assertEquals(BundledPrayerDefaults.PROFILE, effective.profile)
    }

    @Test
    fun `owning another field does not remove the bundled offsets`() {
        val effective = resolver.resolve(
            null,
            PrayerSettings.DEFAULT,
            PrayerConfigOverrides(setOf(PrayerConfigOverrides.Field.METHOD)),
            null,
        )

        assertEquals(BundledPrayerDefaults.PROFILE.adjustmentsMinutes, effective.profile.adjustmentsMinutes)
    }

    @Test
    fun `a user who owns the adjustments keeps their own and not the bundled ones`() {
        val effective = resolver.resolve(
            null,
            PrayerSettings(adjustmentsMinutes = mapOf(PrayerName.FAJR to 2)),
            PrayerConfigOverrides(setOf(PrayerConfigOverrides.Field.ADJUSTMENTS)),
            null,
        )

        assertEquals(mapOf(PrayerName.FAJR to 2), effective.profile.adjustmentsMinutes)
    }
}

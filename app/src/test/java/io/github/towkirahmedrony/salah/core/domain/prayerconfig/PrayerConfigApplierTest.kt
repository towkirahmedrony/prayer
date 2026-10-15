package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.testing.FakeActivePrayerConfigStore
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.checksumOf
import io.github.towkirahmedrony.salah.testing.payloadOf
import io.github.towkirahmedrony.salah.testing.sampleAreaRef
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the active remote configuration reaches the one settings store.
 *
 * The two properties that matter most: a remote update supplies the unowned fields and *only* those, and an
 * explicit user choice is never overwritten. The origin the screen displays is asserted here too, because it
 * is the user-visible expression of the same rule.
 */
class PrayerConfigApplierTest {

    private val logger = RecordingLogger()
    private val resolver = PrayerConfigResolver()

    private val area = sampleAreaRef()
    private val areaLocation = checkNotNull(
        LocationInfo.create(
            label = "Kumarkhali, Kushtia",
            latitude = 23.9,
            longitude = 89.2,
            timeZoneId = "Asia/Dhaka",
            origin = LocationOrigin.AREA_SELECTION,
            area = area,
        ),
    )

    private fun applier(
        preferences: FakeUserPreferencesRepository,
        store: FakeActivePrayerConfigStore,
    ): PrayerConfigApplier = PrayerConfigApplier(
        resolver = resolver,
        userPreferences = preferences,
        store = store,
        locations = FakeLocationRepository(areaLocation),
        logger = logger,
    )

    @Test
    fun `a remote revision supplies the defaults when the user owns nothing`() = runTest {
        val preferences = FakeUserPreferencesRepository()
        val store = FakeActivePrayerConfigStore(
            ActivePrayerConfig(
                sampleSnapshot(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, asrMethod = AsrCalculationMethod.STANDARD),
                checksumOf("active"),
            ),
        )

        applier(preferences, store).apply()

        val stored = preferences.prayerSettings.first()
        assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, stored.method)
        assertEquals(AsrCalculationMethod.STANDARD, stored.asrMethod)
    }

    @Test
    fun `an explicit user override survives a remote update`() = runTest {
        val preferences = FakeUserPreferencesRepository(
            initialSettings = PrayerSettings(method = PrayerCalculationMethod.TURKEY),
            initialOverrides = PrayerConfigOverrides(setOf(PrayerConfigOverrides.Field.METHOD)),
        )
        val store = FakeActivePrayerConfigStore(
            ActivePrayerConfig(
                sampleSnapshot(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, asrMethod = AsrCalculationMethod.STANDARD),
                checksumOf("active"),
            ),
        )

        applier(preferences, store).apply()

        val stored = preferences.prayerSettings.first()
        assertEquals("the user's method must survive", PrayerCalculationMethod.TURKEY, stored.method)
        assertEquals("the remote Asr still applies", AsrCalculationMethod.STANDARD, stored.asrMethod)
        assertEquals(
            "the override is not forgotten by applying",
            setOf(PrayerConfigOverrides.Field.METHOD),
            preferences.prayerConfigOverrides.first().fields,
        )
    }

    @Test
    fun `resetting an override restores the applicable default`() = runTest {
        val preferences = FakeUserPreferencesRepository(
            initialSettings = PrayerSettings(method = PrayerCalculationMethod.TURKEY),
            initialOverrides = PrayerConfigOverrides(setOf(PrayerConfigOverrides.Field.METHOD)),
        )
        val store = FakeActivePrayerConfigStore(
            ActivePrayerConfig(
                sampleSnapshot(method = PrayerCalculationMethod.EGYPTIAN),
                checksumOf("active"),
            ),
        )
        val applier = applier(preferences, store)

        applier.resetField(PrayerConfigOverrides.Field.METHOD)

        assertEquals(PrayerCalculationMethod.EGYPTIAN, preferences.prayerSettings.first().method)
        assertFalse(preferences.prayerConfigOverrides.first().contains(PrayerConfigOverrides.Field.METHOD))
    }

    @Test
    fun `resetting everything returns to the applicable defaults`() = runTest {
        val preferences = FakeUserPreferencesRepository(
            initialSettings = PrayerSettings(
                method = PrayerCalculationMethod.TURKEY,
                adjustmentsMinutes = mapOf(PrayerName.FAJR to 4),
            ),
            initialOverrides = PrayerConfigOverrides(PrayerConfigOverrides.Field.entries.toSet()),
        )
        val store = FakeActivePrayerConfigStore(
            ActivePrayerConfig(sampleSnapshot(method = PrayerCalculationMethod.EGYPTIAN), checksumOf("active")),
        )
        val applier = applier(preferences, store)

        applier.resetAll()

        val stored = preferences.prayerSettings.first()
        assertEquals(PrayerCalculationMethod.EGYPTIAN, stored.method)
        assertTrue(stored.adjustmentsMinutes.isEmpty())
        assertFalse(preferences.prayerConfigOverrides.first().hasAny)
    }

    @Test
    fun `the time format is never touched by a remote update`() = runTest {
        val preferences = FakeUserPreferencesRepository(
            initialSettings = PrayerSettings(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
        )
        val store = FakeActivePrayerConfigStore(
            ActivePrayerConfig(sampleSnapshot(method = PrayerCalculationMethod.KUWAIT), checksumOf("active")),
        )

        applier(preferences, store).apply()

        assertEquals(TimeFormatPreference.TWENTY_FOUR_HOUR, preferences.prayerSettings.first().timeFormat)
    }

    @Test
    fun `the origin reflects the active remote configuration`() = runTest {
        val preferences = FakeUserPreferencesRepository()
        val store = FakeActivePrayerConfigStore(
            ActivePrayerConfig(sampleSnapshot(method = PrayerCalculationMethod.KUWAIT), checksumOf("active")),
        )
        val applier = applier(preferences, store)

        applier.apply()

        assertEquals(PrayerConfigOriginKind.PUBLISHED_GLOBAL, applier.origin.first().kind)
    }

    @Test
    fun `with no remote configuration the bundled default applies`() = runTest {
        val preferences = FakeUserPreferencesRepository()
        val store = FakeActivePrayerConfigStore()

        val applier = applier(preferences, store)
        applier.apply()

        assertEquals(PrayerSettings.DEFAULT.toProfile(), preferences.prayerSettings.first().toProfile())
        assertEquals(PrayerConfigOriginKind.BUNDLED_DEFAULT, applier.origin.first().kind)
    }
}

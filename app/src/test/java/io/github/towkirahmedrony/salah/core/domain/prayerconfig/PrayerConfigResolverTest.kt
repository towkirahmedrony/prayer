package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.testing.areaOverride
import io.github.towkirahmedrony.salah.testing.sampleAreaRef
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The precedence rule, deepest-first, and the field-level user override on top of it.
 *
 * Each test names the one layer it is pinning, so a change that quietly reorders the rule fails here
 * rather than in a user's timetable.
 */
class PrayerConfigResolverTest {

    private val resolver = PrayerConfigResolver()
    private val area = sampleAreaRef()

    @Test
    fun `with nothing remote the bundled default applies`() {
        val effective = resolver.resolve(null, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, area)

        assertEquals(PrayerSettings.DEFAULT.toProfile(), effective.profile)
        assertEquals(PrayerConfigOriginKind.BUNDLED_DEFAULT, effective.origin.kind)
    }

    @Test
    fun `without a saved area the global default applies`() {
        val snapshot = sampleSnapshot(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE)

        val effective = resolver.resolve(snapshot, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, null)

        assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, effective.profile.method)
        assertEquals(PrayerConfigOriginKind.PUBLISHED_GLOBAL, effective.origin.kind)
    }

    @Test
    fun `an upazila configuration wins over district, division and global`() {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.KARACHI,
            areaOverrides = listOf(
                areaOverride("BD1001001", PrayerConfigurationScope.UPAZILA, method = PrayerCalculationMethod.EGYPTIAN),
                areaOverride("BD1001", PrayerConfigurationScope.DISTRICT, method = PrayerCalculationMethod.KUWAIT),
                areaOverride("BD10", PrayerConfigurationScope.DIVISION, method = PrayerCalculationMethod.DUBAI),
            ),
        )

        val effective = resolver.resolve(snapshot, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, area)

        assertEquals(PrayerCalculationMethod.EGYPTIAN, effective.profile.method)
        assertEquals(PrayerConfigOriginKind.PUBLISHED_AREA, effective.origin.kind)
        assertEquals("BD1001001", effective.origin.areaId)
    }

    @Test
    fun `a district configuration wins over division and global`() {
        val snapshot = sampleSnapshot(
            areaOverrides = listOf(
                areaOverride("BD1001", PrayerConfigurationScope.DISTRICT, method = PrayerCalculationMethod.KUWAIT),
                areaOverride("BD10", PrayerConfigurationScope.DIVISION, method = PrayerCalculationMethod.DUBAI),
            ),
        )

        val effective = resolver.resolve(snapshot, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, area)

        assertEquals(PrayerCalculationMethod.KUWAIT, effective.profile.method)
        assertEquals("BD1001", effective.origin.areaId)
    }

    @Test
    fun `a division configuration wins over the global default`() {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
            areaOverrides = listOf(
                areaOverride("BD10", PrayerConfigurationScope.DIVISION, method = PrayerCalculationMethod.DUBAI),
            ),
        )

        val effective = resolver.resolve(snapshot, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, area)

        assertEquals(PrayerCalculationMethod.DUBAI, effective.profile.method)
        assertEquals("BD10", effective.origin.areaId)
    }

    @Test
    fun `a missing area-specific configuration falls back to the global default`() {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
            areaOverrides = listOf(
                areaOverride("BD9999", PrayerConfigurationScope.UPAZILA, method = PrayerCalculationMethod.EGYPTIAN),
            ),
        )

        val effective = resolver.resolve(snapshot, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, area)

        assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, effective.profile.method)
        assertEquals(PrayerConfigOriginKind.PUBLISHED_GLOBAL, effective.origin.kind)
    }

    @Test
    fun `an explicit user override wins for the field it owns, and only that field`() {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
            asrMethod = AsrCalculationMethod.STANDARD,
        )
        val settings = PrayerSettings(method = PrayerCalculationMethod.KARACHI)
        val overrides = PrayerConfigOverrides(setOf(PrayerConfigOverrides.Field.METHOD))

        val effective = resolver.resolve(snapshot, settings, overrides, area)

        assertEquals("the user's method wins", PrayerCalculationMethod.KARACHI, effective.profile.method)
        assertEquals("the remote Asr still applies", AsrCalculationMethod.STANDARD, effective.profile.asrMethod)
        assertEquals(PrayerConfigOriginKind.USER_OVERRIDE, effective.origin.kind)
        assertEquals(PrayerConfigOriginKind.PUBLISHED_GLOBAL, effective.origin.baseKind)
    }

    @Test
    fun `releasing an override returns the field to the applicable default`() {
        val snapshot = sampleSnapshot(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE)

        val effective = resolver.resolve(snapshot, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, area)

        assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, effective.profile.method)
        assertEquals(PrayerConfigOriginKind.PUBLISHED_GLOBAL, effective.origin.kind)
    }
}

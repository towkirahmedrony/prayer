package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.data.prayer.AdhanPrayerTimesProvider
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The remote configuration, resolved and then handed to the real engine.
 *
 * This is the test that ties the new configuration system back to the *existing* behaviour it must not
 * change: the bundled default must reproduce the engine's current output exactly, a supported remote value
 * must move only the inputs it names, and an unsupported value must never become a profile at all. The
 * expected times are the same regression values the engine's own test pins.
 */
class PrayerConfigEngineMappingTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneId.of("UTC"))
    private val provider = AdhanPrayerTimesProvider(clock)
    private val resolver = PrayerConfigResolver()

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private suspend fun schedule(profile: PrayerCalculationProfile): PrayerSchedule {
        val result = provider.schedule(PrayerScheduleRequest(date, dhaka, profile))
        assertTrue("expected a schedule but was $result", result is PrayerTimesProviderResult.Success)
        return (result as PrayerTimesProviderResult.Success).schedule
    }

    private fun PrayerSchedule.at(prayer: PrayerName): String =
        timeOf(prayer)!!.toLocalTime().toString().substring(0, 5)

    @Test
    fun `the bundled default preserves the existing calculated times`() = runTest {
        val effective = resolver.resolve(null, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, null)

        assertEquals(PrayerSettings.DEFAULT.toProfile(), effective.profile)

        val schedule = schedule(effective.profile)
        assertEquals("04:39", schedule.at(PrayerName.FAJR))
        assertEquals("11:46", schedule.at(PrayerName.DHUHR))
        assertEquals("15:58", schedule.at(PrayerName.ASR))
        assertEquals("17:36", schedule.at(PrayerName.MAGHRIB))
        assertEquals("18:51", schedule.at(PrayerName.ISHA))
    }

    @Test
    fun `a published Asr convention changes only Asr`() = runTest {
        val bundled = schedule(
            resolver.resolve(null, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, null).profile,
        )
        val standard = schedule(
            resolver.resolve(
                sampleSnapshot(method = PrayerCalculationMethod.KARACHI, asrMethod = AsrCalculationMethod.STANDARD),
                PrayerSettings.DEFAULT,
                PrayerConfigOverrides.NONE,
                null,
            ).profile,
        )

        assertNotEquals(bundled.at(PrayerName.ASR), standard.at(PrayerName.ASR))
        PrayerName.entries.filter { it != PrayerName.ASR }.forEach { prayer ->
            assertEquals("the Asr convention must not move $prayer", bundled.at(prayer), standard.at(prayer))
        }
    }

    @Test
    fun `a published calculation method changes Fajr and Isha`() = runTest {
        val bundled = schedule(
            resolver.resolve(null, PrayerSettings.DEFAULT, PrayerConfigOverrides.NONE, null).profile,
        )
        val worldLeague = schedule(
            resolver.resolve(
                sampleSnapshot(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE),
                PrayerSettings.DEFAULT,
                PrayerConfigOverrides.NONE,
                null,
            ).profile,
        )

        assertNotEquals(bundled.at(PrayerName.FAJR), worldLeague.at(PrayerName.FAJR))
        assertNotEquals(bundled.at(PrayerName.ISHA), worldLeague.at(PrayerName.ISHA))
    }

    @Test
    fun `a user override reaches the engine for its field only`() = runTest {
        val effective = resolver.resolve(
            sampleSnapshot(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, asrMethod = AsrCalculationMethod.HANAFI),
            PrayerSettings(asrMethod = AsrCalculationMethod.STANDARD),
            PrayerConfigOverrides(setOf(PrayerConfigOverrides.Field.ASR_METHOD)),
            null,
        )

        assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, effective.profile.method)
        assertEquals(AsrCalculationMethod.STANDARD, effective.profile.asrMethod)
    }

    @Test
    fun `an unsupported remote value never becomes a profile`() {
        val raw = """{"configuration_id":"bd_prayer","revision_id":"r1","revision_number":1,""" +
            """"schema_version":1,"global":{"method":"MARS","asr_method":"HANAFI",""" +
            """"high_latitude_rule":"AUTOMATIC","adjustments":{}}}"""

        val result = PrayerConfigurationParser.parseSnapshot(raw)

        assertTrue("an unsupported value must be refused, not mapped", result is SnapshotParseResult.Invalid)
    }
}

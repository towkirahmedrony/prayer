package io.github.towkirahmedrony.salah.data.prayer

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Verifies the offline-first contract of the prayer schedule repository:
 * local reads never fail, failures become visible states, and a cached schedule is never
 * reused for a different day.
 */
class ProviderBackedPrayerScheduleRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), zone)
    private val date: LocalDate = LocalDate.of(2026, 10, 11)
    private val profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create(
            label = "Dhaka",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
        ),
    )

    private class ScriptedProvider(
        private val configured: ProviderAvailability = ProviderAvailability.CONFIGURED,
        private val handle: suspend (PrayerScheduleRequest) -> PrayerTimesProviderResult,
    ) : PrayerTimesProvider {
        var callCount: Int = 0
            private set

        override val id: String = "scripted"
        override val source: PrayerTimeSource = PrayerTimeSource.LOCAL_CALCULATION
        override val isAvailableOffline: Boolean = true

        override fun availability(): ProviderAvailability = configured

        override suspend fun schedule(request: PrayerScheduleRequest): PrayerTimesProviderResult {
            callCount += 1
            return handle(request)
        }
    }

    private fun scheduleFor(date: LocalDate): PrayerSchedule = PrayerSchedule(
        date = date,
        location = location,
        profile = profile,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, date.atTime(4, 55).atZone(zone)),
            PrayerTimeEntry(PrayerName.DHUHR, date.atTime(11, 52).atZone(zone)),
            PrayerTimeEntry(PrayerName.ASR, date.atTime(15, 18).atZone(zone)),
            PrayerTimeEntry(PrayerName.MAGHRIB, date.atTime(17, 36).atZone(zone)),
            PrayerTimeEntry(PrayerName.ISHA, date.atTime(18, 56).atZone(zone)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, date.atTime(6, 10).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, date.atTime(11, 54).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, date.atTime(17, 34).atZone(zone)),
        ),
        generatedAt = clock.instant(),
    )

    private fun repository(provider: PrayerTimesProvider): PrayerScheduleRepository =
        ProviderBackedPrayerScheduleRepository(
            provider = provider,
            clock = clock,
            profile = profile,
        )

    @Test
    fun `reports an explicit state when no location is configured`() = runTest {
        val state = repository(UnconfiguredLocalPrayerTimesProvider())
            .observeSchedule(null, date)
            .first()

        assertTrue(state is PrayerScheduleState.Unavailable)
        assertEquals(
            UnavailabilityReason.NO_LOCATION_CONFIGURED,
            (state as PrayerScheduleState.Unavailable).reason,
        )
        assertEquals(PrayerTimeSource.UNCONFIGURED, state.origin.source)
    }

    @Test
    fun `reports that the calculation engine is not configured`() = runTest {
        val state = repository(UnconfiguredLocalPrayerTimesProvider())
            .observeSchedule(location, date)
            .first()

        assertTrue(state is PrayerScheduleState.Unavailable)
        assertEquals(
            UnavailabilityReason.CALCULATION_NOT_CONFIGURED,
            (state as PrayerScheduleState.Unavailable).reason,
        )
    }

    @Test
    fun `serves a refreshed schedule from local state without calling the provider again`() =
        runTest {
            val provider = ScriptedProvider { request ->
                PrayerTimesProviderResult.Success(scheduleFor(request.date))
            }
            val repo = repository(provider)

            val refreshed = repo.refresh(location, date)
            assertTrue(refreshed is PrayerScheduleState.Available)

            val observed = repo.observeSchedule(location, date).first()
            assertTrue(observed is PrayerScheduleState.Available)
            assertEquals(date, (observed as PrayerScheduleState.Available).schedule.date)
            assertEquals(1, provider.callCount)
        }

    @Test
    fun `keeps a cached schedule from being reused for another date`() = runTest {
        val provider = ScriptedProvider { request ->
            PrayerTimesProviderResult.Success(scheduleFor(request.date))
        }
        val repo = repository(provider)
        repo.refresh(location, date)

        val otherDay = repo.observeSchedule(location, date.plusDays(1)).first()

        assertTrue(otherDay is PrayerScheduleState.Unavailable)
        assertEquals(
            UnavailabilityReason.UNKNOWN,
            (otherDay as PrayerScheduleState.Unavailable).reason,
        )
    }

    @Test
    fun `turns a provider exception into a visible failure instead of crashing`() = runTest {
        val provider = ScriptedProvider { throw IllegalStateException("engine exploded") }
        val repo = repository(provider)

        val refreshed = repo.refresh(location, date)
        assertTrue(refreshed is PrayerScheduleState.Failed)

        val observed = repo.observeSchedule(location, date).first()
        assertTrue(observed is PrayerScheduleState.Failed)
        assertTrue((observed as PrayerScheduleState.Failed).detail.contains("engine exploded"))
    }

    @Test
    fun `keeps a provider failure result visible`() = runTest {
        val provider = ScriptedProvider {
            PrayerTimesProviderResult.Failure("provider returned a malformed response")
        }
        val repo = repository(provider)

        val refreshed = repo.refresh(location, date)

        assertTrue(refreshed is PrayerScheduleState.Failed)
        assertEquals(
            "provider returned a malformed response",
            (refreshed as PrayerScheduleState.Failed).detail,
        )
    }

    @Test
    fun `reports unavailable when a provider has nothing for the request`() = runTest {
        val provider = ScriptedProvider { request ->
            PrayerTimesProviderResult.Unavailable(
                reason = UnavailabilityReason.NETWORK_UNAVAILABLE,
                detail = "offline",
            )
        }
        val repo = repository(provider)

        val refreshed = repo.refresh(location, date)

        assertTrue(refreshed is PrayerScheduleState.Unavailable)
        assertEquals(
            UnavailabilityReason.NETWORK_UNAVAILABLE,
            (refreshed as PrayerScheduleState.Unavailable).reason,
        )
    }
}

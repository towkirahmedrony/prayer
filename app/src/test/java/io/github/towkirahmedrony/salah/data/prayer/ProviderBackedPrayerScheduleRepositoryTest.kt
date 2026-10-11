package io.github.towkirahmedrony.salah.data.prayer

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
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
 * Verifies the offline-first contract of the prayer schedule repository: local reads never fail,
 * failures become visible states, and a cached schedule is only served for the exact request it
 * was calculated from — never for another day, city or calculation profile.
 */
class ProviderBackedPrayerScheduleRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), zone)
    private val date: LocalDate = LocalDate.of(2026, 10, 11)
    private val profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )
    private val otherLocation: LocationInfo = checkNotNull(
        LocationInfo.create("Chattogram", 22.3569, 91.7832, "Asia/Dhaka"),
    )

    private fun request(
        on: LocalDate = date,
        at: LocationInfo = location,
        withProfile: PrayerCalculationProfile = profile,
    ) = PrayerScheduleRequest(on, at, withProfile)

    /** A provider that answers from a scripted handler and counts how often it was called. */
    private class ScriptedProvider(
        private val configured: ProviderAvailability = ProviderAvailability.CONFIGURED,
        private val handle: (PrayerScheduleRequest) -> PrayerTimesProviderResult,
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

    private fun scheduleFor(request: PrayerScheduleRequest): PrayerSchedule = PrayerSchedule(
        date = request.date,
        location = request.location,
        profile = request.profile,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, request.date.atTime(4, 39).atZone(zone)),
            PrayerTimeEntry(PrayerName.DHUHR, request.date.atTime(11, 46).atZone(zone)),
            PrayerTimeEntry(PrayerName.ASR, request.date.atTime(15, 58).atZone(zone)),
            PrayerTimeEntry(PrayerName.MAGHRIB, request.date.atTime(17, 36).atZone(zone)),
            PrayerTimeEntry(PrayerName.ISHA, request.date.atTime(18, 51).atZone(zone)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, request.date.atTime(5, 54).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, request.date.atTime(11, 45).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, request.date.atTime(17, 36).atZone(zone)),
        ),
        generatedAt = clock.instant(),
    )

    private fun repository(provider: PrayerTimesProvider = ScriptedProvider { r ->
        PrayerTimesProviderResult.Success(scheduleFor(r))
    }) = ProviderBackedPrayerScheduleRepository(provider = provider, clock = clock)

    @Test
    fun `reports an explicit state when no location is configured`() = runTest {
        val state = repository().ensureSchedule(null).first()

        assertTrue(state is PrayerScheduleState.Unavailable)
        assertEquals(
            UnavailabilityReason.NO_LOCATION_CONFIGURED,
            (state as PrayerScheduleState.Unavailable).reason,
        )
    }

    @Test
    fun `reports that the engine is not configured instead of inventing times`() = runTest {
        val provider = ScriptedProvider(configured = ProviderAvailability.NOT_CONFIGURED) {
            PrayerTimesProviderResult.Unavailable(UnavailabilityReason.CALCULATION_NOT_CONFIGURED)
        }

        val state = repository(provider).ensureSchedule(request()).first()

        assertEquals(
            UnavailabilityReason.CALCULATION_NOT_CONFIGURED,
            (state as PrayerScheduleState.Unavailable).reason,
        )
        assertEquals(0, provider.callCount)
    }

    @Test
    fun `calculates once and then serves the cached schedule`() = runTest {
        val provider = ScriptedProvider { r -> PrayerTimesProviderResult.Success(scheduleFor(r)) }
        val repo = repository(provider)

        val first = repo.ensureSchedule(request()).first()
        val second = repo.ensureSchedule(request()).first()

        assertTrue(first is PrayerScheduleState.Available)
        assertTrue(second is PrayerScheduleState.Available)
        assertEquals(PrayerTimeSource.LOCAL_CALCULATION, (first as PrayerScheduleState.Available).origin.source)
        assertTrue(first.origin.isAvailableOffline)
        assertEquals(1, provider.callCount)
    }

    @Test
    fun `recalculates when the date changes`() = runTest {
        val provider = ScriptedProvider { r -> PrayerTimesProviderResult.Success(scheduleFor(r)) }
        val repo = repository(provider)

        repo.ensureSchedule(request()).first()
        val otherDay = repo.ensureSchedule(request(on = date.plusDays(1))).first()

        assertEquals(date.plusDays(1), (otherDay as PrayerScheduleState.Available).schedule.date)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `recalculates when the location changes`() = runTest {
        val provider = ScriptedProvider { r -> PrayerTimesProviderResult.Success(scheduleFor(r)) }
        val repo = repository(provider)

        repo.ensureSchedule(request()).first()
        val moved = repo.ensureSchedule(request(at = otherLocation)).first()

        assertEquals(otherLocation, (moved as PrayerScheduleState.Available).schedule.location)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `recalculates when the calculation profile changes`() = runTest {
        val provider = ScriptedProvider { r -> PrayerTimesProviderResult.Success(scheduleFor(r)) }
        val repo = repository(provider)

        repo.ensureSchedule(request()).first()
        val changed = request(
            withProfile = profile.copy(method = PrayerCalculationMethod.NORTH_AMERICA),
        )
        val recalculated = repo.ensureSchedule(changed).first()

        assertEquals(
            PrayerCalculationMethod.NORTH_AMERICA,
            (recalculated as PrayerScheduleState.Available).schedule.profile.method,
        )
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `recalculates when only the Asr method changes`() = runTest {
        val provider = ScriptedProvider { r -> PrayerTimesProviderResult.Success(scheduleFor(r)) }
        val repo = repository(provider)

        repo.ensureSchedule(request()).first()
        repo.ensureSchedule(
            request(withProfile = profile.copy(asrMethod = AsrCalculationMethod.STANDARD)),
        ).first()

        assertEquals(2, provider.callCount)
    }

    @Test
    fun `refresh bypasses the cache and replaces the entry`() = runTest {
        val provider = ScriptedProvider { r -> PrayerTimesProviderResult.Success(scheduleFor(r)) }
        val repo = repository(provider)

        repo.ensureSchedule(request()).first()
        repo.refresh(request())
        repo.ensureSchedule(request()).first()

        // One calculation for the first read, one for the explicit refresh, none for the read
        // that follows a successful refresh.
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `turns a provider exception into a visible failure instead of crashing`() = runTest {
        val provider = ScriptedProvider { throw IllegalStateException("engine exploded") }
        val repo = repository(provider)

        val state = repo.ensureSchedule(request()).first()

        assertTrue(state is PrayerScheduleState.Failed)
        assertTrue((state as PrayerScheduleState.Failed).detail.contains("engine exploded"))
    }

    @Test
    fun `keeps a provider failure result visible and never caches it as a schedule`() = runTest {
        var attempt = 0
        val provider = ScriptedProvider { r ->
            attempt += 1
            if (attempt == 1) {
                PrayerTimesProviderResult.Failure("provider returned a malformed response")
            } else {
                PrayerTimesProviderResult.Success(scheduleFor(r))
            }
        }
        val repo = repository(provider)

        val failed = repo.ensureSchedule(request()).first()
        assertEquals(
            "provider returned a malformed response",
            (failed as PrayerScheduleState.Failed).detail,
        )

        // A failure is not cached, so the next read genuinely retries and then succeeds.
        val recovered = repo.ensureSchedule(request()).first()
        assertTrue(recovered is PrayerScheduleState.Available)
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `reports unavailable when a provider has nothing for the request`() = runTest {
        val provider = ScriptedProvider {
            PrayerTimesProviderResult.Unavailable(
                reason = UnavailabilityReason.CALCULATION_NOT_POSSIBLE,
                detail = "no twilight",
            )
        }

        val state = repository(provider).ensureSchedule(request()).first()

        assertTrue(state is PrayerScheduleState.Unavailable)
        assertEquals(
            UnavailabilityReason.CALCULATION_NOT_POSSIBLE,
            (state as PrayerScheduleState.Unavailable).reason,
        )
    }

    @Test
    fun `refuses to show a schedule calculated for a different day`() = runTest {
        // A provider that ignores the request and answers with yesterday's times must not be
        // able to make them look like today's.
        val provider = ScriptedProvider { r ->
            PrayerTimesProviderResult.Success(scheduleFor(r.copy(date = r.date.minusDays(1))))
        }
        val repo = repository(provider)

        val state = repo.ensureSchedule(request()).first()

        assertTrue(state is PrayerScheduleState.Failed)
        // Nothing was cached, so the next read asks the provider again rather than serving it.
        repo.ensureSchedule(request()).first()
        assertEquals(2, provider.callCount)
    }

    @Test
    fun `refuses to show a schedule calculated for a different location`() = runTest {
        val provider = ScriptedProvider { r ->
            PrayerTimesProviderResult.Success(scheduleFor(r.copy(location = otherLocation)))
        }

        val state = repository(provider).ensureSchedule(request()).first()

        assertTrue(state is PrayerScheduleState.Failed)
        assertTrue((state as PrayerScheduleState.Failed).detail.contains("Chattogram"))
    }
}

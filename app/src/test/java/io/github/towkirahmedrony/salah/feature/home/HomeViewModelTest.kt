package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.domain.startup.StoredLocationState
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * The Home screen's **data contract** — the half `HomeUiStateFactoryTest` cannot reach.
 *
 * The factory test covers the mapping from domain state to [HomeUiState], given a schedule. This
 * class covers what produces that state: the flow assembly in [HomeViewModel]. It locks in the
 * properties the Home screen depends on but that no mapping test can express:
 *
 *  - a displayed time can only come from the schedule the repository actually produced, for the
 *    saved location and for the local day **at that location**;
 *  - the schedule is calculated once per request, not once per tick: the countdown re-reads the
 *    clock every second, and the prayer times must not be recalculated with it;
 *  - a new local day, and a new saved location, each produce a new request and a new calculation;
 *  - the calendar snapshot is progressive — Home renders prayer times while the date is still
 *    pending, so an optional input can never hold the screen on its skeleton;
 *  - no configured location is reported as exactly that, instead of a fabricated place, invented
 *    coordinates or a stalled skeleton.
 *
 * Everything runs on virtual time: the clock is moved by hand and the view model's one-second tick
 * is driven through [tick], which advances the scheduler and then runs it at the current instant.
 * `advanceUntilIdle` is deliberately never used here — the tick is an infinite flow, so "idle" is a
 * state this assembly never reaches, and waiting for it would hang the test rather than fail it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    /** 09:00 UTC is 15:00 in Dhaka, which is inside the Dhuhr period of the schedule below. */
    private val instant: Instant = Instant.parse("2026-10-11T09:00:00Z")

    private val date: LocalDate = ZonedDateTime.ofInstant(instant, zone).toLocalDate()

    /** Moved by the tests, so nothing here depends on the wall clock. */
    private val clock = MutableClock(instant, zone)

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    private val dhaka = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, zone.id))

    /** A second saved area, to prove a location change is not silently ignored. */
    private val chattogram = checkNotNull(LocationInfo.create("Chattogram", 22.3569, 91.7832, zone.id))

    private lateinit var storedLocation: MutableStateFlow<LocationInfo?>
    private lateinit var prayerSettings: MutableStateFlow<PrayerSettings>

    @Before
    fun setUp() {
        // `HomeViewModel` builds its state in `viewModelScope`, which is `Dispatchers.Main`. The test
        // dispatcher is installed as Main and handed to `runTest`, so the view model and the test
        // share one scheduler and one virtual clock.
        Dispatchers.setMain(mainDispatcher)
        storedLocation = MutableStateFlow<LocationInfo?>(null)
        prayerSettings = MutableStateFlow(PrayerSettings.DEFAULT)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `home reports the active period the repository's schedule puts this moment in`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka
            val repository = FakeScheduleRepository()
            val states = observe(home(repository))
            runCurrent()

            val ready = latestReady(states)

            // 15:00 in Dhaka: Dhuhr opened at 11:46 and Asr closes it at 15:58.
            assertEquals(PrayerName.DHUHR, ready.currentPrayer.prayer)
            assertTrue("the Dhuhr period is open at 15:00", ready.currentPrayer.isActive)
            assertNotNull("an open period carries its start", ready.currentPrayer.startTime)
            assertEquals(PrayerName.ASR, ready.currentPrayer.endsWithPrayer)

            // The countdown is the time to that real boundary, not a stored value.
            assertEquals("58:00", ready.currentPrayer.countdown)

            // Every prayer on the strip carries a calculated time; none is a placeholder.
            assertEquals(PrayerName.entries.size, ready.prayerRows.size)
            assertTrue(
                "every prayer row needs a real time, was ${ready.prayerRows.map { it.timeLabel }}",
                ready.prayerRows.all { it.timeLabel != null },
            )

            // The saved place, not a default that happens to be Dhaka.
            assertEquals("Dhaka", ready.location.label)

            // The calendar is progressive: Home is readable with times and no date yet, so an
            // optional input cannot hold the prayer content behind it.
            assertNull(
                "the date is expected to still be pending here",
                ready.gregorianDate,
            )
        }

    @Test
    fun `the schedule is calculated once per request and not once per tick`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka
            val repository = FakeScheduleRepository()
            val states = observe(home(repository))
            runCurrent()

            // Today's schedule, plus tomorrow's so Isha's period can be closed at the next Fajr.
            // Which of the two the view model asks for first is not part of the contract — they are
            // two independent shared flows, and either may reach the repository first — so the
            // assertion is about what was asked for, not the order it was asked in.
            val initialRequests = repository.requestedDates()
            assertEquals(2, initialRequests.size)
            assertEquals(setOf(date, date.plusDays(1)), initialRequests.toSet())
            assertNull("a real schedule means no unavailability note", latestReady(states).timetableNote)

            // Ten more ticks inside the same local day. The request has not changed, so the
            // repository must not be asked again: only the countdown is re-derived.
            clock.set(instant.plusSeconds(10))
            tick(10_000)

            assertEquals(
                "a tick must not recalculate the schedule",
                initialRequests,
                repository.requestedDates(),
            )
            assertEquals("57:50", latestReady(states).currentPrayer.countdown)

            // The local day rolls over: the new day's schedule is asked for, and the day after it.
            // That is the date change reaching the data source.
            clock.set(instant.plusSeconds(86_400))
            tick(1_000)

            val afterRollover = repository.requestedDates()
            assertEquals(4, afterRollover.size)
            assertEquals(setOf(date, date.plusDays(1), date.plusDays(2)), afterRollover.toSet())
            assertEquals(
                "the new day is asked for as today, having also been asked for as tomorrow",
                2,
                afterRollover.count { it == date.plusDays(1) },
            )
        }

    @Test
    fun `home waits for a real schedule and shows it the moment one exists`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka
            val repository = FakeScheduleRepository().apply { withheld = true }
            val states = observe(home(repository))
            runCurrent()

            // Nothing but the skeleton: prayer content is the one thing Home may not show early,
            // because a prayer time with no schedule behind it would be an invented one.
            assertEquals(listOf<HomeUiState>(HomeUiState.Loading), states)

            repository.gate.complete(Unit)
            runCurrent()

            assertEquals(PrayerName.DHUHR, latestReady(states).currentPrayer.prayer)
        }

    @Test
    fun `a missing location is reported instead of a fabricated place`() = runTest(mainDispatcher) {
        storedLocation.value = null
        val repository = FakeScheduleRepository()
        val states = observe(home(repository))
        runCurrent()

        val ready = latestReady(states)

        // The screen still renders, with the reason and no values.
        assertNull("no place may be invented for a user who has not chosen one", ready.location.label)
        assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, ready.location.unavailable)
        assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, ready.timetableNote)
        assertTrue(
            "no times can be listed without a location",
            ready.prayerRows.all { it.timeLabel == null },
        )
        assertNull("no countdown without a schedule", ready.currentPrayer.countdown)

        // The repository is asked exactly what it was told: no location, so no times. Salah never
        // substitutes coordinates of its own.
        assertTrue(
            "no schedule may be requested without a location, was ${repository.requests}",
            repository.requests.all { it == null },
        )
    }

    @Test
    fun `moving the saved location recalculates against the new place`() = runTest(mainDispatcher) {
        storedLocation.value = dhaka
        val repository = FakeScheduleRepository()
        val states = observe(home(repository))
        runCurrent()
        assertEquals("Dhaka", latestReady(states).location.label)

        storedLocation.value = chattogram
        runCurrent()

        assertEquals("Chattogram", latestReady(states).location.label)
        assertEquals(
            listOf("Dhaka", "Dhaka", "Chattogram", "Chattogram"),
            repository.requests.map { it?.location?.label },
        )
    }

    @Test
    fun `after Isha the next prayer is tomorrow's Fajr, counted down like every other`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka
            val repository = FakeScheduleRepository()
            val states = observe(home(repository))
            runCurrent()

            // 20:00 in Dhaka: today's Isha (18:51) has begun, so nothing on today's timetable is still
            // ahead. The next prayer is tomorrow's Fajr, and the countdown is to that real instant.
            clock.set(ZonedDateTime.of(date, LocalTime.of(20, 0), zone).toInstant())
            tick(1_000)

            val ready = latestReady(states)
            assertEquals(PrayerName.FAJR, ready.nextPrayer.prayer)
            assertEquals("8:39:00", ready.nextPrayer.countdown)
            assertNotNull("the next prayer carries its time", ready.nextPrayer.time)
            // Tomorrow's Fajr is the same boundary that closes today's Isha period, so the two cards
            // agree instead of one of them going blank.
            assertEquals(PrayerName.ISHA, ready.currentPrayer.prayer)
            assertTrue(ready.currentPrayer.isActive)
            assertEquals("8:39:00", ready.currentPrayer.countdown)
            assertTrue(ready.currentPrayer.endsNextDay)
        }

    @Test
    fun `past midnight the next prayer is the new day's Fajr, not yesterday's leftover`() =
        runTest(mainDispatcher) {
            storedLocation.value = dhaka
            val repository = FakeScheduleRepository()
            val states = observe(home(repository))
            runCurrent()
            // 15:00: the Dhuhr period is open, so the next prayer is Asr.
            assertEquals(PrayerName.ASR, latestReady(states).nextPrayer.prayer)

            // 00:30 the following morning: the local day has rolled over while the app stayed open, the
            // new day's schedule has been requested, and Fajr is simply the next thing on it.
            clock.set(ZonedDateTime.of(date.plusDays(1), LocalTime.of(0, 30), zone).toInstant())
            tick(1_000)

            val ready = latestReady(states)
            assertEquals(PrayerName.FAJR, ready.nextPrayer.prayer)
            assertEquals("4:09:00", ready.nextPrayer.countdown)
            // The period is still the Isha that began yesterday, closed by the new day's own Fajr. That
            // boundary is on *this* schedule, so it is no longer a "next day" one, and its start — which
            // is on yesterday's timetable — is not shown.
            assertEquals(PrayerName.ISHA, ready.currentPrayer.prayer)
            assertTrue(ready.currentPrayer.isActive)
            assertNull("the start is on the previous day's timetable", ready.currentPrayer.startTime)
            assertFalse(ready.currentPrayer.endsNextDay)
            assertTrue(
                "the new day's schedule must have been asked for",
                repository.requestedDates().contains(date.plusDays(1)),
            )
        }

    @Test
    fun `moving the location re-derives tomorrow as well as today`() = runTest(mainDispatcher) {
        storedLocation.value = dhaka
        val repository = FakeScheduleRepository()
        val states = observe(home(repository))
        runCurrent()
        clock.set(ZonedDateTime.of(date, LocalTime.of(20, 0), zone).toInstant())
        tick(1_000)
        assertEquals(PrayerName.FAJR, latestReady(states).nextPrayer.prayer)

        storedLocation.value = chattogram
        runCurrent()

        // The next prayer is re-derived rather than carried over: tomorrow's schedule is requested again
        // for the new place, and the answer still resolves.
        val ready = latestReady(states)
        assertEquals("Chattogram", ready.location.label)
        assertEquals(PrayerName.FAJR, ready.nextPrayer.prayer)
        // Which of the two days is asked for first is not part of the contract — they are independent
        // shared flows — so the assertion is about what was asked for, not the order.
        assertEquals(
            setOf(date, date.plusDays(1)),
            repository.requestedDates().takeLast(2).toSet(),
        )
        assertEquals(
            listOf("Chattogram", "Chattogram"),
            repository.requests.takeLast(2).map { it?.location?.label },
        )
    }

    @Test
    fun `the header date and the timetable name the same location-local day`() = runTest(mainDispatcher) {
        // The device is on UTC and the location is on UTC+6, so 20:00 UTC is already 12 October in Dhaka
        // while the device still says the 11th. That is the reported defect: the date above the times and
        // the times themselves were derived in two different zones. Both must be the location's day.
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            clock.set(Instant.parse("2026-10-11T20:00:00Z"))
            storedLocation.value = dhaka
            val repository = FakeScheduleRepository()
            val stored = MutableStateFlow<StoredLocationState>(StoredLocationState.Loaded(dhaka))
            val states = observe(
                home(
                    repository = repository,
                    calendarRepository = DeviceClockCalendarRepository(
                        clock = clock,
                        dayZone = LocationDayZone(stored),
                    ),
                ),
            )
            runCurrent()

            val ready = latestReady(states)
            // The timetable is for the location's day — 12 October, and the 13th as its tomorrow —
            // rather than the device's 11 October. Which is asked for first is not part of the contract.
            assertEquals(
                setOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)),
                repository.requestedDates().toSet(),
            )
            // ...and the date line above it names that same day, not the device's 11 October.
            assertEquals("12 October 2026", ready.gregorianDate)
            // The header line and the Gregorian section are the same snapshot, so they cannot disagree
            // with each other either.
            assertEquals(ready.gregorianDate, ready.gregorianSection.value)
            // And the Bangla and Hijri lines resolve for that same day rather than going missing.
            assertNotNull(ready.banglaSection.value)
            assertNotNull(ready.hijriSection.value)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    /**
     * A view model over the real factory and the real resolvers, with only the repositories faked.
     *
     * The start-up snapshot collects on the test's own `backgroundScope`, so its collectors cannot
     * outlive the test.
     */
    private fun TestScope.home(
        repository: PrayerScheduleRepository,
        /** The calendar the header reads, defaulting to the one that never produces a snapshot. */
        calendarRepository: CalendarRepository = UnproducedCalendar,
        /** The preference store the dashboard layout is read from; the default layout by default. */
        preferences: UserPreferencesRepository = FakeUserPreferencesRepository(),
    ): HomeViewModel = HomeViewModel(
        startup = StartupSnapshot(
            storedLocationSource = storedLocation,
            prayerSettingsSource = prayerSettings,
            scope = backgroundScope,
        ),
        prayerScheduleRepository = repository,
        calendarRepository = calendarRepository,
        prohibitedPeriodsRepository = UnverifiedProhibitedPeriods,
        uiStateFactory = HomeUiStateFactory(
            nextPrayerResolver = NextPrayerResolver(),
            currentPrayerResolver = CurrentPrayerPeriodResolver(),
            prohibitedStatusResolver = ProhibitedPeriodStatusResolver(),
        ),
        userPreferencesRepository = preferences,
        clock = clock,
    )

    /**
     * Collects Home for the life of the test and hands back the list the collector fills.
     *
     * `uiState` is a `StateFlow`, so it never completes; collecting it on `backgroundScope` is what
     * keeps the subscription — and with it the view model's tick — alive exactly as long as the test
     * needs it, and no longer.
     */
    private fun TestScope.observe(viewModel: HomeViewModel): List<HomeUiState> {
        val states = mutableListOf<HomeUiState>()
        backgroundScope.launch { viewModel.uiState.toList(states) }
        return states
    }

    /**
     * Moves the virtual clock on and lets the view model's tick run.
     *
     * `advanceTimeBy` alone is not enough: whether a task scheduled for *exactly* the target instant
     * runs during the advance is an implementation detail that has changed between coroutines-test
     * releases, and a tick is scheduled for an exact instant. Running the scheduler at the current
     * time afterwards executes it in either case, so a test asserting "the second tick saw the new
     * clock" cannot fail because of that detail.
     */
    private fun TestScope.tick(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    /**
     * The latest state, asserted to be [HomeUiState.Ready].
     *
     * A `Loading` or `Failed` state is a failure of the test, not something to assert around: every
     * test that uses this has already given the view model everything a ready state needs.
     */
    private fun latestReady(states: List<HomeUiState>): HomeUiState.Ready {
        val state = states.lastOrNull()
        assertTrue("expected a Ready state but the latest was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    /**
     * A schedule repository answering with the values the real engine produces for Dhaka (see
     * `AdhanPrayerTimesProviderTest`), laid on the day that was requested.
     *
     * It records every request so the tests can assert *when* the schedule is calculated, and an
     * answer can be withheld to observe what Home shows before one exists.
     */
    private inner class FakeScheduleRepository : PrayerScheduleRepository {

        val requests = mutableListOf<PrayerScheduleRequest?>()

        /** When true, the answer waits for [gate] instead of arriving straight away. */
        var withheld: Boolean = false

        val gate = CompletableDeferred<Unit>()

        override fun ensureSchedule(request: PrayerScheduleRequest?): Flow<PrayerScheduleState> {
            requests += request
            return flow {
                if (withheld) gate.await()
                emit(stateFor(request))
            }
        }

        override suspend fun refresh(request: PrayerScheduleRequest?): PrayerScheduleState =
            stateFor(request)

        fun requestedDates(): List<LocalDate?> = requests.map { it?.date }

        private fun stateFor(request: PrayerScheduleRequest?): PrayerScheduleState =
            if (request == null) {
                // What the repository contract says a null request means.
                PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                    origin = origin,
                )
            } else {
                PrayerScheduleState.Available(
                    schedule = schedule(request),
                    origin = origin,
                )
            }
    }

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "local-adhan-kotlin",
        observedAt = instant,
        isAvailableOffline = true,
    )

    private fun schedule(request: PrayerScheduleRequest): PrayerSchedule = PrayerSchedule(
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
        generatedAt = instant,
    )

    /**
     * A calendar that never produces a snapshot.
     *
     * That is the deliberate case: the date is an optional input on Home, and these tests are about
     * the prayer content being correct without one.
     */
    private object UnproducedCalendar : CalendarRepository {
        override fun observeToday(): Flow<CalendarSnapshot> = flow { awaitCancellation() }

        override fun observeMonth(system: CalendarSystem, anchor: LocalDate): Flow<CalendarMonth> = emptyFlow()

        override fun observeTodayDate(): Flow<LocalDate> = emptyFlow()

        override fun observeRamadanWindow(): Flow<RamadanWindow?> = emptyFlow()
    }

    /** The prohibited periods repository's documented "no reviewed rule set yet" answer. */
    private object UnverifiedProhibitedPeriods : ProhibitedPeriodsRepository {
        override fun observePeriods(schedule: PrayerSchedule?): Flow<ProhibitedPeriodsState> = flow {
            emit(ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.RULES_NOT_VERIFIED))
            awaitCancellation()
        }
    }

    /**
     * A clock the test moves.
     *
     * `HomeViewModel` reads the instant from the clock on every tick and the zone from the platform,
     * so moving the instant is enough to walk the screen across a day boundary — and the zone is
     * never a part of what is asserted, which is what keeps these tests independent of the machine
     * they run on.
     */
    private class MutableClock(private var current: Instant, private val zone: ZoneId) : Clock() {

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun set(instant: Instant) {
            current = instant
        }
    }
}

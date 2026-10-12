package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

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

    /**
     * A view model over the real factory and the real resolvers, with only the repositories faked.
     *
     * The start-up snapshot collects on the test's own `backgroundScope`, so its collectors cannot
     * outlive the test.
     */
    private fun TestScope.home(repository: PrayerScheduleRepository): HomeViewModel = HomeViewModel(
        startup = StartupSnapshot(
            storedLocationSource = storedLocation,
            prayerSettingsSource = prayerSettings,
            scope = backgroundScope,
        ),
        prayerScheduleRepository = repository,
        calendarRepository = UnproducedCalendar,
        prohibitedPeriodsRepository = UnverifiedProhibitedPeriods,
        uiStateFactory = HomeUiStateFactory(
            nextPrayerResolver = NextPrayerResolver(),
            currentPrayerResolver = CurrentPrayerPeriodResolver(),
            prohibitedStatusResolver = ProhibitedPeriodStatusResolver(),
        ),
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

        override fun observeMonth(month: YearMonth): Flow<CalendarMonth> = emptyFlow()

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

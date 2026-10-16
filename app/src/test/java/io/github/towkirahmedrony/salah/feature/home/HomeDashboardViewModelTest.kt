package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaCalculator
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DashboardCard
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
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
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The dashboard's *behaviour*, exercised through [HomeViewModel].
 *
 * Where `DashboardConfigTest` covers the layout algebra in isolation, this class covers what the Home
 * screen actually receives and what the actions do: the enabled cards and their order come from the
 * persisted layout, hiding a card removes it, moving a card reorders it, resetting restores the
 * default layout without touching any other preference, and the context-aware cards — Ramadan and
 * Qibla — resolve honestly in the states that matter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDashboardViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val instant: Instant = Instant.parse("2026-10-11T09:00:00Z")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    private lateinit var storedLocation: MutableStateFlow<LocationInfo?>
    private lateinit var prayerSettings: MutableStateFlow<PrayerSettings>

    private val dhaka = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, zone.id))

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        storedLocation = MutableStateFlow<LocationInfo?>(dhaka)
        prayerSettings = MutableStateFlow(PrayerSettings.DEFAULT)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the dashboard renders the enabled cards in the saved order`() = runTest(mainDispatcher) {
        val preferences = FakeUserPreferencesRepository()
        val states = observe(home(preferences = preferences))
        runCurrent()

        val dashboard = latestReady(states).dashboard
        assertEquals(
            DashboardCard.entries.filter { it.defaultEnabled }.map { it.id },
            dashboard.enabled.map { it.card.id },
        )
        // Hidden cards are simply absent from what is rendered.
        assertFalse(dashboard.enabled.any { it.card == DashboardCard.QIBLA })
        assertFalse(dashboard.enabled.any { it.card == DashboardCard.SOLAR_GEOMETRY })
    }

    @Test
    fun `hiding a card removes it from the dashboard and is persisted`() = runTest(mainDispatcher) {
        val preferences = FakeUserPreferencesRepository()
        val viewModel = home(preferences = preferences)
        val states = observe(viewModel)
        runCurrent()

        viewModel.setCardEnabled(DashboardCard.NIGHT_PERIODS, enabled = false)
        runCurrent()

        assertFalse(latestReady(states).dashboard.enabled.any { it.card == DashboardCard.NIGHT_PERIODS })
        assertFalse(preferences.dashboardLayout.isEnabled(DashboardCard.NIGHT_PERIODS))
        // No other card was affected.
        assertTrue(preferences.dashboardLayout.isEnabled(DashboardCard.NEXT_PRAYER))
        assertTrue(preferences.dashboardLayout.isEnabled(DashboardCard.FORBIDDEN_TIMES))
    }

    @Test
    fun `moving a card reorders the dashboard`() = runTest(mainDispatcher) {
        val viewModel = home()
        val states = observe(viewModel)
        runCurrent()

        viewModel.moveCard(DashboardCard.TODAY_TIMETABLE, delta = -1)
        runCurrent()

        assertEquals(
            listOf(DashboardCard.TODAY_TIMETABLE, DashboardCard.NEXT_PRAYER),
            latestReady(states).dashboard.enabled.take(2).map { it.card },
        )
    }

    @Test
    fun `restoring the default layout resets only the dashboard`() = runTest(mainDispatcher) {
        val preferences = FakeUserPreferencesRepository(
            initialTheme = ThemeMode.DARK,
            initialAppLanguage = AppLanguage.BANGLA,
        )
        val viewModel = home(preferences = preferences)
        val states = observe(viewModel)
        runCurrent()

        viewModel.setCardEnabled(DashboardCard.QIBLA, enabled = true)
        viewModel.setCardEnabled(DashboardCard.NEXT_PRAYER, enabled = false)
        runCurrent()
        assertFalse(latestReady(states).dashboard.enabled.any { it.card == DashboardCard.NEXT_PRAYER })

        viewModel.resetDashboard()
        runCurrent()

        val dashboard = latestReady(states).dashboard
        assertEquals(
            DashboardCard.entries.filter { it.defaultEnabled }.map { it.id },
            dashboard.enabled.map { it.card.id },
        )
        // Unrelated preferences are untouched by the reset.
        assertEquals(ThemeMode.DARK, preferences.themeMode.first())
        assertEquals(AppLanguage.BANGLA, preferences.appLanguage.first())
    }

    @Test
    fun `a data update does not reorder or re-enable any card`() = runTest(mainDispatcher) {
        val viewModel = home()
        val states = observe(viewModel)
        runCurrent()
        viewModel.moveCard(DashboardCard.DAILY_TOOLS, delta = -2)
        runCurrent()
        val arranged = latestReady(states).dashboard.enabled.map { it.card.id }

        // The one-second tick re-emits the moment a few times; the arrangement must not move with the
        // prayer data. `advanceTimeBy` drives the virtual clock the view model reads.
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(arranged, latestReady(states).dashboard.enabled.map { it.card.id })
    }

    @Test
    fun `the Ramadan card resolves Sehri and Iftar from today's timetable while in Ramadan`() =
        runTest(mainDispatcher) {
            val window = RamadanWindow(
                hijriYear = 1448,
                start = date.minusDays(10),
                end = date.plusDays(19),
                source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
            )
            val states = observe(home(calendarRepository = FixedCalendar(window)))
            runCurrent()

            val ramadan = latestReady(states).ramadan
            assertTrue(ramadan.visible)
            assertTrue(ramadan.inRamadan)
            assertEquals(11, ramadan.dayNumber)
            assertEquals(30, ramadan.totalDays)
            // Sehri is the calculated Fajr and Iftar the calculated Maghrib, from the same schedule.
            assertNotNull(ramadan.sehriEndLabel)
            assertNotNull(ramadan.iftarLabel)
        }

    @Test
    fun `the Ramadan card is not shown when Ramadan is far away`() = runTest(mainDispatcher) {
        val window = RamadanWindow(
            hijriYear = 1449,
            start = date.plusDays(200),
            end = date.plusDays(229),
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )
        val states = observe(home(calendarRepository = FixedCalendar(window)))
        runCurrent()

        val ramadan = latestReady(states).ramadan
        assertFalse("Salah must not claim it is Ramadan when it is not", ramadan.visible)
        assertFalse(ramadan.inRamadan)
    }

    @Test
    fun `the Qibla card carries a bearing from the saved location`() = runTest(mainDispatcher) {
        val states = observe(home())
        runCurrent()

        val qibla = latestReady(states).qibla
        assertNotNull(qibla.bearingDegrees)
        // The bearing is a real direction in [0, 360).
        assertTrue(qibla.bearingDegrees!! >= 0.0)
        assertTrue(qibla.bearingDegrees!! < 360.0)
        assertNotNull(qibla.distanceKm)
    }

    @Test
    fun `the Qibla card explains a missing location instead of inventing a direction`() =
        runTest(mainDispatcher) {
            storedLocation.value = null
            val states = observe(home())
            runCurrent()

            val qibla = latestReady(states).qibla
            assertEquals(null, qibla.bearingDegrees)
            assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, qibla.unavailable)
        }

    // --- Fixtures -----------------------------------------------------------------

    private fun TestScope.home(
        preferences: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
        calendarRepository: CalendarRepository = NoCalendar,
    ): HomeViewModel = HomeViewModel(
        startup = StartupSnapshot(
            storedLocationSource = storedLocation,
            prayerSettingsSource = prayerSettings,
            scope = backgroundScope,
        ),
        prayerScheduleRepository = FakeScheduleRepository(),
        calendarRepository = calendarRepository,
        prohibitedPeriodsRepository = NoProhibited,
        uiStateFactory = HomeUiStateFactory(
            nextPrayerResolver = NextPrayerResolver(),
            currentPrayerResolver = CurrentPrayerPeriodResolver(),
            prohibitedStatusResolver = ProhibitedPeriodStatusResolver(),
            qiblaCalculator = QiblaCalculator(),
        ),
        userPreferencesRepository = preferences,
        clock = Clock.fixed(instant, zone),
    )

    private fun TestScope.observe(viewModel: HomeViewModel): List<HomeUiState> {
        val states = mutableListOf<HomeUiState>()
        backgroundScope.launch { viewModel.uiState.toList(states) }
        return states
    }

    private fun latestReady(states: List<HomeUiState>): HomeUiState.Ready {
        val state = states.lastOrNull()
        assertTrue("expected a Ready state but the latest was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "local-adhan-kotlin",
        observedAt = instant,
        isAvailableOffline = true,
    )

    private inner class FakeScheduleRepository : PrayerScheduleRepository {
        override fun ensureSchedule(request: PrayerScheduleRequest?): Flow<PrayerScheduleState> = flow {
            emit(
                if (request == null) {
                    PrayerScheduleState.Unavailable(
                        reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                        origin = origin,
                    )
                } else {
                    PrayerScheduleState.Available(schedule(request), origin)
                },
            )
        }

        override suspend fun refresh(request: PrayerScheduleRequest?): PrayerScheduleState =
            throw UnsupportedOperationException("not used")
    }

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

    /** A calendar that produces no snapshot and no Ramadan window. */
    private object NoCalendar : CalendarRepository {
        override fun observeToday(): Flow<CalendarSnapshot> = flowOf()

        override fun observeMonth(system: CalendarSystem, anchor: LocalDate): Flow<CalendarMonth> =
            flowOf()

        override fun observeTodayDate(): Flow<LocalDate> = flowOf()

        override fun observeRamadanWindow(): Flow<RamadanWindow?> = flowOf()
    }

    /** A calendar that reports a fixed Ramadan window. */
    private class FixedCalendar(private val window: RamadanWindow) : CalendarRepository {
        override fun observeToday(): Flow<CalendarSnapshot> = flowOf()

        override fun observeMonth(system: CalendarSystem, anchor: LocalDate): Flow<CalendarMonth> =
            flowOf()

        override fun observeTodayDate(): Flow<LocalDate> = flowOf(window.start)

        override fun observeRamadanWindow(): Flow<RamadanWindow?> = flowOf(window)
    }

    private object NoProhibited : ProhibitedPeriodsRepository {
        override fun observePeriods(schedule: PrayerSchedule?): Flow<ProhibitedPeriodsState> =
            flowOf(
                ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.RULES_NOT_VERIFIED),
            )
    }
}

package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * The Calendar screen's **data contract** — the half [CalendarUiStateFactoryTest] cannot reach.
 *
 * The factory test covers the mapping from a month to [CalendarUiState], given that month. This class
 * covers what produces it: the flow assembly in [CalendarViewModel], and in particular the property
 * the bug report is about — that selecting a calendar mode changes the calendar the screen is
 * reading, immediately and without restarting anything.
 *
 * It uses the real [DeviceClockCalendarRepository] over a fixed clock and the real converters, so a
 * month asserted here is the month the app builds. `runTest`'s `backgroundScope` holds the collection,
 * exactly as `HomeViewModelTest` does, so the subscription lives precisely as long as the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private lateinit var preferences: FakePreferences
    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // Both the repository and the view model read "today" in the device zone, so pin it to UTC to
        // match the fixed clock. The default locale is pinned to English too, because the Gregorian
        // mode follows the device language and an assertion on "September 2024" must not depend on
        // the machine's own locale.
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.ENGLISH)
        preferences = FakePreferences(CalendarSettings.DEFAULT)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `selecting a mode changes the displayed calendar immediately`() = runTest(mainDispatcher) {
        val viewModel = viewModelAt("2024-09-20T09:00:00Z")
        val states = observe(viewModel)
        runCurrent()

        val gregorian = latestReady(states)
        assertEquals(CalendarSystem.GREGORIAN, gregorian.system)
        assertEquals("September 2024", gregorian.monthLabel)
        assertEquals(30, gregorian.weeks.flatten().count { it != null })

        // 20 September 2024 is inside Ashwin 1431 (16 September - 16 October 2024).
        viewModel.onSelectSystem(CalendarSystem.BANGLA)
        runCurrent()

        val bangla = latestReady(states)
        assertEquals(CalendarSystem.BANGLA, bangla.system)
        assertEquals("আশ্বিন ১৪৩১", bangla.monthLabel)
        assertEquals(LocalDate.of(2024, 9, 16), bangla.monthStart)
        assertEquals(LocalDate.of(2024, 10, 16), bangla.monthEnd)
        assertEquals(31, bangla.weeks.flatten().count { it != null })
        // And it was persisted through the one preference store, not held on the screen.
        assertEquals(CalendarSystem.BANGLA, preferences.calendarSettings.first().preferredSystem)
    }

    @Test
    fun `selecting Hijri shows the Hijri month with Arabic names`() = runTest(mainDispatcher) {
        val viewModel = viewModelAt("2024-03-20T09:00:00Z")
        val states = observe(viewModel)
        runCurrent()

        // 20 March 2024 is inside Ramadan 1445 (11 March - 9 April 2024).
        viewModel.onSelectSystem(CalendarSystem.HIJRI)
        runCurrent()

        val hijri = latestReady(states)
        assertEquals(CalendarSystem.HIJRI, hijri.system)
        assertEquals("رَمَضان ١٤٤٥", hijri.monthLabel)
        assertEquals(LocalDate.of(2024, 3, 11), hijri.monthStart)
        assertEquals(LocalDate.of(2024, 4, 9), hijri.monthEnd)
        assertEquals(30, hijri.weeks.flatten().count { it != null })
    }

    @Test
    fun `month navigation moves one month of the selected calendar system`() = runTest(mainDispatcher) {
        val viewModel = viewModelAt("2024-03-20T09:00:00Z")
        val states = observe(viewModel)
        runCurrent()

        viewModel.onSelectSystem(CalendarSystem.HIJRI)
        runCurrent()
        assertEquals(LocalDate.of(2024, 3, 11), latestReady(states).monthStart)

        // Next is the next Hijri month: Shawwal 1445 begins the day Ramadan ends, 10 April 2024 —
        // not "the next Gregorian month", which is what the previous implementation would have done.
        viewModel.onNextMonth()
        runCurrent()
        val shawwal = latestReady(states)
        assertEquals(LocalDate.of(2024, 4, 10), shawwal.monthStart)
        assertEquals(CalendarSystem.HIJRI, shawwal.system)
        // The selection follows into the displayed month, so the details card never describes a day
        // the user cannot see.
        assertEquals(LocalDate.of(2024, 4, 10), shawwal.selected.date)

        viewModel.onPreviousMonth()
        runCurrent()
        assertEquals(LocalDate.of(2024, 3, 11), latestReady(states).monthStart)
    }

    @Test
    fun `a stored mode opens the calendar in that mode`() = runTest(mainDispatcher) {
        preferences = FakePreferences(CalendarSettings(preferredSystem = CalendarSystem.BANGLA))
        val viewModel = viewModelAt("2024-09-20T09:00:00Z")
        val states = observe(viewModel)
        runCurrent()

        val restored = latestReady(states)
        assertEquals(CalendarSystem.BANGLA, restored.system)
        assertEquals("আশ্বিন ১৪৩১", restored.monthLabel)
    }

    @Test
    fun `the Gregorian mode still reads as a Gregorian month`() = runTest(mainDispatcher) {
        val viewModel = viewModelAt("2026-10-13T09:00:00Z")
        val states = observe(viewModel)
        runCurrent()

        val state = latestReady(states)
        assertEquals(CalendarSystem.GREGORIAN, state.system)
        assertEquals("October 2026", state.monthLabel)
        assertEquals(LocalDate.of(2026, 10, 1), state.monthStart)
        assertEquals(LocalDate.of(2026, 10, 31), state.monthEnd)
        assertEquals((1..31).toList(), state.weeks.flatten().mapNotNull { it?.dayOfMonth })
        assertEquals("13", state.weeks.flatten().first { it?.date == LocalDate.of(2026, 10, 13) }!!.dayLabel)
        assertTrue(state.selectedIsToday)
    }

    /** Builds the view model over the real repository and the real factory, with a fixed clock. */
    private fun viewModelAt(instant: String): CalendarViewModel {
        val clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"))
        val repository = DeviceClockCalendarRepository(
            clock = clock,
            settings = preferences.calendarSettings,
        )
        return CalendarViewModel(
            calendarRepository = repository,
            userPreferencesRepository = preferences,
            uiStateFactory = CalendarUiStateFactory(),
            clock = clock,
        )
    }

    /**
     * Collects the calendar for the life of the test and hands back the list the collector fills.
     *
     * `uiState` is a `StateFlow`, so it never completes; collecting it on `backgroundScope` keeps the
     * subscription — and with it the repository's month flow — alive exactly as long as the test.
     */
    private fun TestScope.observe(viewModel: CalendarViewModel): List<CalendarUiState> {
        val states = mutableListOf<CalendarUiState>()
        backgroundScope.launch { viewModel.uiState.toList(states) }
        return states
    }

    /** The latest state, asserted to be [CalendarUiState.Ready]. */
    private fun latestReady(states: List<CalendarUiState>): CalendarUiState.Ready {
        val state = states.lastOrNull()
        assertTrue("expected a Ready state but the latest was $state", state is CalendarUiState.Ready)
        return state as CalendarUiState.Ready
    }

    /** The one preference store the view model reads and writes, in memory. */
    private class FakePreferences(initial: CalendarSettings) : UserPreferencesRepository {

        private val calendar = MutableStateFlow(initial)

        override val calendarSettings: Flow<CalendarSettings> = calendar

        override suspend fun updateCalendarSettings(transform: (CalendarSettings) -> CalendarSettings) {
            calendar.value = transform(calendar.value)
        }

        override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override val prayerSettings: Flow<PrayerSettings> = MutableStateFlow(PrayerSettings.DEFAULT)

        override suspend fun updatePrayerSettings(transform: (PrayerSettings) -> PrayerSettings) = Unit
    }
}

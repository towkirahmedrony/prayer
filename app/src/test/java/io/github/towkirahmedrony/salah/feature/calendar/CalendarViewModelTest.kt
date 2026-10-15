package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.startup.StoredLocationState
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
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
import org.junit.Assert.assertNotEquals
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
        // Both the repository and the view model read "today" in the zone a location-local day is
        // decided in, which is the device's own zone when no location is configured — the case these
        // tests are — so pin it to UTC to match the fixed clock. (Where a location *is* configured its
        // zone answers instead; see the location-zone test below.) The default locale is pinned to
        // English too, because the Gregorian mode follows the device language and an assertion on
        // "September 2024" must not depend on the machine's own locale.
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
        // A padding cell is a cell with no date, so the real days are the ones that carry a date.
        assertEquals(30, gregorian.weeks.flatten().count { it.date != null })

        // 20 September 2024 is inside Ashwin 1431 (16 September - 16 October 2024).
        viewModel.onSelectSystem(CalendarSystem.BANGLA)
        runCurrent()

        val bangla = latestReady(states)
        assertEquals(CalendarSystem.BANGLA, bangla.system)
        assertEquals("আশ্বিন ১৪৩১", bangla.monthLabel)
        assertEquals(LocalDate.of(2024, 9, 16), bangla.monthStart)
        assertEquals(LocalDate.of(2024, 10, 16), bangla.monthEnd)
        assertEquals(31, bangla.weeks.flatten().count { it.date != null })
        // And it was persisted through the one preference store, not held on the screen.
        assertEquals(CalendarSystem.BANGLA, preferences.calendarSettings.first().preferredSystem)
    }

    @Test
    fun `selecting Hijri shows the Hijri month in the interface language`() = runTest(mainDispatcher) {
        val viewModel = viewModelAt("2024-03-20T09:00:00Z")
        val states = observe(viewModel)
        runCurrent()

        // 20 March 2024 is inside Ramadan 1445 (11 March - 9 April 2024).
        viewModel.onSelectSystem(CalendarSystem.HIJRI)
        runCurrent()

        val hijri = latestReady(states)
        assertEquals(CalendarSystem.HIJRI, hijri.system)
        // The device is English here, so the Hijri month is English rather than Arabic script.
        assertEquals("Ramadan 1445", hijri.monthLabel)
        assertEquals(LocalDate.of(2024, 3, 11), hijri.monthStart)
        assertEquals(LocalDate.of(2024, 4, 9), hijri.monthEnd)
        assertEquals(30, hijri.weeks.flatten().count { it.date != null })
    }

    @Test
    fun `the Hijri month follows the interface language without changing the dates`() =
        runTest(mainDispatcher) {
            val viewModel = viewModelAt("2024-03-20T09:00:00Z")
            val states = observe(viewModel)
            runCurrent()

            viewModel.onSelectSystem(CalendarSystem.HIJRI)
            runCurrent()

            val asEnglish = latestReady(states)
            assertEquals("Ramadan 1445", asEnglish.monthLabel)
            val firstOfRamadan = asEnglish.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!
            assertEquals("1", firstOfRamadan.dayLabel)

            preferences.selectLanguage(AppLanguage.BANGLA)
            runCurrent()

            val asBengali = latestReady(states)
            assertEquals("রমজান ১৪৪৫", asBengali.monthLabel)
            assertEquals(
                "১",
                asBengali.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!.dayLabel,
            )
            // The same calendar, the same span, the same days and the same selection. A language change
            // re-renders; it must not navigate, re-select or move a single date.
            assertEquals(asEnglish.system, asBengali.system)
            assertEquals(asEnglish.monthStart, asBengali.monthStart)
            assertEquals(asEnglish.monthEnd, asBengali.monthEnd)
            assertEquals(asEnglish.selected.date, asBengali.selected.date)
            assertEquals(
                asEnglish.weeks.flatten().mapNotNull { cell -> cell?.date },
                asBengali.weeks.flatten().mapNotNull { cell -> cell?.date },
            )
            // And the language selector is not a calendar setting: the stored system is untouched.
            assertEquals(CalendarSystem.HIJRI, preferences.calendarSettings.first().preferredSystem)
        }

    @Test
    fun `a stored interface language opens the calendar in that language, Hijri included`() =
        runTest(mainDispatcher) {
            // What the General screen's selector leaves in the store, read back by a fresh view model:
            // the same path a process restart takes.
            preferences = FakePreferences(
                CalendarSettings(preferredSystem = CalendarSystem.HIJRI),
                initialLanguage = AppLanguage.BANGLA,
            )

            val viewModel = viewModelAt("2024-03-20T09:00:00Z")
            val states = observe(viewModel)
            runCurrent()

            val restored = latestReady(states)
            assertEquals(CalendarSystem.HIJRI, restored.system)
            assertEquals("রমজান ১৪৪৫", restored.monthLabel)
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

    @Test
    fun `changing the interface language re-renders the same dates and writes no calendar setting`() =
        runTest(mainDispatcher) {
            val viewModel = viewModelAt("2026-10-13T09:00:00Z")
            val states = observe(viewModel)
            runCurrent()

            val asEnglish = latestReady(states)
            assertEquals("October 2026", asEnglish.monthLabel)
            assertEquals(CalendarSystem.GREGORIAN, asEnglish.system)
            val dates = asEnglish.weeks.flatten().mapNotNull { cell -> cell?.date }
            assertEquals(31, dates.size)
            assertEquals(LocalDate.of(2026, 10, 13), asEnglish.selected.date)

            // The General screen's selector stores Bengali, which re-emits the month and re-renders it.
            preferences.selectLanguage(AppLanguage.BANGLA)
            runCurrent()

            val asBengali = latestReady(states)
            // The same calendar, the same month span, the same days and the same selection: a language
            // change is a rendering change and must not navigate, re-select or re-read a date.
            assertEquals(CalendarSystem.GREGORIAN, asBengali.system)
            assertEquals(asEnglish.monthStart, asBengali.monthStart)
            assertEquals(asEnglish.monthEnd, asBengali.monthEnd)
            assertEquals(asEnglish.selected.date, asBengali.selected.date)
            assertEquals(dates, asBengali.weeks.flatten().mapNotNull { cell -> cell?.date })
            assertEquals(asEnglish.selectedIsToday, asBengali.selectedIsToday)
            assertTrue(asBengali.selectedIsToday)
            assertEquals(asEnglish.ramadan.todayDayNumber, asBengali.ramadan.todayDayNumber)

            // Rendered in Bengali instead: the heading is neither English nor still in Western digits,
            // and the day numbers come from the Bangla numeral table.
            assertNotEquals(asEnglish.monthLabel, asBengali.monthLabel)
            assertTrue(
                "the Bengali heading still carries Western digits: ${asBengali.monthLabel}",
                asBengali.monthLabel.none { character -> character in '0'..'9' },
            )
            assertTrue(
                "the Bengali heading carries no Bengali script: ${asBengali.monthLabel}",
                asBengali.monthLabel.any { character -> character.code in 0x0980..0x09FF },
            )
            assertTrue(
                "day numbers are still Western after the language change",
                asBengali.weeks.flatten()
                    .mapNotNull { cell -> cell?.dayLabel }
                    .none { label -> label.any { character -> character in '0'..'9' } },
            )

            // And the interface language is not a calendar setting: the stored calendar is untouched,
            // so switching to Bengali cannot be confused with selecting the Bangla civil calendar.
            assertEquals(CalendarSystem.GREGORIAN, preferences.calendarSettings.first().preferredSystem)
        }

    @Test
    fun `opens on the month containing the location's day, not the device's`() = runTest(mainDispatcher) {
        // The device is pinned to UTC; the location is on UTC+6. At 20:00 UTC on 13 October the device
        // still calls it the 13th while Dhaka has already turned to the 14th. The screen has to agree
        // with the day the calendar repository marks as today, or it would open on a month that does not
        // contain the day it then highlights.
        // The stored location as the app publishes it — already read, because the location gate does not
        // let a location-dependent screen exist before it is.
        val stored = MutableStateFlow<StoredLocationState>(StoredLocationState.Loaded(dhaka))
        val states = observe(viewModelAt("2026-10-13T20:00:00Z", stored))
        runCurrent()

        val ready = latestReady(states)
        assertEquals(LocalDate.of(2026, 10, 14), ready.today.date)
        assertEquals(LocalDate.of(2026, 10, 14), ready.selected.date)
        assertTrue(ready.selectedIsToday)
        // And the grid actually covers that day, which is what makes the anchor the right month.
        assertTrue(
            "the day being marked is not in the grid",
            ready.weeks.flatten().any { cell -> cell?.date == LocalDate.of(2026, 10, 14) },
        )
    }

    /** The stored location the location-zone test resolves its day from. */
    private val dhaka = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"))

    /**
     * Builds the view model over the real repository and the real factory, with a fixed clock.
     *
     * [stored] is the stored location, when the test needs one: both the repository and the view model
     * then take their day from its zone, which is the shared source the production wiring uses.
     */
    private fun TestScope.viewModelAt(
        instant: String,
        stored: MutableStateFlow<StoredLocationState>? = null,
    ): CalendarViewModel {
        val clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"))
        val dayZone = stored?.let { LocationDayZone(it) }
        val repository = DeviceClockCalendarRepository(
            clock = clock,
            settings = preferences.calendarSettings,
            dayZone = dayZone,
        )
        return CalendarViewModel(
            calendarRepository = repository,
            userPreferencesRepository = preferences,
            uiStateFactory = CalendarUiStateFactory(),
            clock = clock,
            dayZone = dayZone,
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
    private class FakePreferences(
        initial: CalendarSettings,
        initialLanguage: AppLanguage = AppLanguage.DEFAULT,
    ) : UserPreferencesRepository {

        private val calendar = MutableStateFlow(initial)
        private val language = MutableStateFlow(initialLanguage)

        override val calendarSettings: Flow<CalendarSettings> = calendar

        override suspend fun updateCalendarSettings(transform: (CalendarSettings) -> CalendarSettings) {
            calendar.value = transform(calendar.value)
        }

        override val appLanguage: Flow<AppLanguage> = language

        override suspend fun setAppLanguage(value: AppLanguage) {
            language.value = value
        }

        /** Moves the stored language, as the General screen's selector would. */
        fun selectLanguage(value: AppLanguage) {
            language.value = value
        }

        override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override val prayerSettings: Flow<PrayerSettings> = MutableStateFlow(PrayerSettings.DEFAULT)

        override suspend fun updatePrayerSettings(transform: (PrayerSettings) -> PrayerSettings) = Unit

        override val prayerConfigOverrides: Flow<PrayerConfigOverrides> =
            MutableStateFlow(PrayerConfigOverrides.NONE)

        override suspend fun updatePrayerConfigOverrides(
            transform: (PrayerConfigOverrides) -> PrayerConfigOverrides,
        ) = Unit
    }
}

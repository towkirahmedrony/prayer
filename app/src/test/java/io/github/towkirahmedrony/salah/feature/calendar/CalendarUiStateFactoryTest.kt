package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * What the Calendar screen is handed.
 *
 * The interesting rules are: which cell is today, which is the selection, and which is both; that a date
 * a converter cannot cover appears as a stated reason rather than an empty space; and that the Ramadan
 * summary is presentable only when it could actually be derived. Each is asserted against a month built
 * by the real repository, so the state under test is the one the app builds.
 */
class CalendarUiStateFactoryTest {

    private val factory = CalendarUiStateFactory()
    private val english = Locale.ENGLISH
    private lateinit var originalDefault: TimeZone

    @Before
    fun pinDefaultZoneToUtc() {
        originalDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalDefault)
    }

    private suspend fun monthAt(
        instant: String,
        month: YearMonth,
        settings: CalendarSettings = CalendarSettings.DEFAULT,
    ) = DeviceClockCalendarRepository(
        clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")),
        settings = flowOf(settings),
    ).observeMonth(month).first()

    @Test
    fun `today is both today and the selection until the user picks another day`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(month, CalendarSettings.DEFAULT, month.today, english) as CalendarUiState.Ready

        assertTrue(state.selectedIsToday)
        assertEquals(LocalDate.of(2024, 3, 11), state.selected.date)
        assertEquals(1, state.weeks.flatten().count { it?.stage == CalendarCellStage.TODAY_SELECTED })
        assertEquals(0, state.weeks.flatten().count { it?.stage == CalendarCellStage.SELECTED })
        assertEquals(0, state.weeks.flatten().count { it?.stage == CalendarCellStage.TODAY })
    }

    @Test
    fun `selecting another day keeps today marked, and they are different marks`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val selected = LocalDate.of(2024, 3, 20)
        val state = factory.create(month, CalendarSettings.DEFAULT, selected, english) as CalendarUiState.Ready

        assertFalse(state.selectedIsToday)
        assertEquals(selected, state.selected.date)
        val selectedCell = state.weeks.flatten().first { it?.date == selected }!!
        val todayCell = state.weeks.flatten().first { it?.date == month.today }!!
        assertEquals(CalendarCellStage.SELECTED, selectedCell.stage)
        assertEquals(CalendarCellStage.TODAY, todayCell.stage)
        // The header still describes today, which is what the user needs in another month.
        assertEquals(month.today, state.today.date)
    }

    @Test
    fun `every day of the selected day is resolved in all three calendars`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(month, CalendarSettings.DEFAULT, LocalDate.of(2024, 3, 11), english) as
            CalendarUiState.Ready

        assertEquals(3, state.selected.lines.size)
        val gregorian = state.selected.lines.first { it.system == CalendarSystem.GREGORIAN }
        val bangla = state.selected.lines.first { it.system == CalendarSystem.BANGLA }
        val hijri = state.selected.lines.first { it.system == CalendarSystem.HIJRI }

        assertEquals("11 March 2024", gregorian.value)
        assertNull(gregorian.source)
        assertEquals("27 Falgun 1430", bangla.value)
        assertEquals("1 Ramadan 1445 AH", hijri.value)
        // The Hijri line always carries the source that says it is an estimate.
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, hijri.source)
        assertEquals("Monday", state.selected.weekday)
        assertEquals(listOf(IslamicOccasion.RAMADAN_BEGINS), state.selected.occasions)
        assertEquals(1, state.selected.ramadanDayNumber)
        assertNull(state.selectedOutOfRange)
    }

    @Test
    fun `a date a converter cannot cover is stated as a reason, not left blank`() = runTest {
        // The 2019 Bangla rules do not describe 2018, and the platform's Hijri table stops in 2174.
        val beforeRules = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2018, 6))
        val state = factory.create(
            beforeRules,
            CalendarSettings(preferredSystem = CalendarSystem.BANGLA),
            LocalDate.of(2018, 6, 15),
            english,
        ) as CalendarUiState.Ready

        val bangla = state.selected.lines.first { it.system == CalendarSystem.BANGLA }
        assertNull(bangla.value)
        assertEquals(UnavailabilityReason.OUT_OF_SUPPORTED_RANGE, bangla.unavailable)
        assertEquals(UnavailabilityReason.OUT_OF_SUPPORTED_RANGE, state.selectedOutOfRange)
        // Padding cells carry no date, and every day of the month is a real cell: the grid never
        // substitutes a placeholder for a date it cannot produce.
        assertTrue(state.weeks.flatten().filter { it?.stage == CalendarCellStage.PADDING }.all { it?.date == null })
        assertTrue(state.weeks.flatten().filter { it?.date != null }.none { it?.stage == CalendarCellStage.PADDING })

        // With Gregorian as the preferred view there is no out-of-range claim to make, because the
        // Gregorian date is always available.
        val asGregorian = factory.create(
            beforeRules,
            CalendarSettings(preferredSystem = CalendarSystem.GREGORIAN),
            LocalDate.of(2018, 6, 15),
            english,
        ) as CalendarUiState.Ready
        assertNull(asGregorian.selectedOutOfRange)
    }

    @Test
    fun `a date with no Hijri conversion says so and carries no source`() = runTest {
        val beyondTable = monthAt("2175-01-01T09:00:00Z", YearMonth.of(2175, 1))
        val state = factory.create(
            beyondTable,
            CalendarSettings.DEFAULT,
            LocalDate.of(2175, 1, 1),
            english,
        ) as CalendarUiState.Ready

        val hijri = state.selected.lines.first { it.system == CalendarSystem.HIJRI }
        assertNull(hijri.value)
        assertNull(hijri.source)
        assertEquals(UnavailabilityReason.OUT_OF_SUPPORTED_RANGE, hijri.unavailable)
        // And the Ramadan section makes no claim at all rather than an estimated one.
        assertNull(state.ramadan.rangeLabel)
        assertNull(state.ramadan.hijriYear)
        assertNull(state.ramadan.source)
        assertNull(state.ramadan.todayDayNumber)
    }

    @Test
    fun `weekday headers follow the locale's own first day of the week`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(month, CalendarSettings.DEFAULT, month.today, english) as CalendarUiState.Ready

        assertEquals(7, state.weekdayLabels.size)
        val firstRealDate = month.weeks.flatten().first { it != null }!!.date
        assertEquals(
            firstRealDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, english),
            state.weekdayLabels.first(),
        )
        // The last column is the day before the first, in week order: the header is a real week.
        val lastColumn = firstRealDate.dayOfWeek.plus(6)
        assertEquals(lastColumn.getDisplayName(java.time.format.TextStyle.SHORT, english), state.weekdayLabels.last())
    }

    @Test
    fun `the Ramadan summary is labelled as an estimate and carries the range`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(month, CalendarSettings.DEFAULT, month.today, english) as CalendarUiState.Ready

        assertEquals(RamadanPhase.IN_RAMADAN, state.ramadan.phase)
        assertEquals(1445, state.ramadan.hijriYear)
        assertEquals("11 March 2024 – 9 April 2024", state.ramadan.rangeLabel)
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, state.ramadan.source)
        assertEquals(1, state.ramadan.todayDayNumber)
        // 11–31 March are Ramadan days 1–21; the first ten days of the month are not in Ramadan.
        assertEquals(21, month.days.count { it.ramadanDayNumber != null })
        // Every Ramadan day in the grid is flagged, and the day before is not.
        assertTrue(state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!.isRamadanDay)
        assertFalse(state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 10) }!!.isRamadanDay)
    }

    @Test
    fun `the user's Hijri adjustment is carried into the state and moves the Hijri date`() = runTest {
        // The adjustment is an input to the repository that builds the month, exactly as the app's
        // ViewModel feeds it from the one stored preference — so the day data and the header agree.
        val settings = CalendarSettings(hijriAdjustmentDays = 2)
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3), settings)
        val state = factory.create(
            month,
            settings,
            LocalDate.of(2024, 3, 11),
            english,
        ) as CalendarUiState.Ready

        assertEquals(2, state.hijriAdjustmentDays)
        val hijri = state.selected.lines.first { it.system == CalendarSystem.HIJRI }
        assertEquals("3 Ramadan 1445 AH", hijri.value)
        assertEquals(HijriDateSource.ESTIMATED_USER_ADJUSTED, hijri.source)
        // And the Ramadan day number moves with it, because the window moves too: 11 March is the
        // third day of a Ramadan that starts on 9 March under this offset, which is the same day the
        // Hijri line reports.
        assertEquals(3, state.selected.ramadanDayNumber)
        assertEquals(3, state.ramadan.todayDayNumber)
    }

    @Test
    fun `a month with no days at all fails with a reason instead of an empty grid`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val empty = month.copy(weeks = emptyList())
        val state = factory.create(empty, CalendarSettings.DEFAULT, LocalDate.of(2024, 3, 11), english)

        assertTrue(state is CalendarUiState.Failed)
        assertNotNull((state as CalendarUiState.Failed).detail)
    }

    @Test
    fun `the preferred view is reported, and the grid is always the Gregorian month`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(
            month,
            CalendarSettings(preferredSystem = CalendarSystem.HIJRI),
            month.today,
            english,
        ) as CalendarUiState.Ready

        assertEquals(CalendarSystem.HIJRI, state.system)
        // The month itself does not change with the view: Bangla and Hijri are read *over* the
        // Gregorian grid, because a civil month is what a user navigates.
        assertEquals(YearMonth.of(2024, 3), state.month)
        assertEquals("March 2024", state.monthLabel)
        // The 11th is a Monday in the grid, whatever view is selected.
        assertEquals(
            DayOfWeek.MONDAY,
            state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!.date!!.dayOfWeek,
        )
    }
}

package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
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
import org.junit.Assert.assertNotEquals
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
import java.time.format.TextStyle
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

    /** The locale the shell passes when the interface language is Bengali. */
    private val bengali = Locale.forLanguageTag("bn")

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

    /**
     * The month the app would build: the selected system's month containing `month`'s first day.
     *
     * The system comes from [settings], exactly as the view model passes it, so the month under test
     * is never built for one calendar and rendered as another.
     */
    private suspend fun monthAt(
        instant: String,
        month: YearMonth,
        settings: CalendarSettings = CalendarSettings.DEFAULT,
    ) = DeviceClockCalendarRepository(
        clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")),
        settings = flowOf(settings),
    ).observeMonth(settings.preferredSystem, month.atDay(1)).first()

    /**
     * Same, but anchored on an explicit Gregorian day, so a test can sit inside a Bangla or Hijri
     * month whose span does not line up with a Gregorian month.
     */
    private suspend fun monthAt(
        instant: String,
        anchor: LocalDate,
        settings: CalendarSettings,
    ) = DeviceClockCalendarRepository(
        clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")),
        settings = flowOf(settings),
    ).observeMonth(settings.preferredSystem, anchor).first()

    /** True when [this] is in the Bengali Unicode block (U+0980–U+09FF). */
    private fun Char.inBengaliBlock(): Boolean = code in 0x0980..0x09FF

    @Test
    fun `a Bengali interface renders the grid in Bengali without moving a single date`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        // Gregorian, with the stored date language left on "follow the interface": the case the
        // language selector actually changes.
        val settings = CalendarSettings.DEFAULT

        val asEnglish = factory.create(month, settings, month.today, english) as CalendarUiState.Ready
        val asBengali = factory.create(month, settings, month.today, bengali) as CalendarUiState.Ready

        // Same calendar, same month, same selection, same Ramadan data: only the script changed. If a
        // language choice could move any of these it would be a date change, not a translation.
        assertEquals(CalendarSystem.GREGORIAN, asBengali.system)
        assertEquals(asEnglish.month, asBengali.month)
        assertEquals(asEnglish.monthStart, asBengali.monthStart)
        assertEquals(asEnglish.monthEnd, asBengali.monthEnd)
        assertEquals(asEnglish.selected.date, asBengali.selected.date)
        assertEquals(asEnglish.selectedIsToday, asBengali.selectedIsToday)
        assertEquals(asEnglish.weeks.size, asBengali.weeks.size)
        assertEquals(asEnglish.hijriAdjustmentDays, asBengali.hijriAdjustmentDays)
        assertEquals(asEnglish.ramadan.todayDayNumber, asBengali.ramadan.todayDayNumber)

        // The heading really is Bengali: `March 2024` is what English gives, and the Bengali one is
        // neither that nor still written in Western digits.
        assertEquals("March 2024", asEnglish.monthLabel)
        assertNotEquals(asEnglish.monthLabel, asBengali.monthLabel)
        assertTrue(
            "the Bengali month heading is still in Western digits: ${asBengali.monthLabel}",
            asBengali.monthLabel.none { character -> character in '0'..'9' },
        )

        // Day numbers are composed from the Bangla numeral table, so this holds on every device
        // regardless of how complete its locale data is.
        val bengaliDayLabels = asBengali.weeks.flatten().mapNotNull { cell -> cell?.dayLabel }
        assertTrue("the Bengali grid has no day numbers at all", bengaliDayLabels.isNotEmpty())
        bengaliDayLabels.forEach { label ->
            assertTrue("a Bengali day number is still Western: $label", label.none { it in '0'..'9' })
        }
        assertTrue(
            "no Bengali day number carries a Bengali digit",
            bengaliDayLabels.any { label -> label.any { character -> character.inBengaliBlock() } },
        )

        // The weekday header follows the same locale, and the columns still line up with the days
        // beneath them — the grid's alignment is a property of the month, not of the language.
        assertEquals(7, asBengali.weekdayLabels.size)
        assertNotEquals(asEnglish.weekdayLabels, asBengali.weekdayLabels)
        asBengali.weekdayLabels.forEach { label ->
            assertTrue(
                "a Bengali weekday header is still Latin: $label",
                label.any { character -> character.inBengaliBlock() },
            )
        }
        asBengali.weeks.forEach { week ->
            week.forEachIndexed { column, cell ->
                cell?.date?.let { date ->
                    assertEquals(
                        "weekday header and day are misaligned after the language change",
                        date.dayOfWeek.getDisplayName(TextStyle.SHORT, bengali),
                        asBengali.weekdayLabels[column],
                    )
                }
            }
        }

        // Today is still today, and still the selection: the marks survive the language change.
        assertEquals(
            asEnglish.weeks.flatten().count { it?.stage == CalendarCellStage.TODAY_SELECTED },
            asBengali.weeks.flatten().count { it?.stage == CalendarCellStage.TODAY_SELECTED },
        )
    }

    @Test
    fun `an explicit date language still wins over the interface language`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val chosenEnglish = CalendarSettings(dateLanguage = DateLanguagePreference.ENGLISH)

        val state = factory.create(month, chosenEnglish, month.today, bengali) as CalendarUiState.Ready

        // A Bengali interface does not override a deliberate statement about the dates themselves.
        assertEquals("March 2024", state.monthLabel)
        assertEquals(CalendarSystem.GREGORIAN, state.system)
        assertEquals(DateLanguagePreference.ENGLISH, state.dateLanguage)
    }

    @Test
    fun `the interface language renders all three calendars but changes none of them`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val selected = LocalDate.of(2024, 3, 11)

        val asEnglish = factory.create(month, CalendarSettings.DEFAULT, selected, english) as CalendarUiState.Ready
        val asBengali = factory.create(month, CalendarSettings.DEFAULT, selected, bengali) as CalendarUiState.Ready

        // The same three lines, describing the same date, with the same availability — a translation
        // must never add or remove a representation.
        assertEquals(asEnglish.selected.lines.map { it.system }, asBengali.selected.lines.map { it.system })
        assertEquals(asEnglish.selected.lines.map { it.unavailable }, asBengali.selected.lines.map { it.unavailable })
        assertEquals(selected, asBengali.selected.date)
        assertEquals(
            HijriDateSource.ESTIMATED_UMM_AL_QURA,
            asBengali.selected.lines.first { it.system == CalendarSystem.HIJRI }.source,
        )
        // And the date list's Gregorian line is Bengali, while the Bangla and Hijri lines were already
        // in their own scripts.
        assertTrue(
            asBengali.selected.lines.first { it.system == CalendarSystem.GREGORIAN }
                .value!!.any { character -> character.inBengaliBlock() },
        )
    }

    @Test
    fun `the selected calendar system comes from the settings, not from the language`() = runTest {
        val banglaSettings = CalendarSettings(preferredSystem = CalendarSystem.BANGLA)
        val anchor = LocalDate.of(2024, 3, 11)
        val month = monthAt("2024-03-11T09:00:00Z", anchor, banglaSettings)

        val asEnglish = factory.create(month, banglaSettings, anchor, english) as CalendarUiState.Ready
        val asBengali = factory.create(month, banglaSettings, anchor, bengali) as CalendarUiState.Ready

        assertEquals(CalendarSystem.BANGLA, asEnglish.system)
        assertEquals(CalendarSystem.BANGLA, asBengali.system)
        // The Bangla mode presents the calendar in Bengali in both, because that mode is about the
        // dates themselves; only the side-by-side date list defers to the interface language.
        assertEquals(asEnglish.monthLabel, asBengali.monthLabel)
    }

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
    fun `weekday headers label the columns the grid actually lays out`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(month, CalendarSettings.DEFAULT, month.today, english) as CalendarUiState.Ready

        // The header's first column is the week's first day — the value the grid padded with — not the
        // weekday the month happens to start on. March 2024 starts on a Friday, so deriving the header
        // from the first real day would shift every label by five columns.
        assertEquals(7, state.weekdayLabels.size)
        assertEquals(
            month.firstDayOfWeek.getDisplayName(TextStyle.SHORT, english),
            state.weekdayLabels.first(),
        )
        assertEquals(
            month.firstDayOfWeek.plus(6).getDisplayName(TextStyle.SHORT, english),
            state.weekdayLabels.last(),
        )

        // And every column carries the weekday of the days beneath it, so no header can mislabel one.
        state.weeks.forEachIndexed { weekIndex, week ->
            week.forEachIndexed { column, cell ->
                if (cell?.date != null) {
                    assertEquals(
                        "week $weekIndex, column $column",
                        cell.date!!.dayOfWeek.getDisplayName(TextStyle.SHORT, english),
                        state.weekdayLabels[column],
                    )
                }
            }
        }
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
    fun `selecting the Bangla view lays out the Bangla calendar month, not the Gregorian one`() = runTest {
        // 20 September 2024 is inside Ashwin 1431, which runs 16 September to 16 October 2024.
        val settings = CalendarSettings(preferredSystem = CalendarSystem.BANGLA)
        val month = monthAt("2024-09-20T09:00:00Z", LocalDate.of(2024, 9, 20), settings)
        val state = factory.create(month, settings, LocalDate.of(2024, 9, 20), english) as CalendarUiState.Ready

        assertEquals(CalendarSystem.BANGLA, state.system)
        // The heading is the Bangla month and year in Bengali script, not "September 2024".
        assertEquals("আশ্বিন ১৪৩১", state.monthLabel)
        // The grid is the Bangla month's own span, and Ashwin has 31 days.
        assertEquals(LocalDate.of(2024, 9, 16), state.monthStart)
        assertEquals(LocalDate.of(2024, 10, 16), state.monthEnd)
        // Padding cells carry no date, so this counts the real days of the month.
        assertEquals(31, state.weeks.flatten().count { it.date != null })
        // Every cell number is the Bangla day of month, 1..31, and the digits are Bengali.
        assertEquals((1..31).toList(), state.weeks.flatten().mapNotNull { it?.dayOfMonth })
        val firstOfAshwin = state.weeks.flatten().first { it?.date == LocalDate.of(2024, 9, 16) }!!
        assertEquals(1, firstOfAshwin.dayOfMonth)
        assertEquals("১", firstOfAshwin.dayLabel)
        // Weekday headers are in Bengali script, not the English abbreviations.
        assertTrue(state.weekdayLabels.all { it.isNotBlank() })
        assertTrue(
            "expected Bengali weekday labels, were ${state.weekdayLabels}",
            state.weekdayLabels.any { label -> label.any { character -> character.code in BENGALI_SCRIPT } },
        )
        // The cell still carries its Gregorian identity, so date-dependent features are unaffected.
        assertEquals(LocalDate.of(2024, 9, 16), firstOfAshwin.date)
    }

    @Test
    fun `selecting the Hijri view follows the interface language instead of always using Arabic`() = runTest {
        // 20 March 2024 is inside Ramadan 1445, which runs 11 March to 9 April 2024.
        val settings = CalendarSettings(preferredSystem = CalendarSystem.HIJRI)
        val month = monthAt("2024-03-20T09:00:00Z", LocalDate.of(2024, 3, 20), settings)
        val state = factory.create(month, settings, LocalDate.of(2024, 3, 20), english) as CalendarUiState.Ready

        assertEquals(CalendarSystem.HIJRI, state.system)
        // The English interface gets the Hijri month in English, not in Arabic script.
        assertEquals("Ramadan 1445", state.monthLabel)
        assertEquals(LocalDate.of(2024, 3, 11), state.monthStart)
        assertEquals(LocalDate.of(2024, 4, 9), state.monthEnd)
        // Ramadan 1445 has 30 days, and the grid holds exactly those days numbered 1..30.
        assertEquals(30, state.weeks.flatten().count { it.date != null })
        assertEquals((1..30).toList(), state.weeks.flatten().mapNotNull { it?.dayOfMonth })
        val firstOfRamadan = state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!
        assertEquals(1, firstOfRamadan.dayOfMonth)
        // Western numerals beside the Latin month name, so the English grid is not half-localised.
        assertEquals("1", firstOfRamadan.dayLabel)
        assertNoArabicScript(state)
    }

    @Test
    fun `selecting the Hijri view in a Bengali interface renders Bengali names and numerals`() = runTest {
        val settings = CalendarSettings(preferredSystem = CalendarSystem.HIJRI)
        val month = monthAt("2024-03-20T09:00:00Z", LocalDate.of(2024, 3, 20), settings)
        val state = factory.create(month, settings, LocalDate.of(2024, 3, 20), bengali) as CalendarUiState.Ready

        assertEquals(CalendarSystem.HIJRI, state.system)
        assertEquals("রমজান ১৪৪৫", state.monthLabel)
        assertEquals(LocalDate.of(2024, 3, 11), state.monthStart)
        assertEquals(LocalDate.of(2024, 4, 9), state.monthEnd)
        val firstOfRamadan = state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!
        // Bengali numerals beside the Bengali month name, not Arabic-Indic ones.
        assertEquals("১", firstOfRamadan.dayLabel)
        assertTrue(
            "expected Bengali weekday labels, were ${state.weekdayLabels}",
            state.weekdayLabels.any { label -> label.any { character -> character.code in BENGALI_SCRIPT } },
        )
        assertNoArabicScript(state)
    }

    @Test
    fun `the Hijri dates in the selected-day card follow the interface language`() = runTest {
        val settings = CalendarSettings(preferredSystem = CalendarSystem.HIJRI)
        val month = monthAt("2024-03-20T09:00:00Z", LocalDate.of(2024, 3, 20), settings)
        val englishState = factory.create(month, settings, LocalDate.of(2024, 3, 20), english) as
            CalendarUiState.Ready
        val bengaliState = factory.create(month, settings, LocalDate.of(2024, 3, 20), bengali) as
            CalendarUiState.Ready

        val englishHijri = englishState.selected.lines.first { it.system == CalendarSystem.HIJRI }
        val bengaliHijri = bengaliState.selected.lines.first { it.system == CalendarSystem.HIJRI }

        // The card describes the selected day — 20 March 2024, which is 10 Ramadan 1445 because
        // 11 March is 1 Ramadan.
        assertEquals(LocalDate.of(2024, 3, 20), englishState.selected.date)
        assertEquals(englishState.selected.date, bengaliState.selected.date)
        // The same day described twice; only the rendering language differs.
        assertEquals("10 Ramadan 1445 AH", englishHijri.value)
        assertEquals("১০ রমজান ১৪৪৫ AH", bengaliHijri.value)
        assertEquals("Wednesday", englishState.selected.weekday)
        assertTrue(
            "expected a Bengali weekday, was ${bengaliState.selected.weekday}",
            bengaliState.selected.weekday.any { it.code in BENGALI_SCRIPT },
        )
        assertNoArabicScript(englishState)
        assertNoArabicScript(bengaliState)
    }

    @Test
    fun `the Gregorian view is unchanged by the other modes' corrections`() = runTest {
        val month = monthAt("2024-03-11T09:00:00Z", YearMonth.of(2024, 3))
        val state = factory.create(month, CalendarSettings.DEFAULT, LocalDate.of(2024, 3, 11), english) as
            CalendarUiState.Ready

        assertEquals(CalendarSystem.GREGORIAN, state.system)
        assertEquals("March 2024", state.monthLabel)
        assertEquals(YearMonth.of(2024, 3), state.month)
        assertEquals(LocalDate.of(2024, 3, 1), state.monthStart)
        assertEquals(LocalDate.of(2024, 3, 31), state.monthEnd)
        assertEquals((1..31).toList(), state.weeks.flatten().mapNotNull { it?.dayOfMonth })
        assertEquals("11", state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!.dayLabel)
        assertEquals(
            DayOfWeek.MONDAY,
            state.weeks.flatten().first { it?.date == LocalDate.of(2024, 3, 11) }!!.date!!.dayOfWeek,
        )
    }

    /**
     * Fails when any Hijri label in [state] is still written in Arabic script.
     *
     * The Arabic month names and Arabic-Indic numerals exist for an Arabic rendering locale only, so an
     * English or Bengali calendar must contain no character from the Arabic block at all. Checking every
     * label the screen draws — the heading, the weekday headers, the grid's day numbers and both date
     * cards — is what makes "no Arabic left in the English or Bengali UI" a real assertion rather than a
     * spot check.
     */
    private fun assertNoArabicScript(state: CalendarUiState.Ready) {
        val labels = listOf(state.monthLabel) +
            state.weekdayLabels +
            state.weeks.flatten().mapNotNull { it.dayLabel } +
            listOf(state.selected.weekday, state.today.weekday) +
            (state.selected.lines + state.today.lines).mapNotNull { it.value }

        val offenders = labels.filter { label -> label.any { it.code in ARABIC_SCRIPT } }
        assertTrue("expected no Arabic-script labels, found $offenders", offenders.isEmpty())
    }

    private companion object {
        /** Unicode block for Bengali, so a header can be asserted to be in Bengali script. */
        val BENGALI_SCRIPT = 0x0980..0x09FF

        /** Unicode block for Arabic, so a header can be asserted to be in Arabic script. */
        val ARABIC_SCRIPT = 0x0600..0x06FF
    }
}

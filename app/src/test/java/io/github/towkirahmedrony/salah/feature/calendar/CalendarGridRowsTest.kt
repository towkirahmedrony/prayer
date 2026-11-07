package io.github.towkirahmedrony.salah.feature.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The reserved grid height every calendar mode shares.
 *
 * A month occupies four to six week rows depending on the weekday it starts on, so the block the
 * screen draws has to reserve a fixed number of rows ([CalendarGridMetrics.WEEK_ROWS]) and pad a
 * shorter month with blank cells. These tests pin the two things that makes safe: the padding is
 * blank, and no real day is moved, dropped or duplicated by it.
 */
class CalendarGridRowsTest {

    private fun dayCell(date: LocalDate) = CalendarCell(
        date = date,
        dayOfMonth = date.dayOfMonth,
        dayLabel = date.dayOfMonth.toString(),
        stage = CalendarCellStage.REGULAR,
        isRamadanDay = false,
        occasion = null,
    )

    /** A whole week of real days, [offsetDays] after [START]. */
    private fun weekFrom(offsetDays: Int): List<CalendarCell> =
        (0 until 7).map { step -> dayCell(START.plusDays((offsetDays + step).toLong())) }

    private fun blankWeek(): List<CalendarCell> = List(7) { blankCell() }

    @Test
    fun `a four week month is padded to the reserved six rows`() {
        val month = listOf(weekFrom(0), weekFrom(7), weekFrom(14), weekFrom(21))

        val reserved = month.reservedToWeekRows()

        assertEquals(CalendarGridMetrics.WEEK_ROWS, reserved.size)
        assertEquals(6, reserved.size)
        reserved.forEach { week -> assertEquals(7, week.size) }

        // The added rows carry no date at all, and say so by their stage.
        reserved.drop(month.size).forEach { week ->
            week.forEach { cell ->
                assertNull(cell.date)
                assertNull(cell.dayOfMonth)
                assertNull(cell.dayLabel)
                assertEquals(CalendarCellStage.PADDING, cell.stage)
            }
        }
    }

    @Test
    fun `a month that already fills the reserved rows is returned unchanged`() {
        val month = (0 until 6).map { index -> weekFrom(index * 7) }

        val reserved = month.reservedToWeekRows()

        assertSame(month, reserved)
    }

    @Test
    fun `padding never adds, drops or reorders a real date`() {
        // A five-row month: four blank cells, then the days, then the trailing blanks of its last row.
        val month = listOf(
            List(4) { blankCell() } + weekFrom(0).take(3),
            weekFrom(3),
            weekFrom(10),
            weekFrom(17),
            weekFrom(24).take(4) + List(3) { blankCell() },
        )
        val realDatesBefore = month.flatten().mapNotNull { cell -> cell.date }

        val reserved = month.reservedToWeekRows()
        val realDatesAfter = reserved.flatten().mapNotNull { cell -> cell.date }

        assertEquals(1, reserved.size - month.size)
        assertEquals(realDatesBefore, realDatesAfter)
        assertEquals(28, realDatesAfter.size)
        assertTrue(
            reserved.flatten().filter { cell -> cell.date == null }
                .all { cell -> cell.stage == CalendarCellStage.PADDING },
        )
    }

    @Test
    fun `a reservation smaller than the month leaves every row and day in place`() {
        val month = listOf(weekFrom(0), weekFrom(7), weekFrom(14), weekFrom(21), weekFrom(28))

        // The grid never shrinks a month to fit a reservation: a real day is not droppable.
        assertEquals(month, month.reservedToWeekRows(rows = 4))
    }

    @Test
    fun `an empty grid stays empty rather than claiming a month`() {
        // The state's contract is that a month with no days is a failure, not a blank calendar, so the
        // reservation must not turn "nothing" into six rows of exclamation-free blank cells.
        assertEquals(emptyList<List<CalendarCell>>(), emptyList<List<CalendarCell>>().reservedToWeekRows())
    }

    private companion object {
        /** A Wednesday, so the weeks below keep the same weekday columns a real month would. */
        val START: LocalDate = LocalDate.of(2024, 1, 3)
    }
}

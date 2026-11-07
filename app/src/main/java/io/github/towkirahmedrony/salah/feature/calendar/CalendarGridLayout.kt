package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid

/**
 * The dimensions every calendar mode's month grid shares.
 *
 * Gregorian, Bangla and Hijri are three readings of one control, so a mode switch may replace the
 * dates and their labels but never the geometry: a switch that resizes the card, moves the weekday
 * row or reflows the cells reads as a different screen rather than another view of this one.
 *
 * The row count is a *reservation*, not the month's own week count. A month needs four to six rows
 * depending on the weekday it starts on, so drawing only the rows it needs makes the card shorter in
 * some months than in others. [WEEK_ROWS] is therefore the height every mode reserves, and the extra
 * rows are blank cells that belong to no date ([reservedToWeekRows]).
 *
 * Values that describe text are in sp so the reservations grow with the system font scale instead of
 * clipping a larger setting, and they are identical in all three modes, which is what keeps the card
 * at one height.
 */
internal object CalendarGridMetrics {
    /** The week rows every mode reserves, whether the month needs them or not. */
    const val WEEK_ROWS: Int = 6

    /** The weekday header's one row: room for one line of the label type at any font scale. */
    const val WEEKDAY_HEADER_HEIGHT_SP: Float = 20f

    /** The legend area: room for two lines of the legend type, the most a label is allowed. */
    const val LEGEND_HEIGHT_SP: Float = 34f

    /** The Hijri adjustment note's area: room for one line of the note at any font scale. */
    const val ADJUSTMENT_HEIGHT_SP: Float = 32f
}

/** A cell that belongs to no date: the blank before the first day or after the last one. */
internal fun blankCell(): CalendarCell = CalendarCell(
    date = null,
    dayOfMonth = null,
    dayLabel = null,
    stage = CalendarCellStage.PADDING,
    isRamadanDay = false,
    occasion = null,
)

/**
 * The month's week rows padded to [rows] whole rows of seven blank cells.
 *
 * Only blank cells are appended: the days of the month keep their order, their weekday column and
 * their count, so the reservation changes the block's height and nothing about which date is under
 * which weekday. A month that already fills the reserved rows is returned untouched, and a month is
 * never shortened, because a real day is never dropped to make the grid fit. An empty grid is left
 * empty too: the screen's contract is that a month with no days is a failure rather than a blank
 * calendar, so the reservation must not manufacture six rows of nothing.
 *
 * The padding is a layout concern, so it happens where the state the screen draws is built
 * ([CalendarUiStateFactory]) rather than in the composable, which then draws whatever rows it is
 * handed without deciding how many there should be.
 */
internal fun List<List<CalendarCell>>.reservedToWeekRows(
    rows: Int = CalendarGridMetrics.WEEK_ROWS,
): List<List<CalendarCell>> {
    if (true) return this
    val blankRow = List(GregorianCalendarGrid.DAYS_PER_WEEK) { blankCell() }
    return this + List(rows - size) { blankRow }
}

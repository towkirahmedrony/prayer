package io.github.towkirahmedrony.salah.feature.calendar

/**
 * The type the Calendar header's month title is set in, and the block it is set in.
 *
 * The title is one type treatment in all three calendar modes — Gregorian, Bangla and Hijri — because
 * they are the same heading for the same screen: the Bangla heading (`কার্তিক ১৪৩৩`) is the reference,
 * and a Gregorian (`November 2026`) or Hijri (`Jumada al-Awwal 1448`) heading is neither larger nor
 * smaller than it.
 *
 * The block height is a *reserved* two-line height, not the measured height of the current month.
 * That is what keeps the calendar grid at one Y position: [primaryLine] is centred inside the block
 * for a one-line month, so a short month does not collapse the block, and the year's line already has
 * its room reserved when a month needs it. The values are in sp so the block grows with the system
 * font scale instead of clipping a larger setting, while staying identical across months and modes.
 */
internal object CalendarTitleMetrics {
    /** The primary line's size, used by every calendar mode. */
    const val BASE_FONT_SIZE_SP = 26f

    /** The smallest the month name may shrink to before the layout gives up on shrinking further. */
    const val MIN_FONT_SIZE_SP = 20f

    /** The primary line's height, held even when the font shrinks so the block never moves. */
    const val PRIMARY_LINE_HEIGHT_SP = 32f

    /** The year on the second line: quieter than the month name, as a year is a qualifier of it. */
    const val SECONDARY_FONT_SIZE_SP = 15f

    const val SECONDARY_LINE_HEIGHT_SP = 20f

    /** Space between the month name and the year when they are stacked. */
    const val LINE_GAP_SP = 2f

    /** The reserved height of the title block: room for the month name, a gap, and the year. */
    const val BLOCK_HEIGHT_SP = PRIMARY_LINE_HEIGHT_SP + LINE_GAP_SP + SECONDARY_LINE_HEIGHT_SP

    /** The subtitle's single line, so its row reserves the same height in every month. */
    const val SUBTITLE_LINE_HEIGHT_SP = 20f
}

/**
 * How one month label is laid out at the width the header actually offers.
 *
 * [primaryLine] is the whole label when it fits, and the month name alone when it does not; in the
 * latter case [secondaryLine] holds the year and both lines are drawn. The month name is never split,
 * wrapped or ellipsized here: the planner only chooses between one line, two lines, and a smaller size.
 */
internal data class CalendarTitleLayout(
    val primaryLine: String,
    val secondaryLine: String? = null,
    val fontSizeSp: Float = CalendarTitleMetrics.BASE_FONT_SIZE_SP,
) {
    /** True when the year moved to its own line under the month name. */
    val isTwoLine: Boolean get() = secondaryLine != null
}

/**
 * Decides the header title's line count and size from its text and the width it has.
 *
 * Measuring is injected as [CalendarTitlePlanner.plan]'s `measureWidthPx` so this decision is a plain
 * function: the composable passes a `TextMeasurer`, and a test passes a width function and can assert
 * the rules without a device. The rules are, in order:
 *
 *  1. the whole label fits on one line at [CalendarTitleMetrics.BASE_FONT_SIZE_SP];
 *  2. otherwise the month name and the year stack, with the month name at the base size;
 *  3. otherwise the month name shrinks, one sp at a time, until it fits, but never below
 *     [CalendarTitleMetrics.MIN_FONT_SIZE_SP].
 *
 * No month is special-cased: a label is split into month name and year by its trailing numeric token,
 * which is the shape every formatter in the app produces (`November 2026`, `কার্তিক ১৪৩৩`,
 * `Jumada al-Awwal 1448`).
 */
internal object CalendarTitlePlanner {

    fun plan(
        monthLabel: String,
        availableWidthPx: Int,
        measureWidthPx: (text: String, fontSizeSp: Float) -> Int,
    ): CalendarTitleLayout {
        val label = monthLabel.trim()
        if (label.isEmpty()) return CalendarTitleLayout(primaryLine = label)

        // A negative or zero available width means the header has not been measured yet; the base
        // typography is the honest answer, and the next pass lays out against the real width.
        if (availableWidthPx <= 0) return CalendarTitleLayout(primaryLine = label)

        if (measureWidthPx(label, CalendarTitleMetrics.BASE_FONT_SIZE_SP) <= availableWidthPx) {
            return CalendarTitleLayout(primaryLine = label)
        }

        val (monthName, year) = splitMonthAndYear(label) ?: run {
            // Nothing to move onto a second line, so the only recourse is a smaller size.
            return CalendarTitleLayout(
                primaryLine = label,
                fontSizeSp = largestFittingSize(label, availableWidthPx, measureWidthPx),
            )
        }

        return CalendarTitleLayout(
            primaryLine = monthName,
            secondaryLine = year,
            fontSizeSp = largestFittingSize(monthName, availableWidthPx, measureWidthPx),
        )
    }

    /**
     * The label split into the month name and its trailing year, or `null` when the label has no
     * trailing numeric token to move onto the second line.
     */
    private fun splitMonthAndYear(label: String): Pair<String, String>? {
        val separator = label.lastIndexOf(' ')
        if (separator <= 0) return null

        val year = label.substring(separator + 1)
        if (year.isEmpty() || year.none { character -> character.isDigit() }) return null

        val monthName = label.substring(0, separator).trim()
        if (monthName.isEmpty()) return null

        return monthName to year
    }

    /**
     * The largest size, from the base down to the minimum, at which [text] fits [availableWidthPx].
     *
     * The minimum is returned even when the text still does not fit: the caller never ellipsizes the
     * month name, so a width that no month reaches the minimum at is clipped rather than abbreviated.
     */
    private fun largestFittingSize(
        text: String,
        availableWidthPx: Int,
        measureWidthPx: (String, Float) -> Int,
    ): Float {
        var size = CalendarTitleMetrics.BASE_FONT_SIZE_SP
        while (size > CalendarTitleMetrics.MIN_FONT_SIZE_SP &&
            measureWidthPx(text, size) > availableWidthPx
        ) {
            size -= 1f
        }
        return size.coerceAtLeast(CalendarTitleMetrics.MIN_FONT_SIZE_SP)
    }
}

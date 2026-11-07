package io.github.towkirahmedrony.salah.feature.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * How the Calendar header's month title is laid out.
 *
 * The title is the one heading all three calendar modes share, and its twin requirements are that the
 * month name is never abbreviated and that the header's height never depends on the month. Both are
 * decided by [CalendarTitlePlanner] before any drawing happens, so they are asserted here against a
 * width function that behaves like a bold serif: every glyph costs a fraction of an em. The fake makes
 * the cases deliberate rather than dependent on the device's font metrics.
 */
class CalendarTitlePlannerTest {

    /** A typical density, so a width in sp can be handed to the planner in pixels. */
    private val density = 2.75f

    /** Width of one glyph as a fraction of an em: bold serif is wide, and the same for every glyph. */
    private val glyphEm = 0.5f

    /** [widthSp] converted to the pixels the header would really offer. */
    private fun availablePx(widthSp: Float): Int = (widthSp * density).roundToInt()

    /** The measuring function the title would use: a width in pixels for [text] at [fontSizeSp]. */
    private fun measure(text: String, fontSizeSp: Float): Int =
        (text.length * fontSizeSp * density * glyphEm).roundToInt()

    private fun plan(label: String, widthSp: Float) = CalendarTitlePlanner.plan(
        monthLabel = label,
        availableWidthPx = availablePx(widthSp),
        measureWidthPx = ::measure,
    )

    /** The width the header offers a title beside `Today` and the two chevrons on a normal phone. */
    private val narrow = 190f

    @Test
    fun `a month and year that fit stay on one line at the shared base size`() {
        val layout = plan("November 2026", narrow)

        assertEquals("November 2026", layout.primaryLine)
        assertNull(layout.secondaryLine)
        assertFalse(layout.isTwoLine)
        assertEquals(CalendarTitleMetrics.BASE_FONT_SIZE_SP, layout.fontSizeSp, 0f)
    }

    @Test
    fun `a long month name moves the year to a second line instead of being abbreviated`() {
        val layout = plan("Jumada al-Awwal 1448", narrow)

        assertEquals("Jumada al-Awwal", layout.primaryLine)
        assertEquals("1448", layout.secondaryLine)
        assertTrue(layout.isTwoLine)
        // The name itself is intact: nothing dropped, no ellipsis, no line break inside it.
        assertFalse(layout.primaryLine.contains('…'))
        assertFalse(layout.primaryLine.contains('\n'))
        assertFalse(layout.primaryLine.contains("1448"))
    }

    @Test
    fun `the month name shrinks only as far as it must, and fits at the size chosen`() {
        val shrunk = plan("Jumada al-Awwal 1448", narrow)

        assertTrue(
            "the month name was set too small: ${shrunk.fontSizeSp}",
            shrunk.fontSizeSp > CalendarTitleMetrics.MIN_FONT_SIZE_SP,
        )
        assertTrue(
            "the chosen size does not fit the available width",
            measure(shrunk.primaryLine, shrunk.fontSizeSp) <= availablePx(narrow),
        )

        // Without the year the same name still has to lose size rather than being cut.
        val noYear = plan("Jumada al-Awwal", narrow)
        assertEquals("Jumada al-Awwal", noYear.primaryLine)
        assertNull(noYear.secondaryLine)
        assertTrue(
            "the chosen size does not fit the available width",
            measure(noYear.primaryLine, noYear.fontSizeSp) <= availablePx(narrow),
        )
    }

    @Test
    fun `the month name stops at the minimum size and is still whole`() {
        val layout = plan("Jumada al-Awwal 1448", widthSp = 40f)

        assertEquals(CalendarTitleMetrics.MIN_FONT_SIZE_SP, layout.fontSizeSp, 0f)
        assertEquals("Jumada al-Awwal", layout.primaryLine)
        assertEquals("1448", layout.secondaryLine)
    }

    @Test
    fun `a label with no year is never given an empty second line`() {
        val layout = plan("Ramadan", narrow)

        assertEquals("Ramadan", layout.primaryLine)
        assertNull(layout.secondaryLine)
    }

    @Test
    fun `Bangla months keep their Bengali year out of the month name`() {
        // Narrower than any lettered phone, so the stacked title is the layout under test.
        val layout = plan("রমজান ১৪৪৫", widthSp = 80f)
        assertEquals("রমজান", layout.primaryLine)
        assertEquals("১৪৪৫", layout.secondaryLine)

        // Even on a width no phone has, the name is whole and the size is clamped, not truncated.
        val cramped = plan("অগ্রহায়ণ ১৪৩৩", widthSp = 40f)
        assertEquals("অগ্রহায়ণ", cramped.primaryLine)
        assertEquals("১৪৩৩", cramped.secondaryLine)
        assertEquals(CalendarTitleMetrics.MIN_FONT_SIZE_SP, cramped.fontSizeSp, 0f)
    }

    @Test
    fun `every Gregorian month of 2026 keeps its whole month name`() {
        val months = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        months.forEach { month ->
            val label = "$month 2026"
            val layout = plan(label, narrow)
            assertWholeLabel(label, layout)
        }
    }

    @Test
    fun `every Hijri month keeps its whole name including the long ones`() {
        val months = listOf(
            "Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani",
            "Jumada al-Awwal", "Jumada al-Thani", "Rajab", "Sha'ban",
            "Ramadan", "Shawwal", "Dhul Qadah", "Dhul Hijjah",
        )

        months.forEach { month ->
            val label = "$month 1448"
            val layout = plan(label, narrow)
            assertWholeLabel(label, layout)
        }
    }

    @Test
    fun `every Bangla month from Boishakh to Chaitra keeps its whole name`() {
        val months = listOf(
            "বৈশাখ", "জ্যৈষ্ঠ", "আষাঢ়", "শ্রাবণ", "ভাদ্র", "আশ্বিন",
            "কার্তিক", "অগ্রহায়ণ", "পৌষ", "মাঘ", "ফাল্গুন", "চৈত্র",
        )

        months.forEach { month ->
            val label = "$month ১৪৩৩"
            val layout = plan(label, widthSp = 100f)
            assertEquals(month, layout.primaryLine)
            assertEquals("১৪৩৩", layout.secondaryLine)
            assertTrue(layout.fontSizeSp in CalendarTitleMetrics.MIN_FONT_SIZE_SP..CalendarTitleMetrics.BASE_FONT_SIZE_SP)
        }
    }

    @Test
    fun `the title block always reserves a month line, a gap and a year line`() {
        // The grid's Y position is this block's height, so the block must hold both lines at every
        // month and mode: its size is a constant, never what one label happens to measure.
        assertEquals(
            CalendarTitleMetrics.PRIMARY_LINE_HEIGHT_SP +
                CalendarTitleMetrics.LINE_GAP_SP +
                CalendarTitleMetrics.SECONDARY_LINE_HEIGHT_SP,
            CalendarTitleMetrics.BLOCK_HEIGHT_SP,
            0f,
        )
        assertTrue(
            "the block is too short for a stacked title",
            CalendarTitleMetrics.BLOCK_HEIGHT_SP >
                CalendarTitleMetrics.PRIMARY_LINE_HEIGHT_SP + CalendarTitleMetrics.SECONDARY_LINE_HEIGHT_SP,
        )
    }

    /** The one invariant every label must hold: its month name is never shortened or wrapped. */
    private fun assertWholeLabel(label: String, layout: CalendarTitleLayout) {
        val rebuilt = listOfNotNull(layout.primaryLine, layout.secondaryLine).joinToString(" ")
        assertEquals(label, rebuilt)
        assertFalse("the title was abbreviated: ${layout.primaryLine}", layout.primaryLine.contains('…'))
        assertFalse("the title wrapped inside the name: ${layout.primaryLine}", layout.primaryLine.contains('\n'))
        assertTrue(
            "the title was set outside the allowed range: ${layout.fontSizeSp}",
            layout.fontSizeSp in CalendarTitleMetrics.MIN_FONT_SIZE_SP..CalendarTitleMetrics.BASE_FONT_SIZE_SP,
        )
    }
}

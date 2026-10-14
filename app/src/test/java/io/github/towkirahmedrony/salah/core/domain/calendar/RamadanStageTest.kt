package io.github.towkirahmedrony.salah.core.domain.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dividing Ramadan into its three traditional stages.
 *
 * The property that matters is that the division is a *partition of the month's own days*: every day
 * falls in exactly one stage, the stages never interleave (which is what lets the table group
 * consecutive rows and draw one heading per stage), and the last stage runs to the end of the month
 * whether that is day 29 or day 30. The last one is asserted by counting a twenty-nine- and a
 * thirty-day month, because a hardcoded end would be invisible in either month on its own.
 */
class RamadanStageTest {

    @Test
    fun `the stages begin on the traditional days one, eleven and twenty-one`() {
        assertEquals(3, RamadanStage.entries.size)
        assertEquals(
            listOf(1, 11, 21),
            RamadanStage.entries.map { stage -> stage.firstDayNumber },
        )
    }

    @Test
    fun `the first ten days are Rahmah and the middle ten Maghfirah`() {
        assertEquals(
            List(10) { RamadanStage.RAHMAH },
            (1..10).map { day -> RamadanStage.ofDayNumber(day) },
        )
        assertEquals(
            List(10) { RamadanStage.MAGHFIRAH },
            (11..20).map { day -> RamadanStage.ofDayNumber(day) },
        )
        // The boundary days themselves, so an off-by-one is caught where it would happen.
        assertEquals(RamadanStage.RAHMAH, RamadanStage.ofDayNumber(10))
        assertEquals(RamadanStage.MAGHFIRAH, RamadanStage.ofDayNumber(11))
        assertEquals(RamadanStage.MAGHFIRAH, RamadanStage.ofDayNumber(20))
        assertEquals(RamadanStage.NAJAT, RamadanStage.ofDayNumber(21))
    }

    @Test
    fun `the last stage has no end of its own, so it covers a 29 and a 30 day Ramadan`() {
        // The stage begins at 21 and takes whatever the month has left. A stage that ended at a fixed
        // day would either hide the thirtieth day of a long Ramadan or claim a day a short one does not
        // have; this test is what fails if one is ever introduced.
        assertEquals(RamadanStage.NAJAT, RamadanStage.ofDayNumber(21))
        assertEquals(RamadanStage.NAJAT, RamadanStage.ofDayNumber(29))
        assertEquals(RamadanStage.NAJAT, RamadanStage.ofDayNumber(30))

        assertEquals(9, (1..29).count { day -> RamadanStage.ofDayNumber(day) == RamadanStage.NAJAT })
        assertEquals(10, (1..30).count { day -> RamadanStage.ofDayNumber(day) == RamadanStage.NAJAT })
    }

    @Test
    fun `a whole month is divided in day order, with no stage revisited`() {
        val month = (1..30).map { day -> RamadanStage.ofDayNumber(day) }

        // Reading down the days, the stage's first day never goes backwards, so the table can group
        // consecutive rows and the three headings appear in this order.
        assertEquals(month.sortedBy { stage -> stage.firstDayNumber }, month)
        // Each stage is one uninterrupted run of days and no fourth stage appears, which is what makes
        // "one heading per stage" a complete description of the table.
        assertEquals(
            listOf(RamadanStage.RAHMAH, RamadanStage.MAGHFIRAH, RamadanStage.NAJAT),
            month.distinct(),
        )
    }
}

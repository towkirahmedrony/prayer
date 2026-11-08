package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.domain.calendar.BanglaCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarHeadline
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The hero card's date columns as [DeviceClockCalendarRepository] produces them.
 *
 * Expected values are taken from the converters themselves wherever a number depends on them, so these
 * tests pin the *composition* — which part is large, which is small, which script — and cannot drift
 * from the conversion rules the calendar tests already own.
 */
class CalendarHeadlineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    /** Sunday 8 November 2026, 06:30 in Dhaka. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-08T00:30:00Z"), zone)
    private val date: LocalDate = LocalDate.of(2026, 11, 8)

    private val formatting = CalendarFormatting()
    private val bangla = BanglaCalendar()
    private val hijri = HijriCalendar()

    private fun headlines(
        dateLanguage: DateLanguagePreference,
        adjustment: Int = 0,
        clock: Clock = this.clock,
    ): List<CalendarHeadline> = runBlocking {
        DeviceClockCalendarRepository(
            clock = clock,
            settings = flowOf(CalendarSettings(hijriAdjustmentDays = adjustment, dateLanguage = dateLanguage)),
            interfaceLanguage = flowOf(AppLanguage.SYSTEM),
        ).observeToday().first().headlines
    }

    private fun List<CalendarHeadline>.forSystem(system: CalendarSystem): CalendarHeadline? =
        firstOrNull { it.system == system }

    @Test
    fun `the columns come in Gregorian, Bangla, Hijri order`() {
        val result = headlines(DateLanguagePreference.ENGLISH)

        assertEquals(
            listOf(CalendarSystem.GREGORIAN, CalendarSystem.BANGLA, CalendarSystem.HIJRI),
            result.map { it.system },
        )
    }

    @Test
    fun `the English Gregorian column is the short day and month over the weekday and year`() {
        val gregorian = headlines(DateLanguagePreference.ENGLISH).forSystem(CalendarSystem.GREGORIAN)

        assertNotNull(gregorian)
        assertEquals("8 Nov", gregorian!!.primary)
        assertEquals("2026", gregorian.year)
        assertEquals("Sunday", gregorian.weekday)
    }

    @Test
    fun `the Bangla column is the day and month with the year apart, in Bangla script`() {
        val column = headlines(DateLanguagePreference.BANGLA).forSystem(CalendarSystem.BANGLA)

        // 8 November 2026 is 23 Kartik 1433 under the Bangladesh rules.
        assertNotNull(column)
        assertEquals("২৩ কার্তিক", column!!.primary)
        assertEquals("১৪৩৩", column.year)
        assertNull(column.weekday)
    }

    @Test
    fun `the Gregorian column is written in Bangla numerals under the Bangla date language`() {
        val gregorian = headlines(DateLanguagePreference.BANGLA).forSystem(CalendarSystem.GREGORIAN)

        assertNotNull(gregorian)
        assertEquals("২০২৬", gregorian!!.year)
        assertTrue(gregorian.primary.startsWith("৮"))
    }

    @Test
    fun `under Bangla the Hijri month is the Arabic name with Bangla numerals around it`() {
        val converted = checkNotNull(hijri.dateOf(date, 0))

        val column = headlines(DateLanguagePreference.BANGLA).forSystem(CalendarSystem.HIJRI)

        assertNotNull(column)
        val bn = Locale("bn")
        assertEquals(
            "${formatting.numerals(converted.day, bn)} ${converted.month.arabicName}",
            column!!.primary,
        )
        assertEquals(formatting.numerals(converted.year, bn), column.year)
    }

    @Test
    fun `under English the Hijri month is the English name`() {
        val converted = checkNotNull(hijri.dateOf(date, 0))

        val column = headlines(DateLanguagePreference.ENGLISH).forSystem(CalendarSystem.HIJRI)

        assertEquals("${converted.day} ${converted.month.englishName}", column!!.primary)
        assertEquals(converted.year.toString(), column.year)
    }

    @Test
    fun `the Hijri column follows the user's day adjustment`() {
        val unadjusted = checkNotNull(hijri.dateOf(date, 0))
        val earlier = checkNotNull(hijri.dateOf(date, -1))

        val atZero = headlines(DateLanguagePreference.ENGLISH, adjustment = 0).forSystem(CalendarSystem.HIJRI)
        val atMinusOne = headlines(DateLanguagePreference.ENGLISH, adjustment = -1).forSystem(CalendarSystem.HIJRI)

        assertEquals("${unadjusted.day} ${unadjusted.month.englishName}", atZero!!.primary)
        assertEquals("${earlier.day} ${earlier.month.englishName}", atMinusOne!!.primary)
    }

    @Test
    fun `the Bangla column is omitted before the supported range instead of approximated`() {
        val before2019 = Clock.fixed(Instant.parse("2018-11-08T00:30:00Z"), zone)

        val result = headlines(DateLanguagePreference.ENGLISH, clock = before2019)

        assertNull(bangla.convert(LocalDate.of(2018, 11, 8)))
        assertNull(result.forSystem(CalendarSystem.BANGLA))
        assertNotNull(result.forSystem(CalendarSystem.GREGORIAN))
    }
}

package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.BanglaMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HijriDate
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.HijriMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Date rendering.
 *
 * The point of these tests is the rule the prompt states explicitly: a date string must not mix
 * numerals or scripts. Salah composes the Bangla and Hijri strings itself from the converted value plus
 * one numeral table, so `১৪ বৈশাখ ১৪৩১` cannot come out half-localised, and the assertion is on the
 * exact expected string rather than "contains a digit".
 */
class CalendarFormattingTest {

    private val formatting = CalendarFormatting()
    private val bangla = Locale("bn")
    private val arabic = Locale("ar")
    private val english = Locale.ENGLISH

    @Test
    fun `Bangla numerals replace every decimal digit`() {
        assertEquals("১৪৩১", BanglaNumerals.format(1431))
        assertEquals("০", BanglaNumerals.format(0))
        assertEquals("৭", BanglaNumerals.format(7))
        assertEquals("১০", BanglaNumerals.format(10))
        // A sign is preserved rather than dropped, so a negative adjustment cannot read as positive.
        assertEquals("-২", BanglaNumerals.format(-2))
    }

    @Test
    fun `numerals follow the chosen language, not the device`() {
        assertEquals("1431", formatting.numerals(1431, english))
        assertEquals("১৪৩১", formatting.numerals(1431, bangla))
        assertTrue(formatting.isBangla(bangla))
        assertTrue(!formatting.isBangla(english))
    }

    @Test
    fun `a Bangla date is Bangla throughout — month name and numerals together`() {
        val date = BanglaDate(year = 1431, month = BanglaMonth.BOISHAKH, day = 14)
        assertEquals("১৪ বৈশাখ ১৪৩১", formatting.bangla(date, bangla))
        // In Latin script it is Latin throughout; one convention per string in either direction.
        assertEquals("14 Boishakh 1431", formatting.bangla(date, english))
        // Every month has both names, and neither is blank.
        BanglaMonth.entries.forEach { month ->
            assertTrue(month.englishName.isNotBlank())
            assertTrue(month.bengaliName.isNotBlank())
            assertNotEquals(month.englishName, month.bengaliName)
        }
    }

    @Test
    fun `a Hijri date is rendered like a Bangla one and carries the Hijri era`() {
        val date = HijriDate(
            year = 1445,
            month = HijriMonth.RAMADAN,
            day = 1,
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )
        assertEquals("১ রমজান ১৪৪৫", formatting.hijri(date, bangla))
        assertEquals("1 Ramadan 1445", formatting.hijri(date, english))
        // The era marker is Latin in both, because it is an era marker rather than a month name.
        assertEquals("1 Ramadan 1445 AH", formatting.hijriWithEra(date, english))
        assertEquals("১ রমজান ১৪৪৫ AH", formatting.hijriWithEra(date, bangla))
    }

    @Test
    fun `the Gregorian date goes through the platform's formatter for the chosen locale`() {
        val date = LocalDate.of(2024, 4, 14)
        assertEquals("14 April 2024", formatting.gregorian(date, english))
        assertEquals("April 2024", formatting.gregorianMonth(date, english))
        assertEquals("Sunday", formatting.weekday(date, english))
        // The Bangla rendering is localised by the platform as a unit, so it differs from English.
        assertNotEquals(
            formatting.gregorian(date, english),
            formatting.gregorian(date, bangla),
        )
    }

    @Test
    fun `weekday column labels are the locale's own abbreviations`() {
        // java.time's DayOfWeek starts on Monday; the *grid's* first column comes from the locale's
        // WeekFields, which is a different question and is covered in GregorianCalendarGridTest.
        assertEquals("Mon", formatting.weekdayShortLabel(DayOfWeek.MONDAY, english))
        assertEquals("Sun", formatting.weekdayShortLabel(DayOfWeek.SUNDAY, english))
        assertEquals(
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            DayOfWeek.entries.map { day -> formatting.weekdayShortLabel(day, english) },
        )
        // And they are non-empty in Bangla, so a Bangla header row never renders as blanks.
        DayOfWeek.entries.forEach { day ->
            assertTrue(formatting.weekdayShortLabel(day, bangla).isNotBlank())
        }
    }

    @Test
    fun `the stored language preference decides the locale, not the device default`() {
        val device = Locale.FRENCH

        assertEquals(device, formatting.localeFor(DateLanguagePreference.SYSTEM, device))
        assertEquals(bangla.language, formatting.localeFor(DateLanguagePreference.BANGLA, device).language)
        assertEquals("en", formatting.localeFor(DateLanguagePreference.ENGLISH, device).language)
    }

    @Test
    fun `Arabic-Indic numerals replace every decimal digit`() {
        assertEquals("١٤٤٥", ArabicNumerals.format(1445))
        assertEquals("٠", ArabicNumerals.format(0))
        assertEquals("٣٠", ArabicNumerals.format(30))
        // A sign is preserved rather than dropped, so a negative offset cannot read as positive.
        assertEquals("-٢", ArabicNumerals.format(-2))
    }

    @Test
    fun `the Hijri mode renders Arabic month names and Arabic-Indic numerals`() {
        val date = HijriDate(
            year = 1445,
            month = HijriMonth.RAMADAN,
            day = 1,
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )
        // Ramadan in Arabic script, with Arabic-Indic digits for the day and the year.
        assertEquals("١ رَمَضان ١٤٤٥", formatting.hijri(date, arabic))
        assertEquals("رَمَضان ١٤٤٥", formatting.hijriMonth(date, arabic))
        assertEquals("١٤٤٥", formatting.numerals(1445, arabic))
        assertTrue(formatting.isArabic(arabic))
        assertTrue(!formatting.isArabic(english))

        // Every Hijri month carries an Arabic name, and it is not a transliteration of the Latin one.
        HijriMonth.entries.forEach { month ->
            assertTrue(month.arabicName.isNotBlank())
            assertNotEquals(month.englishName, month.arabicName)
            assertTrue(
                "${month.name} should be rendered in Arabic script",
                month.arabicName.any { character -> character.code in ARABIC_SCRIPT },
            )
        }
    }

    @Test
    fun `the grid locale follows the selected calendar system, not the device`() {
        // The Gregorian grid follows the stored date language...
        assertEquals(english, formatting.gridLocale(CalendarSystem.GREGORIAN, DateLanguagePreference.ENGLISH))
        assertEquals(bangla.language, formatting.gridLocale(CalendarSystem.GREGORIAN, DateLanguagePreference.BANGLA).language)
        // ...while the Bangla and Hijri grids carry their own script whatever the date language is.
        assertEquals(bangla.language, formatting.gridLocale(CalendarSystem.BANGLA, DateLanguagePreference.ENGLISH).language)
        assertEquals(arabic.language, formatting.gridLocale(CalendarSystem.HIJRI, DateLanguagePreference.ENGLISH).language)
        assertEquals(arabic.language, formatting.gridLocale(CalendarSystem.HIJRI, DateLanguagePreference.BANGLA).language)
    }

    @Test
    fun `a Bangla month heading is the Bangla month and year, not the Gregorian one`() {
        // 1 Ashwin 1431 is 16 September 2024; the heading names the Bangla month, not September.
        val date = BanglaDate(year = 1431, month = BanglaMonth.ASHWIN, day = 1)
        assertEquals("আশ্বিন ১৪৩১", formatting.banglaMonth(date, bangla))
        assertEquals("Ashwin 1431", formatting.banglaMonth(date, english))
    }

    @Test
    fun `a Gregorian month label localises the month name, not only the digits`() {
        // August 2026 must not render as a number-to-string of the month index.
        val label = formatting.gregorianMonth(LocalDate.of(2026, 8, 1), english)
        assertEquals("August 2026", label)
    }

    private companion object {
        /** Unicode block for Arabic, so an Arabic name can be asserted to be in Arabic script. */
        val ARABIC_SCRIPT = 0x0600..0x06FF
    }
}

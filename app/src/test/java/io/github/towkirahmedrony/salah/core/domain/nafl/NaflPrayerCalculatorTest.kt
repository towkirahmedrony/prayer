package io.github.towkirahmedrony.salah.core.domain.nafl

import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The nafl arithmetic, pinned to one real day: Dhaka, 8 November 2026.
 *
 * Anchors were calculated independently of the app (NOAA): sunrise 06:08, solar transit 11:42,
 * sunset 17:16. The displayed prayer times are the ones the app showed that day with the user's manual
 * corrections (Dhuhr 11:46, Maghrib 17:20). The windows are written out by hand (sunrise 20 min, zenith
 * 5 min before to Dhuhr, sunset 20 min), so the calculator is checked against the rules, not itself.
 */
class NaflPrayerCalculatorTest {

    private val calculator = NaflPrayerCalculator()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 11, 8)

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.76, 90.40, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun tomorrowAt(hour: Int, minute: Int): ZonedDateTime =
        date.plusDays(1).atTime(hour, minute).atZone(zone)

    private fun schedule(
        dhuhr: ZonedDateTime = at(11, 46),
        isha: ZonedDateTime = at(18, 33),
    ): PrayerSchedule = PrayerSchedule(
        date = date,
        location = location,
        profile = PrayerSettings.DEFAULT.toProfile(),
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(4, 51)),
            PrayerTimeEntry(PrayerName.DHUHR, dhuhr),
            PrayerTimeEntry(PrayerName.ASR, at(15, 40)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 20)),
            PrayerTimeEntry(PrayerName.ISHA, isha),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, at(6, 8)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, at(11, 42)),
            SolarEventEntry(SolarEvent.SUNSET, at(17, 16)),
        ),
        generatedAt = Instant.parse("2026-11-08T00:00:00Z"),
    )

    private fun window(
        kind: ProhibitedWindowKind,
        start: ZonedDateTime,
        end: ZonedDateTime,
    ) = ProhibitedPeriod(
        startsAt = start,
        endsAt = end,
        ruleSetId = "test",
        kind = kind,
        explanation = "test window",
    )

    private val windows: List<ProhibitedPeriod> = listOf(
        window(ProhibitedWindowKind.SUNRISE_ASCENT, at(6, 8), at(6, 28)),
        window(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 37), at(11, 46)),
        window(ProhibitedWindowKind.SUNSET_SETTING, at(16, 56), at(17, 16)),
    )

    // Maghrib 17:20 to Fajr 04:52 is 11h32m; a third is 3h50m40s, so the last third begins 01:01:20.
    private val lastThird = tomorrowAt(1, 1).plusSeconds(20)
    private val fajrTomorrow = tomorrowAt(4, 52)

    private fun dayOf(
        schedule: PrayerSchedule? = schedule(),
        prohibited: List<ProhibitedPeriod> = windows,
        ishaStartsAt: ZonedDateTime? = at(18, 33),
    ): NaflDay = calculator.calculate(
        schedule = schedule,
        prohibited = prohibited,
        ishaStartsAt = ishaStartsAt,
        lastThirdStartsAt = lastThird,
        fajrEndsAt = fajrTomorrow,
    )

    private val day: NaflDay = dayOf()

    @Test
    fun `Duha runs from the end of the sunrise window to the start of the zenith window`() {
        val duha = checkNotNull(day.duha)

        assertEquals(NaflKind.DUHA, duha.kind)
        assertEquals(at(6, 28), duha.startsAt)
        assertEquals(at(11, 37), duha.endsAt)
        assertEquals(Duration.ofMinutes(309), duha.duration)
    }

    @Test
    fun `Duha never overlaps a prohibited window`() {
        val duha = checkNotNull(day.duha)

        windows.forEach { window ->
            assertTrue(
                "Duha overlaps ${window.kind}",
                !duha.startsAt.isBefore(window.endsAt) || !duha.endsAt.isAfter(window.startsAt),
            )
        }
    }

    @Test
    fun `the preferred part of Duha starts a quarter of the day after sunrise`() {
        // Sunrise 06:08, sunset 17:16: the day is 11h08m, a quarter of it is 2h47m.
        assertEquals(at(8, 55), day.duhaPreferredFrom)
    }

    @Test
    fun `Duha ends at Dhuhr when Dhuhr is earlier than the zenith window opens`() {
        val duha = checkNotNull(dayOf(schedule = schedule(dhuhr = at(11, 30))).duha)

        assertEquals(at(11, 30), duha.endsAt)
    }

    @Test
    fun `there is no Duha without both the sunrise and the zenith windows`() {
        assertNull(dayOf(prohibited = emptyList()).duha)
        assertNull(dayOf(prohibited = windows.filter { it.kind != ProhibitedWindowKind.ZENITH_DECLINE }).duha)
    }

    @Test
    fun `there is no Duha when the sunrise window ends after the zenith window opens`() {
        val squeezed = listOf(
            window(ProhibitedWindowKind.SUNRISE_ASCENT, at(6, 8), at(12, 0)),
            window(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 37), at(11, 46)),
        )

        assertNull(dayOf(prohibited = squeezed).duha)
    }

    @Test
    fun `Awwabin runs from Maghrib to Isha as displayed`() {
        val awwabin = checkNotNull(day.awwabin)

        assertEquals(NaflKind.AWWABIN, awwabin.kind)
        assertEquals(at(17, 20), awwabin.startsAt)
        assertEquals(at(18, 33), awwabin.endsAt)
    }

    @Test
    fun `there is no Awwabin when Isha is not after Maghrib`() {
        assertNull(dayOf(schedule = schedule(isha = at(17, 0))).awwabin)
    }

    @Test
    fun `Tahajjud runs after Isha to Fajr and the last third is kept as the recommended start`() {
        val tahajjud = checkNotNull(day.tahajjud)

        assertEquals(NaflKind.TAHAJJUD, tahajjud.kind)
        assertEquals(at(18, 33), tahajjud.startsAt)
        assertEquals(fajrTomorrow, tahajjud.endsAt)
        assertEquals(lastThird, day.lastThirdStartsAt)
        // The recommended window sits inside the valid window, across midnight.
        assertTrue(day.lastThirdStartsAt!!.isAfter(tahajjud.startsAt))
        assertTrue(day.lastThirdStartsAt!!.isBefore(tahajjud.endsAt))
        assertTrue(tahajjud.contains(at(20, 0)))
        assertTrue(tahajjud.contains(tomorrowAt(1, 30)))
        assertTrue(tahajjud.contains(tomorrowAt(4, 0)))
        assertFalse(tahajjud.contains(at(17, 0)))
        assertFalse(tahajjud.contains(tomorrowAt(4, 52)))
    }

    @Test
    fun `without Isha or Fajr there is no Tahajjud but the recommended start is still kept`() {
        val withoutNight = calculator.calculate(
            schedule = schedule(),
            prohibited = windows,
            ishaStartsAt = null,
            lastThirdStartsAt = lastThird,
            fajrEndsAt = null,
        )

        assertNull(withoutNight.tahajjud)
        assertEquals(lastThird, withoutNight.lastThirdStartsAt)
        assertEquals(day.duha, withoutNight.duha)
    }

    @Test
    fun `Tahajjud is absent when Fajr is not after Isha`() {
        assertNull(dayOf(ishaStartsAt = tomorrowAt(5, 0)).tahajjud)
    }

    @Test
    fun `without a schedule nothing is stated`() {
        assertEquals(NaflDay.Empty, dayOf(schedule = null))
    }

    @Test
    fun `the sunrise window is reported as prohibited with the time until it closes`() {
        val moment = calculator.momentAt(day, windows, at(6, 10))

        assertTrue(moment is NaflMoment.Prohibited)
        moment as NaflMoment.Prohibited
        assertEquals(ProhibitedWindowKind.SUNRISE_ASCENT, moment.period.kind)
        assertEquals(Duration.ofMinutes(18), moment.remaining)
    }

    @Test
    fun `Duha opens exactly when the sunrise window closes`() {
        val moment = calculator.momentAt(day, windows, at(6, 28))

        assertTrue(moment is NaflMoment.DuhaOpen)
        assertEquals(Duration.ofMinutes(309), moment!!.remaining)
    }

    @Test
    fun `the last second of Duha still counts down, then the zenith window takes over`() {
        val lastSecond = calculator.momentAt(day, windows, at(11, 36, 59))
        assertTrue(lastSecond is NaflMoment.DuhaOpen)
        assertEquals(Duration.ofSeconds(1), lastSecond!!.remaining)

        val zenith = calculator.momentAt(day, windows, at(11, 37))
        assertTrue(zenith is NaflMoment.Prohibited)
        zenith as NaflMoment.Prohibited
        assertEquals(ProhibitedWindowKind.ZENITH_DECLINE, zenith.period.kind)
        assertEquals(Duration.ofMinutes(9), zenith.remaining)
    }

    @Test
    fun `at Dhuhr nothing non-fard is open`() {
        assertNull(calculator.momentAt(day, windows, at(11, 46)))
    }

    @Test
    fun `the sunset window ends at sunset, not at the corrected Maghrib`() {
        val inside = calculator.momentAt(day, windows, at(16, 59))
        assertTrue(inside is NaflMoment.Prohibited)
        assertEquals(Duration.ofMinutes(17), inside!!.remaining)

        // 17:16 is sunset: the prohibition is over, while Asr is still the displayed prayer until 17:20.
        assertNull(calculator.momentAt(day, windows, at(17, 16)))
        assertNull(calculator.momentAt(day, windows, at(17, 18)))
    }

    @Test
    fun `no non-fard reading during Fajr, the afternoon or the night`() {
        assertNull(calculator.momentAt(day, windows, at(5, 0)))
        assertNull(calculator.momentAt(day, windows, at(14, 0)))
        assertNull(calculator.momentAt(day, windows, at(20, 0)))
    }

    @Test
    fun `a prohibited window wins over Duha if the two were ever supplied overlapping`() {
        val overlapping = windows + window(ProhibitedWindowKind.ZENITH_DECLINE, at(9, 0), at(9, 30))

        val moment = calculator.momentAt(day, overlapping, at(9, 10))

        assertTrue(moment is NaflMoment.Prohibited)
        assertEquals(Duration.ofMinutes(20), moment!!.remaining)
    }
}

package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.testing.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The fasting countdown.
 *
 * The rules these tests pin are the ones the screen depends on: the countdown is only ever shown inside
 * Ramadan, it counts to the calculated **Fajr** before Sehri ends and to the calculated **Maghrib**
 * afterwards, it moves to the next day's Fajr after Iftar, and after Iftar on the **last day** — when the
 * next day is Shawwal — it disappears rather than counting down to a fast that is not there.
 *
 * Time is fixed and the schedules are synthetic, so nothing here waits on a real clock or a real prayer
 * engine.
 */
class FastingCountdownTest {

    private val resolver = FastingCountdownResolver()
    private val zone = ZoneId.of("Asia/Dhaka")

    // 11 March 2024 is 1 Ramadan 1445 in the platform's Umm al-Qura table.
    private val firstDay: LocalDate = LocalDate.parse("2024-03-11")
    private val lastDay: LocalDate = LocalDate.parse("2024-04-09")

    private val window = RamadanWindow(
        hijriYear = 1445,
        start = firstDay,
        end = lastDay,
        source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
    )

    private fun instant(date: LocalDate, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant()

    private fun schedule(date: LocalDate, fajr: LocalTime = LocalTime.of(4, 47), maghrib: LocalTime = LocalTime.of(18, 12)) =
        testSchedule(date, zone = zone, fajr = fajr, maghrib = maghrib)

    @Test
    fun `before Sehri ends the countdown is to the calculated Fajr`() {
        val countdown = resolver.resolve(
            today = firstDay,
            now = instant(firstDay, 3, 0),
            window = window,
            todaySchedule = schedule(firstDay),
            tomorrowSchedule = schedule(firstDay.plusDays(1)),
        )!!

        assertEquals(FastingEventKind.SEHRI_END, countdown.kind)
        assertEquals(ZonedDateTime.of(firstDay, LocalTime.of(4, 47), zone), countdown.eventAt)
        assertEquals(Duration.ofHours(1).plusMinutes(47), countdown.remaining)
    }

    @Test
    fun `after Sehri ends and before Iftar the countdown is to the calculated Maghrib`() {
        val countdown = resolver.resolve(
            today = firstDay,
            now = instant(firstDay, 12, 0),
            window = window,
            todaySchedule = schedule(firstDay),
            tomorrowSchedule = schedule(firstDay.plusDays(1)),
        )!!

        assertEquals(FastingEventKind.IFTAR, countdown.kind)
        assertEquals(ZonedDateTime.of(firstDay, LocalTime.of(18, 12), zone), countdown.eventAt)
        assertEquals(Duration.ofHours(6).plusMinutes(12), countdown.remaining)
    }

    @Test
    fun `after Iftar the countdown moves to the next day's Sehri`() {
        val tomorrow = firstDay.plusDays(1)
        val countdown = resolver.resolve(
            today = firstDay,
            now = instant(firstDay, 19, 0),
            window = window,
            todaySchedule = schedule(firstDay),
            tomorrowSchedule = schedule(tomorrow, fajr = LocalTime.of(4, 46)),
        )!!

        assertEquals(FastingEventKind.SEHRI_END, countdown.kind)
        assertEquals(ZonedDateTime.of(tomorrow, LocalTime.of(4, 46), zone), countdown.eventAt)
    }

    @Test
    fun `after Iftar on the last day there is no countdown, because the next day is Shawwal`() {
        val countdown = resolver.resolve(
            today = lastDay,
            now = instant(lastDay, 19, 0),
            window = window,
            todaySchedule = schedule(lastDay),
            tomorrowSchedule = schedule(lastDay.plusDays(1)),
        )

        assertNull(countdown)
    }

    @Test
    fun `outside Ramadan there is no fasting countdown`() {
        val outside = LocalDate.parse("2024-05-01")
        assertNull(
            resolver.resolve(
                today = outside,
                now = instant(outside, 12, 0),
                window = window,
                todaySchedule = schedule(outside),
                tomorrowSchedule = schedule(outside.plusDays(1)),
            ),
        )
    }

    @Test
    fun `with no window there is no countdown`() {
        assertNull(
            resolver.resolve(
                today = firstDay,
                now = instant(firstDay, 3, 0),
                window = null,
                todaySchedule = schedule(firstDay),
                tomorrowSchedule = schedule(firstDay.plusDays(1)),
            ),
        )
    }

    @Test
    fun `a day with no schedule has no countdown`() {
        assertNull(
            resolver.resolve(
                today = firstDay,
                now = instant(firstDay, 3, 0),
                window = window,
                todaySchedule = null,
                tomorrowSchedule = schedule(firstDay.plusDays(1)),
            ),
        )
    }

    @Test
    fun `a missing Fajr still allows a countdown to Iftar`() {
        val noFajr = schedule(firstDay).let { schedule ->
            schedule.copy(prayers = schedule.prayers.filterNot { it.prayer == io.github.towkirahmedrony.salah.core.model.PrayerName.FAJR })
        }
        val countdown = resolver.resolve(
            today = firstDay,
            now = instant(firstDay, 12, 0),
            window = window,
            todaySchedule = noFajr,
            tomorrowSchedule = null,
        )!!

        assertEquals(FastingEventKind.IFTAR, countdown.kind)
    }

    @Test
    fun `after Iftar with no schedule for tomorrow there is no countdown`() {
        assertNull(
            resolver.resolve(
                today = firstDay,
                now = instant(firstDay, 19, 0),
                window = window,
                todaySchedule = schedule(firstDay),
                tomorrowSchedule = null,
            ),
        )
    }
}

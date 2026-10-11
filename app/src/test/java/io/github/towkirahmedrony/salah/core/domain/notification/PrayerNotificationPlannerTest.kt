package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.testing.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The planner is where "what should be scheduled" is decided, so this is the most important test
 * class in the notification subsystem. Every claim the feature makes about not duplicating, not
 * inventing and not scheduling the past is asserted here.
 */
class PrayerNotificationPlannerTest {

    private val zone = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 5, 20)
    private val planner = PrayerNotificationPlanner()

    private fun at(time: LocalTime): ZonedDateTime = ZonedDateTime.of(date, time, zone)

    private fun settings(
        masterEnabled: Boolean = true,
        prayers: Map<PrayerName, PrayerNotificationPreference> = emptyMap(),
        sunrise: Boolean = false,
    ) = PrayerNotificationSettings(
        masterEnabled = masterEnabled,
        prayers = prayers,
        sunriseReminderEnabled = sunrise,
    )

    // --- Window ---------------------------------------------------------------

    @Test
    fun `the window starts today and spans two days`() {
        assertEquals(listOf(date, date.plusDays(1)), planner.windowDates(date))
        assertEquals(2, PrayerNotificationPlanner.WINDOW_DAYS)
    }

    @Test
    fun `the cancellation set covers every target and kind for a date`() {
        val slots = planner.windowSlots(listOf(date))

        assertEquals(ReminderTarget.entries.size * PrayerReminderKind.entries.size, slots.size)
        assertEquals(slots.size, slots.toSet().size)
        assertTrue(slots.any { it.target == ReminderTarget.SUNRISE })
    }

    // --- Enabled and disabled -------------------------------------------------

    @Test
    fun `enabled prayers are scheduled at their calculated time`() {
        val planned = planner.plan(
            settings = settings(),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        assertEquals(PrayerName.entries.size, planned.size)
        planned.forEach { assertTrue(it.isAtEventTime) }
        assertEquals(
            at(LocalTime.of(5, 0)),
            planned.first { it.slot.target == ReminderTarget.FAJR }.triggerAt,
        )
        assertEquals(0, planned.first { it.slot.target == ReminderTarget.FAJR }.advanceMinutes)
    }

    @Test
    fun `disabled prayers are not scheduled at all`() {
        val planned = planner.plan(
            settings = settings(
                prayers = mapOf(
                    PrayerName.FAJR to PrayerNotificationPreference(enabled = false),
                    PrayerName.ISHA to PrayerNotificationPreference(enabled = false),
                ),
            ),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        val targets = planned.map { it.slot.target }
        assertFalse(targets.contains(ReminderTarget.FAJR))
        assertFalse(targets.contains(ReminderTarget.ISHA))
        assertEquals(3, planned.size)
    }

    @Test
    fun `the master switch suppresses every reminder`() {
        val planned = planner.plan(
            settings = settings(masterEnabled = false, sunrise = true),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        assertTrue(planned.isEmpty())
    }

    // --- Past events ----------------------------------------------------------

    @Test
    fun `past prayers are not scheduled as future events`() {
        val planned = planner.plan(
            settings = settings(),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(16, 0)),
        )

        val targets = planned.map { it.slot.target }
        assertEquals(listOf(ReminderTarget.MAGHRIB, ReminderTarget.ISHA), targets)
    }

    @Test
    fun `a prayer exactly at now counts as started and is not scheduled`() {
        val planned = planner.plan(
            settings = settings(),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(5, 0)),
        )

        assertFalse(planned.map { it.slot.target }.contains(ReminderTarget.FAJR))
    }

    // --- Advance reminders ----------------------------------------------------

    @Test
    fun `an advance reminder runs before the prayer and carries its lead time`() {
        val planned = planner.plan(
            settings = settings(
                prayers = mapOf(PrayerName.DHUHR to PrayerNotificationPreference(advanceMinutes = 15)),
            ),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        val advance = planned.first { it.slot.kind == PrayerReminderKind.ADVANCE }
        assertEquals(ReminderTarget.DHUHR, advance.slot.target)
        assertEquals(at(LocalTime.of(11, 45)), advance.triggerAt)
        assertEquals(at(LocalTime.of(12, 0)), advance.eventAt)
        assertEquals(15, advance.advanceMinutes)
        assertFalse(advance.isAtEventTime)
    }

    @Test
    fun `an advance reminder that has already passed is dropped`() {
        val planned = planner.plan(
            settings = settings(
                prayers = mapOf(PrayerName.DHUHR to PrayerNotificationPreference(advanceMinutes = 30)),
            ),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(11, 45)),
        )

        val dhuhr = planned.filter { it.slot.target == ReminderTarget.DHUHR }
        assertEquals(listOf(PrayerReminderKind.AT_TIME), dhuhr.map { it.slot.kind })
    }

    @Test
    fun `a zero advance value produces no advance reminder`() {
        val planned = planner.plan(
            settings = settings(prayers = mapOf(PrayerName.ASR to PrayerNotificationPreference(advanceMinutes = 0))),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        assertTrue(planned.none { it.slot.kind == PrayerReminderKind.ADVANCE })
    }

    // --- Solar events ---------------------------------------------------------

    @Test
    fun `sunrise is never scheduled unless its own switch is on`() {
        val off = planner.plan(
            settings = settings(),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )
        val on = planner.plan(
            settings = settings(sunrise = true),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        assertTrue(off.none { it.slot.target == ReminderTarget.SUNRISE })
        val sunrise = on.single { it.slot.target == ReminderTarget.SUNRISE }
        assertEquals(at(LocalTime.of(6, 0)), sunrise.triggerAt)
        assertFalse(sunrise.slot.target.isObligatoryPrayer)
    }

    @Test
    fun `sunrise never gets an advance reminder`() {
        val planned = planner.plan(
            settings = settings(sunrise = true),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        assertTrue(planned.none { it.slot.target == ReminderTarget.SUNRISE && !it.isAtEventTime })
    }

    // --- Missing data ---------------------------------------------------------

    @Test
    fun `no schedule means no reminders rather than invented ones`() {
        val planned = planner.plan(
            settings = settings(sunrise = true),
            schedules = emptyList(),
            now = at(LocalTime.of(0, 30)),
        )

        assertTrue(planned.isEmpty())
    }

    @Test
    fun `an incomplete schedule is refused`() {
        val incomplete = testSchedule(date, zone).copy(prayers = emptyList())

        val planned = planner.plan(
            settings = settings(),
            schedules = listOf(incomplete),
            now = at(LocalTime.of(0, 30)),
        )

        assertTrue(planned.isEmpty())
    }

    // --- Duplicates and determinism -------------------------------------------

    @Test
    fun `overlapping schedules cannot produce a duplicated slot`() {
        val schedule = testSchedule(date, zone)

        val planned = planner.plan(
            settings = settings(),
            schedules = listOf(schedule, schedule, schedule),
            now = at(LocalTime.of(0, 30)),
        )

        assertEquals(planned.size, planned.map { it.slot }.toSet().size)
        assertEquals(PrayerName.entries.size, planned.size)
    }

    @Test
    fun `planning is ordered by trigger instant`() {
        val planned = planner.plan(
            settings = settings(
                prayers = mapOf(PrayerName.ISHA to PrayerNotificationPreference(advanceMinutes = 20)),
                sunrise = true,
            ),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        val instants = planned.map { it.triggerAt.toInstant() }
        assertEquals(instants.sorted(), instants)
    }

    @Test
    fun `planning twice with the same inputs is identical`() {
        val schedule = testSchedule(date, zone)
        val config = settings(prayers = mapOf(PrayerName.FAJR to PrayerNotificationPreference(advanceMinutes = 10)))

        val first = planner.plan(config, listOf(schedule), at(LocalTime.of(0, 30)))
        val second = planner.plan(config, listOf(schedule), at(LocalTime.of(0, 30)))

        assertEquals(first, second)
    }

    @Test
    fun `two days of schedules produce slots dated per schedule`() {
        val tomorrow = date.plusDays(1)
        val planned = planner.plan(
            settings = settings(),
            schedules = listOf(testSchedule(date, zone), testSchedule(tomorrow, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        assertEquals(PrayerName.entries.size * 2, planned.size)
        assertEquals(setOf(date, tomorrow), planned.map { it.slot.date }.toSet())
    }

    // --- Announcement preferences ---------------------------------------------

    @Test
    fun `the chosen sound and vibration travel with the reminder`() {
        val planned = planner.plan(
            settings = settings(
                prayers = mapOf(
                    PrayerName.MAGHRIB to PrayerNotificationPreference(
                        sound = NotificationSound.SILENT,
                        vibration = false,
                    ),
                ),
            ),
            schedules = listOf(testSchedule(date, zone)),
            now = at(LocalTime.of(0, 30)),
        )

        val maghrib = planned.single { it.slot.target == ReminderTarget.MAGHRIB }
        assertEquals(NotificationSound.SILENT, maghrib.sound)
        assertFalse(maghrib.vibration)

        // A silent choice still produces a reminder: silent is not "no reminder".
        assertTrue(maghrib.isAtEventTime)
    }

    // --- Obsolete slots -------------------------------------------------------

    @Test
    fun `obsolete slots are exactly the window minus the plan`() {
        val config = settings(prayers = mapOf(PrayerName.FAJR to PrayerNotificationPreference(enabled = false)))
        val planned = planner.plan(config, listOf(testSchedule(date, zone)), at(LocalTime.of(0, 30)))

        val obsolete = planner.obsoleteSlots(listOf(date), planned)

        assertEquals(planner.windowSlots(listOf(date)).toSet(), (obsolete + planned.map { it.slot }).toSet())
        assertTrue(obsolete.contains(PrayerAlarmSlot(date, ReminderTarget.FAJR, PrayerReminderKind.AT_TIME)))
        assertTrue(obsolete.none { it in planned.map { p -> p.slot } })
    }
}

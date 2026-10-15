package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.testing.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sehri and Iftar reminders.
 *
 * The requirement these tests exist for is that the Ramadan reminders are *not* a second scheduling
 * system: they are planned by the same [PrayerNotificationPlanner], they occupy stable and unique alarm
 * slots, they are cancelled by the same derived cancellation as everything else, and they only ever
 * exist for days the calendar places inside Ramadan. The dates are therefore an *input* here, which is
 * what makes a change to the Hijri offset or the end of Ramadan produce a different plan rather than a
 * stale alarm.
 *
 * The planner deliberately produces no reminder for a time that has already passed and no reminder for a
 * lead time of zero, so Sehri is always a lead-in (an at-time Sehri reminder would land on the Fajr
 * prayer reminder) and Iftar's lead-in is optional.
 */
class RamadanReminderPlanTest {

    private val planner = PrayerNotificationPlanner()
    private val zone = ZoneId.of("Asia/Dhaka")
    private val date = LocalDate.parse("2024-03-11")

    /** 23:00 the previous night, so every time on [date] is still in the future. */
    private val now: ZonedDateTime = ZonedDateTime.of(date.minusDays(1), LocalTime.of(23, 0), zone)

    private fun schedule(fajr: LocalTime = LocalTime.of(4, 47), maghrib: LocalTime = LocalTime.of(18, 12)) =
        testSchedule(date, zone = zone, fajr = fajr, maghrib = maghrib)

    private fun plan(
        settings: PrayerNotificationSettings,
        ramadanDates: Set<LocalDate> = setOf(date),
        scheduleDates: List<LocalDate> = listOf(date),
        imsakOffsetMinutes: Int? = null,
    ) = planner.plan(
        settings = settings,
        schedules = scheduleDates.map { scheduleFor(it) },
        now = now,
        ramadanDates = ramadanDates,
        imsakOffsetMinutes = imsakOffsetMinutes,
    )

    private fun scheduleFor(target: LocalDate) =
        testSchedule(target, zone = zone, fajr = LocalTime.of(4, 47), maghrib = LocalTime.of(18, 12))

    /** Prayer reminders off, so only the Ramadan reminders can appear. */
    private fun ramadanOnly(
        sehri: Boolean = true,
        sehriLead: Int = 10,
        iftar: Boolean = true,
        iftarLead: Int = 0,
        imsak: Boolean = false,
    ): PrayerNotificationSettings = PrayerNotificationSettings.DEFAULT
        .let { base ->
            PrayerName.entries.fold(base) { settings, prayer ->
                settings.withPreference(
                    prayer,
                    settings.preferenceFor(prayer).copy(enabled = false),
                )
            }
        }
        .withSehriReminder(enabled = sehri, leadMinutes = sehriLead)
        .withIftarReminder(enabled = iftar, leadMinutes = iftarLead)
        .withImsakReminder(enabled = imsak)

    private fun List<PlannedPrayerNotification>.ramadan() =
        filter { it.slot.target.isRamadanReminder }

    @Test
    fun `Sehri is planned as a lead-in to the calculated Fajr`() {
        val planned = plan(ramadanOnly(sehriLead = 15)).ramadan()

        val sehri = planned.single { it.slot.target == ReminderTarget.SEHRI }
        assertEquals(PrayerReminderKind.ADVANCE, sehri.slot.kind)
        assertEquals(15, sehri.advanceMinutes)
        assertFalse(sehri.isAtEventTime)
        // Fajr for this schedule is 04:47, so a 15-minute lead-in runs at 04:32.
        assertEquals(ZonedDateTime.of(date, LocalTime.of(4, 47), zone), sehri.eventAt)
        assertEquals(ZonedDateTime.of(date, LocalTime.of(4, 32), zone), sehri.triggerAt)
        assertEquals(date, sehri.slot.date)
    }

    @Test
    fun `an at-time Sehri reminder is never planned, because Fajr already has one`() {
        val planned = plan(ramadanOnly(sehriLead = 10)).ramadan()

        assertEquals(
            listOf(PrayerReminderKind.ADVANCE),
            planned.filter { it.slot.target == ReminderTarget.SEHRI }.map { it.slot.kind },
        )
    }

    @Test
    fun `Iftar is planned at the calculated Maghrib`() {
        val planned = plan(ramadanOnly(iftarLead = 0)).ramadan()

        val iftar = planned.single { it.slot.target == ReminderTarget.IFTAR }
        assertEquals(PrayerReminderKind.AT_TIME, iftar.slot.kind)
        assertTrue(iftar.isAtEventTime)
        assertEquals(0, iftar.advanceMinutes)
        // Maghrib for this schedule is 18:12, and Iftar is that time.
        assertEquals(ZonedDateTime.of(date, LocalTime.of(18, 12), zone), iftar.triggerAt)
        assertEquals(iftar.eventAt, iftar.triggerAt)
    }

    @Test
    fun `an Iftar lead-in is added only when the user asks for one`() {
        val withLead = plan(ramadanOnly(iftarLead = 20)).ramadan()
            .filter { it.slot.target == ReminderTarget.IFTAR }
        val withoutLead = plan(ramadanOnly(iftarLead = 0)).ramadan()
            .filter { it.slot.target == ReminderTarget.IFTAR }

        // Ordered by when they run, so the lead-in comes first and Iftar itself second.
        assertEquals(
            listOf(PrayerReminderKind.ADVANCE, PrayerReminderKind.AT_TIME),
            withLead.map { it.slot.kind },
        )
        assertEquals(
            ZonedDateTime.of(date, LocalTime.of(17, 52), zone),
            withLead.single { it.slot.kind == PrayerReminderKind.ADVANCE }.triggerAt,
        )
        assertEquals(listOf(PrayerReminderKind.AT_TIME), withoutLead.map { it.slot.kind })
    }

    @Test
    fun `no Ramadan reminder is planned outside Ramadan`() {
        // The planner does not decide when Ramadan is: an empty date set is "not Ramadan", and it
        // produces nothing rather than inventing a reminder.
        assertTrue(plan(ramadanOnly(), ramadanDates = emptySet()).ramadan().isEmpty())
        // And a schedule for another day in the same window is not that day's reminder.
        assertTrue(plan(ramadanOnly(), ramadanDates = setOf(date.plusDays(1))).ramadan().isEmpty())
    }

    @Test
    fun `a Ramadan date with no schedule produces no reminder`() {
        // The schedule is missing, so there is no Fajr or Maghrib to remind of.
        assertTrue(plan(ramadanOnly(), scheduleDates = emptyList()).ramadan().isEmpty())
    }

    @Test
    fun `switching a Ramadan reminder off removes its slots`() {
        val both = plan(ramadanOnly()).ramadan()
        val sehriOnly = plan(ramadanOnly(iftar = false)).ramadan()
        val iftarOnly = plan(ramadanOnly(sehri = false)).ramadan()
        val neither = plan(ramadanOnly(sehri = false, iftar = false)).ramadan()

        assertEquals(
            setOf(ReminderTarget.SEHRI, ReminderTarget.IFTAR),
            both.map { it.slot.target }.toSet(),
        )
        assertEquals(listOf(ReminderTarget.SEHRI), sehriOnly.map { it.slot.target })
        assertEquals(listOf(ReminderTarget.IFTAR), iftarOnly.map { it.slot.target })
        assertTrue(neither.isEmpty())
    }

    @Test
    fun `the master switch suppresses the Ramadan reminders too`() {
        val settings = ramadanOnly().copy(masterEnabled = false)
        assertTrue(plan(settings).ramadan().isEmpty())
    }

    @Test
    fun `every Ramadan slot is unique within a plan, so nothing is scheduled twice`() {
        val plan = plan(ramadanOnly(sehri = true, iftar = true, iftarLead = 10)).ramadan()

        val identities = plan.map { it.slot.identity }
        assertEquals(identities.size, identities.toSet().size)
        val requestCodes = plan.map { PrayerNotificationIds.requestCode(it.slot) }
        assertEquals(requestCodes.size, requestCodes.toSet().size)
    }

    @Test
    fun `a Ramadan slot and a prayer slot on the same day cannot collide`() {
        // The alarm identity scheme gives every (target, kind) pair its own code, and the two Ramadan
        // targets are distinct constants, so an Iftar alarm can never be mistaken for the Maghrib
        // reminder it derives from.
        // Every slot the day could ever use, taken from the planner's own enumeration rather than
        // from a hand-written list that could drift away from it.
        val allSlots = planner.windowSlots(listOf(date))
        assertEquals(ReminderTarget.entries.size * PrayerReminderKind.entries.size, allSlots.size)
        val prayerSlots = allSlots.filterNot { it.target.isRamadanReminder }
        val ramadanSlots = allSlots.filter { it.target.isRamadanReminder }

        // Six non-Ramadan targets (five prayers and sunrise) and three Ramadan ones (Sehri, Iftar and
        // the precautionary Imsak), each with an at-time and an advance slot.
        assertEquals(12, prayerSlots.size)
        assertEquals(6, ramadanSlots.size)
        val codes = allSlots.map { PrayerNotificationIds.requestCode(it) }
        assertEquals(codes.size, codes.toSet().size)
        // And the same slot always maps back from its alarm URI, which is how a fired alarm is
        // identified as Sehri or Iftar rather than as a prayer.
        ramadanSlots.forEach { slot ->
            assertEquals(slot, PrayerNotificationIds.slotFromUri(PrayerNotificationIds.uri(slot)))
        }
    }

    @Test
    fun `a date of Ramadan that is no longer in Ramadan leaves no reminder behind`() {
        // What an adjustment or the end of Ramadan looks like to the planner: the same schedule, a
        // different date set. The obsolete day's slots are simply not in the new plan, which is what the
        // coordinator's derived cancellation then cancels.
        val during = plan(ramadanOnly(sehri = false))
        val after = plan(ramadanOnly(sehri = false), ramadanDates = emptySet())

        assertTrue(during.any { it.slot.target == ReminderTarget.IFTAR })
        assertTrue(after.none { it.slot.target == ReminderTarget.IFTAR })
    }

    @Test
    fun `a Ramadan reminder inherits the sound and vibration of the prayer it derives from`() {
        val settings = ramadanOnly()
            .withPreference(
                PrayerName.MAGHRIB,
                PrayerNotificationSettings.DEFAULT.preferenceFor(PrayerName.MAGHRIB)
                    .copy(sound = NotificationSound.SILENT, vibration = false),
            )
        val iftar = plan(settings).ramadan().single { it.slot.target == ReminderTarget.IFTAR }

        // Iftar is Maghrib, so it is announced with Maghrib's own preference — and therefore its
        // existing notification channel rather than a new one.
        assertEquals(NotificationSound.SILENT, iftar.sound)
        assertFalse(iftar.vibration)
    }

    @Test
    fun `a Fajr that has already passed is not planned as a Sehri reminder`() {
        val afterFajr = ZonedDateTime.of(date, LocalTime.of(5, 0), zone)
        val planned = planner.plan(
            settings = ramadanOnly(sehriLead = 10),
            schedules = listOf(schedule()),
            now = afterFajr,
            ramadanDates = setOf(date),
        )

        assertTrue(planned.none { it.slot.target == ReminderTarget.SEHRI })
        // Iftar is still ahead at 05:00, so it is still planned.
        assertNotNull(planned.firstOrNull { it.slot.target == ReminderTarget.IFTAR })
    }

    @Test
    fun `the settings model clamps a Ramadan lead time to an offered value`() {
        assertEquals(5, PrayerNotificationSettings.clampSehriLead(7))
        assertEquals(10, PrayerNotificationSettings.clampSehriLead(10))
        assertEquals(5, PrayerNotificationSettings.clampSehriLead(0))
        assertEquals(30, PrayerNotificationSettings.clampSehriLead(1000))
        assertEquals(0, PrayerNotificationSettings.clampIftarLead(0))
        assertEquals(5, PrayerNotificationSettings.clampIftarLead(6))
        assertEquals(30, PrayerNotificationSettings.clampIftarLead(999))

        // And the with-ers apply the same clamp, so a stored value is always representable.
        assertEquals(5, PrayerNotificationSettings.DEFAULT.withSehriReminder(true, 0).sehriLeadMinutes)
        assertEquals(0, PrayerNotificationSettings.DEFAULT.withIftarReminder(true, 0).iftarLeadMinutes)
        assertEquals(15, PrayerNotificationSettings.DEFAULT.withSehriReminder(true, 15).sehriLeadMinutes)
    }

    @Test
    fun `an enabled Ramadan reminder counts as something enabled`() {
        // Every prayer reminder off, so only a Ramadan switch can make the answer true.
        val off = PrayerName.entries.fold(PrayerNotificationSettings.DEFAULT) { settings, prayer ->
            settings.withPreference(prayer, settings.preferenceFor(prayer).copy(enabled = false))
        }
        assertFalse(off.hasAnyEnabledTarget)

        assertTrue(off.withSehriReminder(enabled = true).hasAnyEnabledTarget)
        assertTrue(off.withIftarReminder(enabled = true).hasAnyEnabledTarget)
        // The master switch still wins.
        assertFalse(off.copy(masterEnabled = false).withIftarReminder(enabled = true).hasAnyEnabledTarget)
    }

    @Test
    fun `the Ramadan targets are the three the reminder system knows about`() {
        assertEquals(
            listOf(ReminderTarget.SEHRI, ReminderTarget.IFTAR, ReminderTarget.IMSAK),
            ReminderTarget.ramadanReminders,
        )
        assertTrue(ReminderTarget.ramadanReminders.all { it.prayer == null })
        assertFalse(ReminderTarget.SEHRI.isObligatoryPrayer)
        assertFalse(ReminderTarget.IFTAR.isObligatoryPrayer)
        assertFalse(ReminderTarget.IMSAK.isObligatoryPrayer)
    }

    @Test
    fun `the precautionary Imsak reminder is planned only when an interval is configured`() {
        // Enabled but no interval: nothing is planned, because Salah has no Imsak rule of its own.
        assertTrue(
            plan(ramadanOnly(imsak = true)).none { it.slot.target == ReminderTarget.IMSAK },
        )

        // Interval configured and the reminder enabled: an at-time reminder at Fajr minus the interval.
        val imsak = plan(ramadanOnly(imsak = true), imsakOffsetMinutes = 15)
            .single { it.slot.target == ReminderTarget.IMSAK }
        assertEquals(PrayerReminderKind.AT_TIME, imsak.slot.kind)
        assertTrue(imsak.isAtEventTime)
        // Fajr for this schedule is 04:47, so a 15-minute interval puts Imsak at 04:32 — and the
        // reminder fires *at Imsak*, so both its event and its trigger are that time, not Fajr.
        assertEquals(ZonedDateTime.of(date, LocalTime.of(4, 32), zone), imsak.eventAt)
        assertEquals(ZonedDateTime.of(date, LocalTime.of(4, 32), zone), imsak.triggerAt)
    }

    @Test
    fun `Imsak is a separate time from the Sehri deadline`() {
        val planned = plan(
            ramadanOnly(sehri = true, sehriLead = 10, imsak = true),
            imsakOffsetMinutes = 20,
        )
        val sehri = planned.single { it.slot.target == ReminderTarget.SEHRI }
        val imsak = planned.single { it.slot.target == ReminderTarget.IMSAK }

        // Sehri ends at the calculated Fajr (04:47); Imsak is a different, earlier, separately
        // identified event (04:27) and never replaces the Sehri deadline.
        assertEquals(ZonedDateTime.of(date, LocalTime.of(4, 47), zone), sehri.eventAt)
        assertEquals(ZonedDateTime.of(date, LocalTime.of(4, 27), zone), imsak.eventAt)
        assertFalse(sehri.slot == imsak.slot)
    }

    @Test
    fun `switching the Imsak reminder off removes its slot`() {
        val on = plan(ramadanOnly(imsak = true), imsakOffsetMinutes = 10)
        val off = plan(ramadanOnly(imsak = false), imsakOffsetMinutes = 10)

        assertTrue(on.any { it.slot.target == ReminderTarget.IMSAK })
        assertTrue(off.none { it.slot.target == ReminderTarget.IMSAK })
    }
}

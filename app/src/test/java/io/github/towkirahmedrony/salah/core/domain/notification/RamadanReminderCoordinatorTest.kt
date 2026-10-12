package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakePermissionStatusProvider
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationScheduler
import io.github.towkirahmedrony.salah.testing.FakePrayerScheduleRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.MutableClock
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.testLocation
import io.github.towkirahmedrony.salah.testing.testSchedule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sehri and Iftar reminders at the level the user experiences them: which alarms the coordinator
 * actually registers, and which it removes when the answer changes.
 *
 * This is deliberately not a second `RamadanReminderPlanTest`. That one tests what a *day* produces;
 * this one tests what the **alarm registry** ends up holding after a reconciliation, which is where
 * the two claims this feature makes are decided:
 *
 *  - a Ramadan reminder only ever exists on a day the stored calendar settings place inside Ramadan;
 *  - the day a date leaves Ramadan — because Ramadan ended, or because the user moved their Hijri
 *    offset — its already-scheduled reminders are cancelled, not left behind.
 *
 * [FakePrayerNotificationScheduler] keys alarms by [PrayerAlarmSlot] exactly as `FLAG_UPDATE_CURRENT`
 * does, so asserting on the set of live slots is a statement about real alarms rather than about a
 * stand-in's bookkeeping.
 *
 * Time is fixed and the schedules are synthetic (Fajr 05:00, Maghrib 18:00 in Dhaka). The Gregorian
 * dates are not invented: in the platform's Umm al-Qura table, 11 March 2024 is 1 Ramadan 1445,
 * 9 April 2024 is its 30th and last day, and 10 April 2024 is 1 Shawwal.
 */
class RamadanReminderCoordinatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    /** 00:30 on 11 March 2024 in Dhaka, so both days of the window are still ahead. */
    private val startOfRamadanInstant: Instant = Instant.parse("2024-03-10T18:30:00Z")
    private val today: LocalDate = LocalDate.of(2024, 3, 11)
    private val tomorrow: LocalDate = today.plusDays(1)

    /** The last day of Ramadan 1445 and the day after it, for the end-of-Ramadan scenario. */
    private val lastDayOfRamadan: LocalDate = LocalDate.of(2024, 4, 9)
    private val afterRamadan: LocalDate = LocalDate.of(2024, 4, 10)

    private val logger = RecordingLogger()
    private val scheduler = FakePrayerNotificationScheduler()
    private val permissions = FakePermissionStatusProvider()
    private val clock = MutableClock(startOfRamadanInstant, zone)

    /**
     * A schedule for every date any scenario below can ask for, so a date that has no Ramadan
     * reminder has none because of the calendar and not because its schedule was missing.
     */
    private val schedules = listOf(today, tomorrow, lastDayOfRamadan, afterRamadan, afterRamadan.plusDays(1))
        .associateWith { date -> testSchedule(date, zone) }

    private val notificationSettings = FakePrayerNotificationPreferencesRepository(ramadanOnly())
    private val userPreferences = FakeUserPreferencesRepository()

    private val coordinator = PrayerNotificationCoordinator(
        planner = PrayerNotificationPlanner(),
        notificationSettings = notificationSettings,
        userPreferences = userPreferences,
        locations = FakeLocationRepository(testLocation(zone)),
        schedules = FakePrayerScheduleRepository(schedules),
        scheduler = scheduler,
        permissions = permissions,
        logger = logger,
        clock = clock,
    )

    /**
     * Every prayer and solar reminder turned off, so the only alarms that can appear are Ramadan
     * ones. Without this, a failed assertion could be explained by a prayer reminder.
     */
    private fun ramadanOnly(
        sehri: Boolean = true,
        iftar: Boolean = true,
    ): PrayerNotificationSettings = PrayerName.entries
        .fold(PrayerNotificationSettings.DEFAULT) { settings, prayer ->
            settings.withPreference(prayer, settings.preferenceFor(prayer).copy(enabled = false))
        }
        .withSehriReminder(enabled = sehri, leadMinutes = 10)
        .withIftarReminder(enabled = iftar, leadMinutes = 0)

    /** The alarms the platform currently holds, by slot. */
    private fun liveRamadanSlots(): Set<PrayerAlarmSlot> =
        scheduler.registered.keys.filter { it.target.isRamadanReminder }.toSet()

    private fun liveRamadanSlotsOn(date: LocalDate): Set<PrayerAlarmSlot> =
        liveRamadanSlots().filter { it.date == date }.toSet()

    private fun at(date: LocalDate, time: LocalTime): ZonedDateTime = ZonedDateTime.of(date, time, zone)

    private suspend fun reconcile(): ReconcileOutcome =
        coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)

    @Test
    fun `Sehri and Iftar are scheduled for the Ramadan days in the rolling window`() = runTest {
        val outcome = reconcile()

        // One Sehri lead-in and one at-Iftar reminder, for each of the two Ramadan days in the window.
        assertEquals(
            setOf(
                PrayerAlarmSlot(today, ReminderTarget.SEHRI, PrayerReminderKind.ADVANCE),
                PrayerAlarmSlot(today, ReminderTarget.IFTAR, PrayerReminderKind.AT_TIME),
                PrayerAlarmSlot(tomorrow, ReminderTarget.SEHRI, PrayerReminderKind.ADVANCE),
                PrayerAlarmSlot(tomorrow, ReminderTarget.IFTAR, PrayerReminderKind.AT_TIME),
            ),
            liveRamadanSlots(),
        )
        // Nothing else is registered, which is what makes the assertions above meaningful.
        assertEquals(4, scheduler.registered.size)
        assertTrue(outcome is ReconcileOutcome.Applied)
    }

    @Test
    fun `Sehri is a lead-in to the calculated Fajr and Iftar is the calculated Maghrib`() = runTest {
        reconcile()

        val sehri = scheduler.registered[PrayerAlarmSlot(today, ReminderTarget.SEHRI, PrayerReminderKind.ADVANCE)]
        assertEquals(at(today, LocalTime.of(4, 50)), sehri?.triggerAt)
        assertEquals(at(today, LocalTime.of(5, 0)), sehri?.eventAt)

        val iftar = scheduler.registered[PrayerAlarmSlot(today, ReminderTarget.IFTAR, PrayerReminderKind.AT_TIME)]
        assertEquals(at(today, LocalTime.of(18, 0)), iftar?.triggerAt)
        assertTrue(iftar!!.isAtEventTime)

        // No at-time Sehri reminder: it would land on the Fajr prayer reminder.
        assertFalse(PrayerAlarmSlot(today, ReminderTarget.SEHRI, PrayerReminderKind.AT_TIME) in scheduler.registered)
    }

    @Test
    fun `moving the Hijri offset takes the day that left Ramadan with it`() = runTest {
        reconcile()
        assertTrue(liveRamadanSlotsOn(today).isNotEmpty())

        // One day earlier: 11 March is now 29 Sha'ban, but 12 March is 1 Ramadan. The 11th's
        // reminders must not survive the change, and the 12th's must still be there.
        userPreferences.setHijriAdjustment(-1)
        val outcome = reconcile()

        assertEquals(emptySet<PrayerAlarmSlot>(), liveRamadanSlotsOn(today))
        assertEquals(2, liveRamadanSlotsOn(tomorrow).size)
        outcome as ReconcileOutcome.Applied
        assertTrue(outcome.cancelled >= 2)
    }

    @Test
    fun `an offset in the other direction is applied too`() = runTest {
        // Two days later is the largest offset the calendar settings allow, so this is the far end of
        // the range the user can actually select. Both window days stay inside Ramadan.
        val calendar = HijriCalendar()
        assertEquals(3, calendar.dateOf(today, 2)?.day)

        userPreferences.setHijriAdjustment(2)
        reconcile()

        assertEquals(4, liveRamadanSlots().size)
        assertTrue(liveRamadanSlotsOn(today).isNotEmpty())
    }

    @Test
    fun `the day Ramadan ends keeps its reminders and the day after schedules none`() = runTest {
        // Late on 8 April: today is the last day of Ramadan, tomorrow is 1 Shawwal.
        clock.advanceTo(Instant.parse("2024-04-08T18:30:00Z"))
        reconcile()

        assertEquals(2, liveRamadanSlotsOn(lastDayOfRamadan).size)
        assertEquals(emptySet<PrayerAlarmSlot>(), liveRamadanSlotsOn(afterRamadan))

        // The next day: no date in the window is in Ramadan, so the last day's reminders are cancelled.
        clock.advanceTo(Instant.parse("2024-04-09T18:30:00Z"))
        val outcome = coordinator.reconcile(ReconcileReason.TIME_OR_DATE_CHANGED)

        assertEquals(emptySet<PrayerAlarmSlot>(), liveRamadanSlots())
        outcome as ReconcileOutcome.Applied
        assertTrue(outcome.cancelled >= 2)
    }

    @Test
    fun `turning the Sehri reminder off cancels only its alarms`() = runTest {
        reconcile()
        assertEquals(2, scheduler.registered.keys.count { it.target == ReminderTarget.SEHRI })

        notificationSettings.set(ramadanOnly(sehri = false))
        reconcile()

        assertEquals(0, scheduler.registered.keys.count { it.target == ReminderTarget.SEHRI })
        // Iftar is untouched: one switch does not carry the other with it.
        assertEquals(2, scheduler.registered.keys.count { it.target == ReminderTarget.IFTAR })
    }

    @Test
    fun `the master switch cancels the Ramadan reminders too`() = runTest {
        reconcile()
        assertFalse(scheduler.registered.isEmpty())

        notificationSettings.set(ramadanOnly().copy(masterEnabled = false))
        val outcome = reconcile()

        assertTrue(outcome is ReconcileOutcome.Disabled)
        assertEquals(emptySet<PrayerAlarmSlot>(), liveRamadanSlots())
    }

    @Test
    fun `reconciling twice does not duplicate a Ramadan alarm`() = runTest {
        reconcile()
        val afterFirst = scheduler.registered.keys.toSet()

        reconcile()

        assertEquals(afterFirst, scheduler.registered.keys.toSet())
        assertEquals(4, scheduler.registered.size)
    }

    @Test
    fun `no location means no Ramadan alarm, because there is no Fajr or Maghrib to remind of`() = runTest {
        val withoutLocation = PrayerNotificationCoordinator(
            planner = PrayerNotificationPlanner(),
            notificationSettings = notificationSettings,
            userPreferences = userPreferences,
            locations = FakeLocationRepository(null),
            schedules = FakePrayerScheduleRepository(schedules),
            scheduler = scheduler,
            permissions = permissions,
            logger = logger,
            clock = clock,
        )

        val outcome = withoutLocation.reconcile(ReconcileReason.SETTINGS_CHANGED)

        assertTrue(outcome is ReconcileOutcome.NoLocation)
        assertTrue(scheduler.registered.isEmpty())
    }
}

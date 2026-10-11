package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The behaviour the feature is judged on: what gets scheduled, what gets cancelled, what happens
 * after a reboot, a time change or a location change, and what happens when something is missing.
 *
 * Time is fixed and the schedules are synthetic, so nothing here depends on a real prayer time or a
 * real wait. The clock is mutable, which is what lets "the day rolled over" be a test rather than a
 * hope.
 */
class PrayerNotificationCoordinatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    /** 02:00 on 2026-05-20 in Dhaka, so all five prayers of that day are still ahead. */
    private val startInstant: Instant = Instant.parse("2026-05-19T20:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 5, 20)
    private val tomorrow: LocalDate = today.plusDays(1)
    private val yesterday: LocalDate = today.minusDays(1)

    private val logger = RecordingLogger()
    private val scheduler = FakePrayerNotificationScheduler()
    private val permissions = FakePermissionStatusProvider()
    private val clock = MutableClock(startInstant, zone)

    private fun defaultSchedules() = mapOf(
        today to testSchedule(today, zone),
        tomorrow to testSchedule(tomorrow, zone),
    )

    private fun coordinator(
        schedules: Map<LocalDate, io.github.towkirahmedrony.salah.core.model.PrayerSchedule> = defaultSchedules(),
        preferences: PrayerNotificationSettings = PrayerNotificationSettings.DEFAULT,
        location: io.github.towkirahmedrony.salah.core.model.LocationInfo? = testLocation(zone),
    ): Pair<PrayerNotificationCoordinator, FakePrayerScheduleRepository> {
        val scheduleRepository = FakePrayerScheduleRepository(schedules)
        val instance = PrayerNotificationCoordinator(
            planner = PrayerNotificationPlanner(),
            notificationSettings = FakePrayerNotificationPreferencesRepository(preferences),
            userPreferences = FakeUserPreferencesRepository(),
            locations = FakeLocationRepository(location),
            schedules = scheduleRepository,
            scheduler = scheduler,
            permissions = permissions,
            logger = logger,
            clock = clock,
        )
        return instance to scheduleRepository
    }

    // --- Scheduling -----------------------------------------------------------

    @Test
    fun `enabled prayers for today and tomorrow are scheduled`() = runTest {
        val (coordinator, _) = coordinator()

        val outcome = coordinator.reconcile(ReconcileReason.APP_START)

        assertTrue(outcome is ReconcileOutcome.Applied)
        outcome as ReconcileOutcome.Applied
        assertEquals(PrayerName.entries.size * 2, outcome.scheduled)
        assertEquals(2, outcome.datesWithSchedule.size)

        // Five prayers, two days, at-time reminders only.
        assertEquals(10, scheduler.registered.size)
        assertEquals(
            setOf(today, tomorrow),
            scheduler.slots().map { it.date }.toSet(),
        )
        assertTrue(scheduler.slots().all { it.kind == PrayerReminderKind.AT_TIME })
    }

    @Test
    fun `only relevant future events are scheduled`() = runTest {
        // 16:00 Dhaka: Fajr, Dhuhr and Asr have passed.
        clock.advanceTo(Instant.parse("2026-05-20T10:00:00Z"))
        val (coordinator, _) = coordinator()

        coordinator.reconcile(ReconcileReason.APP_START)

        val todayTargets = scheduler.slots().filter { it.date == today }.map { it.target }.toSet()
        assertEquals(setOf(ReminderTarget.MAGHRIB, ReminderTarget.ISHA), todayTargets)
        assertTrue(scheduler.slots().none { it.target == ReminderTarget.FAJR && it.date == today })
    }

    @Test
    fun `an advance reminder from a preference is scheduled alongside the prayer time`() = runTest {
        val preferences = PrayerNotificationSettings.DEFAULT.withPreference(
            PrayerName.DHUHR,
            PrayerNotificationPreference(advanceMinutes = 10),
        )
        val (coordinator, _) = coordinator(preferences = preferences)

        coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)

        val advance = scheduler.slots().filter { it.kind == PrayerReminderKind.ADVANCE }
        assertEquals(2, advance.size)
        assertTrue(advance.all { it.target == ReminderTarget.DHUHR })
    }

    @Test
    fun `the sunrise reminder is scheduled separately from the prayers`() = runTest {
        val (coordinator, _) = coordinator(
            preferences = PrayerNotificationSettings.DEFAULT.copy(sunriseReminderEnabled = true),
        )

        coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)

        val solar = scheduler.slots().filter { it.target == ReminderTarget.SUNRISE }
        assertEquals(2, solar.size)
        assertEquals(12, scheduler.registered.size)
    }

    // --- Duplicates -----------------------------------------------------------

    @Test
    fun `repeating a reconcile does not duplicate alarms`() = runTest {
        val (coordinator, _) = coordinator()

        coordinator.reconcile(ReconcileReason.APP_START)
        val afterFirst = scheduler.registered.toMap()
        coordinator.reconcile(ReconcileReason.APP_START)
        coordinator.reconcile(ReconcileReason.TIME_OR_DATE_CHANGED)

        assertEquals(afterFirst.size, scheduler.registered.size)
        assertEquals(afterFirst.keys, scheduler.registered.keys)
        assertEquals(10, scheduler.registered.size)
    }

    @Test
    fun `a duplicated boot broadcast does not duplicate alarms`() = runTest {
        val (coordinator, _) = coordinator()

        repeat(3) { coordinator.reconcile(ReconcileReason.BOOT_COMPLETED) }

        assertEquals(10, scheduler.registered.size)
    }

    // --- Cancellation ---------------------------------------------------------

    @Test
    fun `the master switch cancels every managed alarm and schedules nothing`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)
        assertEquals(10, scheduler.registered.size)

        val off = PrayerNotificationSettings.DEFAULT.copy(masterEnabled = false)
        val (disabled, _) = coordinator(preferences = off)
        val outcome = disabled.reconcile(ReconcileReason.SETTINGS_CHANGED)

        assertTrue(outcome is ReconcileOutcome.Disabled)
        assertTrue("no alarm may survive the master switch", scheduler.registered.isEmpty())
        assertTrue(logger.has(SalahDiagnosticEvents.RECONCILE_MASTER_DISABLED))
    }

    @Test
    fun `disabling a prayer cancels the alarm that was already scheduled for it`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)
        assertTrue(scheduler.slots().any { it.target == ReminderTarget.FAJR && it.date == today })

        val preferences = PrayerNotificationSettings.DEFAULT.withPreference(
            PrayerName.FAJR,
            PrayerNotificationPreference(enabled = false),
        )
        val (updated, _) = coordinator(preferences = preferences)
        updated.reconcile(ReconcileReason.SETTINGS_CHANGED)

        assertFalse(scheduler.slots().any { it.target == ReminderTarget.FAJR })
        assertTrue("the other prayers must survive", scheduler.slots().any { it.target == ReminderTarget.ISHA })
    }

    @Test
    fun `alarms for a previous local date are cancelled`() = runTest {
        val (coordinator, _) = coordinator()
        val stale = PrayerAlarmSlot(yesterday, ReminderTarget.FAJR, PrayerReminderKind.AT_TIME)
        scheduler.schedule(
            io.github.towkirahmedrony.salah.core.domain.notification.PlannedPrayerNotification(
                slot = stale,
                triggerAt = testSchedule(yesterday, zone).timeOf(PrayerName.FAJR)!!,
                eventAt = testSchedule(yesterday, zone).timeOf(PrayerName.FAJR)!!,
                advanceMinutes = 0,
                sound = NotificationSound.SYSTEM_DEFAULT,
                vibration = true,
            ),
        )

        coordinator.reconcile(ReconcileReason.TIME_OR_DATE_CHANGED)

        assertFalse(scheduler.registered.containsKey(stale))
    }

    // --- Missing inputs -------------------------------------------------------

    @Test
    fun `without a location nothing is scheduled and everything managed is cancelled`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)
        assertEquals(10, scheduler.registered.size)

        val (withoutLocation, scheduleRepository) = coordinator(location = null)
        val outcome = withoutLocation.reconcile(ReconcileReason.LOCATION_CHANGED)

        assertTrue(outcome is ReconcileOutcome.NoLocation)
        assertTrue(scheduler.registered.isEmpty())
        assertEquals("no calculation input exists without a location", 0, scheduleRepository.calls)
        assertTrue(logger.has(SalahDiagnosticEvents.RECONCILE_NO_LOCATION))
    }

    @Test
    fun `the cancellation window covers every local date a previous plan could have used`() {
        val (coordinator, _) = coordinator()

        val span = coordinator.cancellationDatesFor(today).toSet()

        // Legal zone offsets span UTC-12:00 to UTC+14:00, so the same instant can be a different
        // calendar date in the device zone than in the prayer location's — up to two days apart.
        // A previous plan therefore covers at most [today - 2, today + 3], and every one of those
        // dates has to be reachable by the cancel pass or an obsolete alarm survives.
        (-2..3).forEach { offset ->
            val date = today.plusDays(offset.toLong())
            assertTrue("cancellation must reach $date (offset $offset)", span.contains(date))
        }
    }

    @Test
    fun `cancelling everything reaches alarms planned relative to a different local date`() = runTest {
        // Alarms registered for the plan window, then the location is cleared. The dates used to
        // cancel come from the device zone, which need not be the location's: on a UTC device the
        // location's "today" is a day behind, so a window anchored only on the device's date would
        // leave the later day's alarms behind.
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)
        assertEquals(10, scheduler.registered.size)

        val (withoutLocation, _) = coordinator(location = null)
        withoutLocation.reconcile(ReconcileReason.LOCATION_CHANGED)

        assertTrue(
            "every date in the previous plan must be cancelled, not just the device's date",
            scheduler.registered.isEmpty(),
        )
    }

    @Test
    fun `a past date is always reconciled even when its schedule cannot be produced`() = runTest {
        val (coordinator, _) = coordinator()
        val stale = PrayerAlarmSlot(yesterday, ReminderTarget.ISHA, PrayerReminderKind.AT_TIME)
        val staleTime = testSchedule(yesterday, zone).timeOf(PrayerName.ISHA)!!
        scheduler.schedule(
            io.github.towkirahmedrony.salah.core.domain.notification.PlannedPrayerNotification(
                slot = stale,
                triggerAt = staleTime,
                eventAt = staleTime,
                advanceMinutes = 0,
                sound = NotificationSound.SYSTEM_DEFAULT,
                vibration = true,
            ),
        )

        // No schedule can be produced for any date, so nothing but the past may be touched.
        val (broken, _) = coordinator(schedules = emptyMap())
        broken.reconcile(ReconcileReason.EXPLICIT)

        assertFalse(
            "a date in the past can never be a legitimate future event",
            scheduler.registered.containsKey(stale),
        )
    }

    @Test
    fun `a day with no schedule keeps the alarms already stored for it`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)

        // Tomorrow's timetable can no longer be produced.
        val (partial, _) = coordinator(schedules = mapOf(today to testSchedule(today, zone)))
        val outcome = partial.reconcile(ReconcileReason.EXPLICIT)

        outcome as ReconcileOutcome.Applied
        assertEquals(listOf(tomorrow), outcome.datesWithoutSchedule)
        assertTrue(
            "a transient failure must not silently drop tomorrow's reminders",
            scheduler.slots().any { it.date == tomorrow },
        )
        assertTrue(logger.has(SalahDiagnosticEvents.SCHEDULE_UNAVAILABLE))
    }

    @Test
    fun `a provider failure is reported and never invents times`() = runTest {
        val (coordinator, scheduleRepository) = coordinator(schedules = emptyMap())
        scheduleRepository.failWithProviderError = true

        val outcome = coordinator.reconcile(ReconcileReason.APP_START)

        outcome as ReconcileOutcome.Applied
        assertEquals(0, outcome.scheduled)
        assertTrue(scheduler.registered.isEmpty())
    }

    // --- Recovery and change --------------------------------------------------

    @Test
    fun `reboot recovery reconstructs the schedule from stored settings`() = runTest {
        // A reboot starts with no alarms at all and no network.
        assertTrue(scheduler.registered.isEmpty())
        val (coordinator, _) = coordinator()

        val outcome = coordinator.reconcile(ReconcileReason.BOOT_COMPLETED)

        outcome as ReconcileOutcome.Applied
        assertEquals(10, outcome.scheduled)
        assertEquals(10, scheduler.registered.size)
        assertEquals(
            setOf(today, tomorrow),
            scheduler.slots().map { it.date }.toSet(),
        )
        assertTrue(logger.has(SalahDiagnosticEvents.RECONCILE_STARTED))
        assertTrue(logger.has(SalahDiagnosticEvents.RECONCILE_FINISHED))
    }

    @Test
    fun `a time change rebuilds the window against the new instant`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)

        // The clock moves past today's Fajr and Dhuhr.
        clock.advanceTo(Instant.parse("2026-05-20T08:00:00Z"))
        coordinator.reconcile(ReconcileReason.TIME_OR_DATE_CHANGED)

        assertFalse(scheduler.slots().any { it.target == ReminderTarget.FAJR && it.date == today })
        assertTrue(scheduler.slots().any { it.target == ReminderTarget.ISHA && it.date == today })
    }

    @Test
    fun `the window advances when the local date rolls over`() = runTest {
        val dayAfterTomorrow = today.plusDays(2)
        val (coordinator, _) = coordinator(
            schedules = mapOf(
                today to testSchedule(today, zone),
                tomorrow to testSchedule(tomorrow, zone),
                dayAfterTomorrow to testSchedule(dayAfterTomorrow, zone),
            ),
        )
        coordinator.reconcile(ReconcileReason.APP_START)

        // Just after local midnight on the 21st.
        clock.advanceTo(Instant.parse("2026-05-20T18:30:00Z"))
        coordinator.reconcile(ReconcileReason.TIME_OR_DATE_CHANGED)

        assertEquals(
            setOf(tomorrow, dayAfterTomorrow),
            scheduler.slots().map { it.date }.toSet(),
        )
        assertTrue(
            "yesterday's date must not linger",
            scheduler.slots().none { it.date == today },
        )
    }

    @Test
    fun `a location change reschedules against the new times`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.reconcile(ReconcileReason.APP_START)
        val before = scheduler.registered[
            PrayerAlarmSlot(today, ReminderTarget.FAJR, PrayerReminderKind.AT_TIME),
        ]!!.triggerAt

        // The same slot, but the new location calculates a different Fajr.
        val moved = testSchedule(today, zone, fajr = LocalTime.of(4, 30))
        val (afterMove, _) = coordinator(
            schedules = mapOf(today to moved, tomorrow to testSchedule(tomorrow, zone, fajr = LocalTime.of(4, 30))),
        )
        afterMove.reconcile(ReconcileReason.LOCATION_CHANGED)

        val after = scheduler.registered[
            PrayerAlarmSlot(today, ReminderTarget.FAJR, PrayerReminderKind.AT_TIME),
        ]!!.triggerAt

        assertFalse("the trigger instant must follow the new schedule", before == after)
        assertEquals(
            moved.timeOf(PrayerName.FAJR),
            after,
        )
        assertEquals("a moved alarm replaces, never duplicates", 10, scheduler.registered.size)
    }

    // --- Permissions ----------------------------------------------------------

    @Test
    fun `missing notification permission is reported but does not stop scheduling`() = runTest {
        permissions.current = NotificationPermissionStatus(
            notificationsAllowed = false,
            exactAlarmsAllowed = true,
        )
        val (coordinator, _) = coordinator()

        val outcome = coordinator.reconcile(ReconcileReason.APP_START)

        outcome as ReconcileOutcome.Applied
        assertEquals(10, outcome.scheduled)
        assertTrue(logger.has(SalahDiagnosticEvents.NOTIFICATIONS_NOT_ALLOWED))
        assertTrue(
            "granting permission later must not require a manual recalculation",
            scheduler.registered.isNotEmpty(),
        )
    }

    @Test
    fun `missing exact-alarm access follows the documented fallback`() = runTest {
        permissions.current = NotificationPermissionStatus(
            notificationsAllowed = true,
            exactAlarmsAllowed = false,
        )
        val (coordinator, _) = coordinator()

        val outcome = coordinator.reconcile(ReconcileReason.APP_START)

        outcome as ReconcileOutcome.Applied
        assertTrue(outcome.exactAlarmFallbackInUse)
        assertTrue(logger.has(SalahDiagnosticEvents.EXACT_ALARM_UNAVAILABLE))
        assertEquals("the fallback still schedules", 10, outcome.scheduled)
    }

    // --- Failure handling -----------------------------------------------------

    @Test
    fun `one rejected alarm does not abort the rest of the plan`() = runTest {
        scheduler.failNextSchedule = true
        val (coordinator, _) = coordinator()

        val outcome = coordinator.reconcile(ReconcileReason.APP_START)

        outcome as ReconcileOutcome.Applied
        assertEquals(1, outcome.failed)
        assertEquals(9, outcome.scheduled)
        assertEquals(9, scheduler.registered.size)
    }

    @Test
    fun `a scheduler that refuses everything still reports instead of throwing`() = runTest {
        scheduler.failEverything = true
        val (coordinator, _) = coordinator()

        val outcome = coordinator.reconcile(ReconcileReason.APP_START)

        assertTrue(outcome is ReconcileOutcome.Applied)
        assertEquals(0, (outcome as ReconcileOutcome.Applied).scheduled)
        assertEquals(10, outcome.failed)
        assertTrue(scheduler.registered.isEmpty())
    }

    @Test
    fun `every reconcile is logged with its reason and its counts`() = runTest {
        val (coordinator, _) = coordinator()

        coordinator.reconcile(ReconcileReason.BOOT_COMPLETED)

        val details = logger.detailsOf(SalahDiagnosticEvents.RECONCILE_FINISHED)
        assertNotNull(details)
        assertEquals(ReconcileReason.BOOT_COMPLETED.name, details!!["reason"])
        assertEquals(10, details["scheduled"])
        assertEquals(zone.id, details["zone"])
    }

    @Test
    fun `no coordinate is written to the diagnostics log`() = runTest {
        val (coordinator, _) = coordinator()

        coordinator.reconcile(ReconcileReason.APP_START)

        val rendered = logger.renderAll()
        assertFalse("latitude must never be logged", rendered.contains("23.8103"))
        assertFalse("longitude must never be logged", rendered.contains("90.4125"))
        assertFalse(rendered.contains("Test location"))
    }
}

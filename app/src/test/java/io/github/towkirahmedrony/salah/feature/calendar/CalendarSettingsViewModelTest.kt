package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakePermissionStatusProvider
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationScheduler
import io.github.towkirahmedrony.salah.testing.FakePrayerScheduleRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.testLocation
import io.github.towkirahmedrony.salah.testing.testSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar settings screen, asserted through the real view model.
 *
 * Two of the three controls here are display preferences and one is not. The default view and the date
 * language change what a date looks like and nothing about when a reminder runs, so they must not
 * touch the alarm set. The Hijri offset does the opposite: it moves the Ramadan window, and the Sehri
 * and Iftar reminders are planned from those dates — so moving it must rebuild the alarms immediately,
 * rather than leaving Salah announcing a Sehri that the calendar it now shows no longer contains.
 *
 * The coordinator is the real one over fake platform edges, and the alarm registry is a fake that
 * behaves like `AlarmManager` (registering a slot twice replaces it), so the assertions are about the
 * reminders that exist rather than about a method having been called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSettingsViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    private val logger = RecordingLogger()
    private val notificationScheduler = FakePrayerNotificationScheduler()

    /** A stored location, so the coordinator has a real place to plan for. */
    private val locations = FakeLocationRepository(testLocation(DHAKA_ZONE))

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `moving the Hijri offset is stored and rebuilds the reminders`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = viewModel(repository)

        assertEquals(
            "opening the screen schedules nothing on its own",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )

        viewModel.shiftHijriAdjustment(1)
        runCurrent()

        assertEquals(1, repository.calendarSettings.first().hijriAdjustmentDays)
        assertEquals(
            "the offset moves the Ramadan window, so the alarms are rebuilt from it",
            1,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
        assertTrue(
            "the reminders are planned for the stored location",
            notificationScheduler.registered.isNotEmpty(),
        )
    }

    @Test
    fun `a tap that the clamp does not move reschedules nothing`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = viewModel(repository)

        // Two taps that move the offset, then one past the maximum that stores the same value again.
        viewModel.shiftHijriAdjustment(1)
        viewModel.shiftHijriAdjustment(1)
        runCurrent()
        assertEquals(
            "each of the two real changes reconciles once",
            2,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )

        viewModel.shiftHijriAdjustment(1)
        runCurrent()

        assertEquals(
            CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
            repository.calendarSettings.first().hijriAdjustmentDays,
        )
        assertEquals(
            "nothing changed, so there is nothing to reschedule",
            2,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
    }

    @Test
    fun `the default view and the date language are stored without rescheduling`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = viewModel(repository)

        viewModel.setPreferredSystem(CalendarSystem.BANGLA)
        viewModel.setPreferredSystem(CalendarSystem.HIJRI)
        viewModel.setDateLanguage(DateLanguagePreference.BANGLA)
        runCurrent()

        val stored = repository.calendarSettings.first()
        assertEquals(CalendarSystem.HIJRI, stored.preferredSystem)
        assertEquals(DateLanguagePreference.BANGLA, stored.dateLanguage)
        assertEquals(
            "neither preference changes when a reminder runs",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
    }

    @Test
    fun `resetting the offset writes zero and reconciles`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository(
            initialCalendarSettings = CalendarSettings(hijriAdjustmentDays = -1),
        )
        val viewModel = viewModel(repository)

        // Reset returns the estimate to unconverted; the change is a real one, so it reconciles.
        viewModel.resetHijriAdjustment()
        runCurrent()

        assertEquals(0, repository.calendarSettings.first().hijriAdjustmentDays)
        assertEquals(1, logger.count(SalahDiagnosticEvents.RECONCILE_STARTED))
    }

    private fun viewModel(repository: FakeUserPreferencesRepository): CalendarSettingsViewModel =
        CalendarSettingsViewModel(
            userPreferencesRepository = repository,
            coordinator = PrayerNotificationCoordinator(
                planner = PrayerNotificationPlanner(),
                notificationSettings = FakePrayerNotificationPreferencesRepository(),
                userPreferences = repository,
                locations = locations,
                schedules = FakePrayerScheduleRepository(mapOf(TODAY to testSchedule(TODAY, DHAKA_ZONE))),
                scheduler = notificationScheduler,
                permissions = FakePermissionStatusProvider(),
                logger = logger,
                clock = CLOCK,
            ),
        )

    private companion object {
        val DHAKA_ZONE: ZoneId = ZoneId.of("Asia/Dhaka")

        val TODAY: LocalDate = LocalDate.of(2026, 10, 11)

        /** The same fixed local afternoon the settings screen's tests use, for the same reason. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), DHAKA_ZONE)
    }
}

package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the settings subpages depend on, asserted against the real view model and the real repository
 * contract.
 *
 * The properties that matter are the ones a "move the controls to another screen" refactor can break
 * without failing to compile:
 *
 *  - a stored preference is what the screens open on, rather than the default;
 *  - every action writes through the repository, so the value outlives the screen and a reopened screen
 *    shows the new one;
 *  - consecutive adjustments are applied to the *stored* value, not to a value the screen read when it
 *    was first composed — the mistake that makes the second of two quick taps disappear;
 *  - only a change that really invalidates the timetable says it did;
 *  - and the change reaches the reminders: every control here except the time format moves a calculated
 *    time, so the alarm set is rebuilt from the new profile rather than left announcing the old one.
 *
 * The theme and the calculation settings are the whole of what this view model owns. The location is
 * asserted in `LocationViewModelTest`, because `LocationViewModel` is now the only thing that writes one.
 *
 * The coordinator is the real one over fake platform edges, so "the reminders were rebuilt" is a
 * statement about the alarms that ended up registered rather than about a collaborator being called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    private val logger = RecordingLogger()
    private val notificationScheduler = FakePrayerNotificationScheduler()

    /** The prayer engine's input, so the coordinator has a real place to plan for. */
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
    fun `a stored theme and calculation settings are what the screens open on`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository(
            initialSettings = PrayerSettings(
                method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
                adjustmentsMinutes = mapOf(PrayerName.FAJR to -3),
                timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR,
            ),
            initialTheme = ThemeMode.DARK,
        )
        val viewModel = settingsViewModel(repository)

        // The state that a subpage would render: the stored theme, not `SYSTEM`, which is only the
        // placeholder the flow carries until the store has been read.
        val state = viewModel.awaitState { it.themeMode == ThemeMode.DARK }

        assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, state.prayerSettings.method)
        assertEquals(TimeFormatPreference.TWENTY_FOUR_HOUR, state.prayerSettings.timeFormat)
        assertEquals(-3, state.prayerSettings.adjustmentFor(PrayerName.FAJR))
        assertNull("nothing has been changed yet, so there is nothing to report", state.message)
    }

    @Test
    fun `choosing a theme is written to the store`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        runCurrent()

        assertEquals(ThemeMode.LIGHT, repository.themeMode.first())
        // And the screen follows the store, so the control that was tapped shows the new choice.
        assertEquals(ThemeMode.LIGHT, viewModel.awaitState { it.themeMode == ThemeMode.LIGHT }.themeMode)
    }

    @Test
    fun `a calculation change rebuilds the reminders from the new profile`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        assertEquals(
            "nothing has been changed, so no reconciliation has run",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )

        viewModel.setCalculationMethod(PrayerCalculationMethod.TURKEY)
        runCurrent()

        assertEquals(
            "the write is followed by exactly one reconciliation",
            1,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
        assertTrue(
            "the reminders are planned from the recalculated timetable",
            notificationScheduler.registered.isNotEmpty(),
        )
    }

    @Test
    fun `a time-format change reschedules nothing`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        viewModel.setTimeFormat(TimeFormatPreference.TWENTY_FOUR_HOUR)
        runCurrent()

        assertEquals(
            "the format changes no calculated time, so there is nothing to reschedule",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
    }

    @Test
    fun `a calculation change is stored and reported as a recalculation`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        viewModel.setCalculationMethod(PrayerCalculationMethod.TURKEY)
        viewModel.setAsrMethod(AsrCalculationMethod.STANDARD)
        runCurrent()

        val stored = repository.prayerSettings.first()
        assertEquals(PrayerCalculationMethod.TURKEY, stored.method)
        assertEquals(AsrCalculationMethod.STANDARD, stored.asrMethod)
        assertEquals(
            SettingsMessage.PrayerSettingsSaved,
            viewModel.awaitState { it.message == SettingsMessage.PrayerSettingsSaved }.message,
        )
    }

    @Test
    fun `a time-format change is stored without claiming a recalculation`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        viewModel.setTimeFormat(TimeFormatPreference.TWENTY_FOUR_HOUR)
        runCurrent()

        assertEquals(TimeFormatPreference.TWENTY_FOUR_HOUR, repository.prayerSettings.first().timeFormat)
        // The change did reach the screen, and it said nothing: the time format is not part of
        // `PrayerCalculationProfile`, so no cached timetable is invalidated and nothing is recalculated.
        // The recalculation message would be the one untrue statement on this screen.
        val state = viewModel.awaitState {
            it.prayerSettings.timeFormat == TimeFormatPreference.TWENTY_FOUR_HOUR
        }
        assertNull(state.message)
    }

    @Test
    fun `consecutive adjustments are applied to the stored value`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        // Two taps in a row, without the state being re-read in between: the second must add to the
        // first, not replace it with the same total the screen started from.
        viewModel.shiftAdjustment(PrayerName.FAJR, 1)
        viewModel.shiftAdjustment(PrayerName.FAJR, 1)
        runCurrent()

        assertEquals(2, repository.prayerSettings.first().adjustmentFor(PrayerName.FAJR))
    }

    @Test
    fun `adjustments are clamped by the model and can be cleared`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        // Past the supported range: what is stored is the largest supported correction rather than the
        // number asked for. The clamp is the model's rule, so it holds whatever writes it.
        viewModel.setAdjustment(PrayerName.ISHA, PrayerSettings.MAX_ADJUSTMENT_MINUTES + 30)
        viewModel.setAdjustment(PrayerName.MAGHRIB, -1)
        runCurrent()

        val adjusted = repository.prayerSettings.first()
        assertEquals(PrayerSettings.MAX_ADJUSTMENT_MINUTES, adjusted.adjustmentFor(PrayerName.ISHA))
        assertEquals(-1, adjusted.adjustmentFor(PrayerName.MAGHRIB))
        assertTrue(adjusted.hasAdjustments)

        viewModel.clearAdjustments()
        runCurrent()

        assertEquals(emptyMap<PrayerName, Int>(), repository.prayerSettings.first().adjustmentsMinutes)
    }

    // --- The language selector ----------------------------------------------------

    @Test
    fun `the stored language is what the selector opens on`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository(initialAppLanguage = AppLanguage.BANGLA)
        val viewModel = settingsViewModel(repository)

        val state = viewModel.awaitState { it.appLanguage == AppLanguage.BANGLA }

        assertEquals(AppLanguage.BANGLA, state.appLanguage)
        // Nothing was changed, so there is nothing to report — the selector is not a control that
        // announces itself on open.
        assertNull(state.message)
    }

    @Test
    fun `a fresh install shows the default, which follows the device`() = runTest(mainDispatcher) {
        val viewModel = settingsViewModel(FakeUserPreferencesRepository())

        assertEquals(AppLanguage.DEFAULT, viewModel.awaitState { true }.appLanguage)
        assertEquals(AppLanguage.SYSTEM, viewModel.awaitState { true }.appLanguage)
    }

    @Test
    fun `choosing Bengali stores the Bengali identifier`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = settingsViewModel(repository)

        viewModel.setAppLanguage(AppLanguage.BANGLA)
        runCurrent()

        // Stored by the stable identifier and nowhere else: the translated label the radio row shows
        // is a string resource and never reaches the store.
        assertEquals(AppLanguage.BANGLA, repository.appLanguage.first())
        assertEquals("bn", AppLanguage.BANGLA.languageTag)
        // And the control follows the store, so the tapped choice is the one shown as selected.
        assertEquals(
            AppLanguage.BANGLA,
            viewModel.awaitState { it.appLanguage == AppLanguage.BANGLA }.appLanguage,
        )
    }

    @Test
    fun `choosing English stores the English identifier`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository(initialAppLanguage = AppLanguage.BANGLA)
        val viewModel = settingsViewModel(repository)

        viewModel.setAppLanguage(AppLanguage.ENGLISH)
        runCurrent()

        assertEquals(AppLanguage.ENGLISH, repository.appLanguage.first())
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
        assertEquals(
            AppLanguage.ENGLISH,
            viewModel.awaitState { it.appLanguage == AppLanguage.ENGLISH }.appLanguage,
        )
    }

    @Test
    fun `the chosen language is restored when the screen is recreated`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        settingsViewModel(repository).setAppLanguage(AppLanguage.BANGLA)
        runCurrent()

        // A second view model over the same store: leaving Settings and coming back, or restarting the
        // app, which is the case the preference exists for.
        val reopened = settingsViewModel(repository)

        assertEquals(
            AppLanguage.BANGLA,
            reopened.awaitState { it.appLanguage == AppLanguage.BANGLA }.appLanguage,
        )
    }

    @Test
    fun `a failed language write is reported and the stored language is unchanged`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        repository.languageWriteFailure = IllegalStateException("the store is not writable")
        val viewModel = settingsViewModel(repository)

        viewModel.setAppLanguage(AppLanguage.BANGLA)
        runCurrent()

        // Nothing was stored...
        assertEquals(AppLanguage.DEFAULT, repository.appLanguage.first())
        val reported = viewModel.awaitState { it.message != null }
        // ...the selection still shows the language really in force...
        assertEquals(AppLanguage.DEFAULT, reported.appLanguage)
        // ...and the screen says so rather than implying the choice took effect.
        assertEquals(SettingsMessage.LanguageSaveFailed, reported.message)

        // A later write that succeeds clears the message and does change the language, so the failure
        // is not sticky.
        repository.languageWriteFailure = null
        viewModel.setAppLanguage(AppLanguage.ENGLISH)
        runCurrent()

        assertEquals(AppLanguage.ENGLISH, repository.appLanguage.first())
        assertEquals(
            AppLanguage.ENGLISH,
            viewModel.awaitState { it.appLanguage == AppLanguage.ENGLISH }.appLanguage,
        )
    }

    @Test
    fun `a language change leaves the other preferences and the reminders alone`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository(
            initialSettings = PrayerSettings(
                method = PrayerCalculationMethod.TURKEY,
                adjustmentsMinutes = mapOf(PrayerName.FAJR to -3),
            ),
            initialTheme = ThemeMode.DARK,
            initialCalendarSettings = CalendarSettings(
                preferredSystem = CalendarSystem.HIJRI,
                hijriAdjustmentDays = 1,
            ),
        )
        val viewModel = settingsViewModel(repository)
        viewModel.awaitState { it.appLanguage == AppLanguage.DEFAULT }

        viewModel.setAppLanguage(AppLanguage.BANGLA)
        runCurrent()

        assertEquals(AppLanguage.BANGLA, repository.appLanguage.first())
        // The theme, the calculation settings and every calendar preference are untouched: the language
        // is its own key, so a word changing cannot move a time or a date.
        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertEquals(PrayerCalculationMethod.TURKEY, repository.prayerSettings.first().method)
        assertEquals(-3, repository.prayerSettings.first().adjustmentFor(PrayerName.FAJR))
        assertEquals(CalendarSystem.HIJRI, repository.calendarSettings.first().preferredSystem)
        assertEquals(1, repository.calendarSettings.first().hijriAdjustmentDays)
        // And the alarm set is not rebuilt, because no calculated time changed.
        assertEquals(
            "changing the interface language must not reschedule the reminders",
            0,
            logger.count(SalahDiagnosticEvents.RECONCILE_STARTED),
        )
    }

    @Test
    fun `the selector's options are the app's two explicit languages plus the device`() =
        runTest(mainDispatcher) {
            // What the General screen renders: `AppLanguage.entries`, with both explicit choices
            // present and the default on the device's own locale.
            val viewModel = settingsViewModel(FakeUserPreferencesRepository())
            val state = viewModel.awaitState { true }

            assertEquals(
                listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.BANGLA),
                AppLanguage.entries.toList(),
            )
            assertEquals(AppLanguage.SYSTEM, state.appLanguage)
        }

    /**
     * The first state that satisfies [predicate], waiting for it rather than assuming how many
     * emissions the flow has made.
     *
     * `uiState` is a `StateFlow` that starts on subscription, so reading it once would return the
     * placeholder it carries before the store is read. Waiting makes each test a statement about the
     * value the screen renders; the timeout turns a missing emission into a failure instead of a hang.
     */
    private suspend fun SettingsViewModel.awaitState(
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState = withTimeout(AWAIT_TIMEOUT_MILLIS) { uiState.first(predicate) }

    /**
     * The real view model over the real coordinator and the fake platform edges.
     *
     * The coordinator is not stubbed out: the reconciliation a calculation change triggers is part of
     * what these tests assert, so replacing it would remove the behaviour under inspection. Everything
     * behind it — the alarm registry, the schedule source, the permission state — is a fake, which is
     * why the assertion can be about the alarms that ended up registered rather than about the method
     * that was called.
     */
    private fun settingsViewModel(repository: FakeUserPreferencesRepository): SettingsViewModel =
        SettingsViewModel(
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
        /** Virtual milliseconds; `runTest` advances the clock when the test is idle. */
        const val AWAIT_TIMEOUT_MILLIS = 5_000L

        val DHAKA_ZONE: ZoneId = ZoneId.of("Asia/Dhaka")

        /** The date the schedule below belongs to, one local day for every test here. */
        val TODAY: LocalDate = LocalDate.of(2026, 10, 11)

        /**
         * A fixed local afternoon: 15:00 in Dhaka, so `testSchedule`'s Maghrib and Isha are still to
         * come and a reconciliation produces real alarms instead of an empty set.
         */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), DHAKA_ZONE)
    }
}

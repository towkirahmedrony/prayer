package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
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
 *  - only a change that really invalidates the timetable says it did.
 *
 * The theme and the calculation settings are the whole of what this view model owns. The location is
 * asserted in `LocationViewModelTest`, because `LocationViewModel` is now the only thing that writes one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

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
        val viewModel = SettingsViewModel(repository)

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
        val viewModel = SettingsViewModel(repository)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        runCurrent()

        assertEquals(ThemeMode.LIGHT, repository.themeMode.first())
        // And the screen follows the store, so the control that was tapped shows the new choice.
        assertEquals(ThemeMode.LIGHT, viewModel.awaitState { it.themeMode == ThemeMode.LIGHT }.themeMode)
    }

    @Test
    fun `a calculation change is stored and reported as a recalculation`() = runTest(mainDispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = SettingsViewModel(repository)

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
        val viewModel = SettingsViewModel(repository)

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
        val viewModel = SettingsViewModel(repository)

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
        val viewModel = SettingsViewModel(repository)

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

    private companion object {
        /** Virtual milliseconds; `runTest` advances the clock when the test is idle. */
        const val AWAIT_TIMEOUT_MILLIS = 5_000L
    }
}

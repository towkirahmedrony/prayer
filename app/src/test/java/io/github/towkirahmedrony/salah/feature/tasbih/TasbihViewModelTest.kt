package io.github.towkirahmedrony.salah.feature.tasbih

import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.testing.FakeTasbihRepository
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.tasbihSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.ZoneId

/**
 * The counter's behaviour, exercised through the view model.
 *
 * `TasbihCounterTest` covers the rules and `DataStoreTasbihRepositoryTest` the store; this covers what is
 * left, which is the view model's own responsibility: that the screen's state is assembled from the store
 * and the preferences, that each action makes exactly the one call it means, that a store which cannot be
 * written is reported rather than swallowed, and that an action taken before the store has been read is
 * dropped instead of being attributed to a session that is not there yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasbihViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: FakeTasbihRepository = FakeTasbihRepository(),
        // English explicitly rather than by default: the numerals the state carries follow the interface
        // language, and an assertion about "33" must not depend on the locale the test machine happens to
        // be configured with.
        preferences: FakeUserPreferencesRepository = FakeUserPreferencesRepository(
            initialAppLanguage = AppLanguage.ENGLISH,
        ),
    ): TasbihViewModel = TasbihViewModel(
        repository = repository,
        userPreferencesRepository = preferences,
        uiStateFactory = TasbihUiStateFactory(),
        clock = Clock.systemUTC(),
        zone = zone,
    )

    private fun TestScope.observe(viewModel: TasbihViewModel): List<TasbihUiState> {
        val states = mutableListOf<TasbihUiState>()
        backgroundScope.launch { viewModel.uiState.toList(states) }
        return states
    }

    private fun latestReady(states: List<TasbihUiState>): TasbihUiState.Ready {
        val state = states.lastOrNull()
        assertTrue("expected a Ready state but the latest was $state", state is TasbihUiState.Ready)
        return state as TasbihUiState.Ready
    }

    // --- Reading the store ------------------------------------------------------

    @Test
    fun `the screen starts loading and becomes ready once the store has been read`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        val states = observe(viewModel)

        // The collection has not been dispatched yet, so the initial state is read from the flow itself.
        assertEquals(TasbihUiState.Loading, viewModel.uiState.value)

        runCurrent()

        val ready = latestReady(states)
        assertEquals("0", ready.countDigits)
        assertEquals(TasbihSession.DEFAULT_TARGET.toString(), ready.targetDigits)
        assertFalse(ready.isComplete)
    }

    @Test
    fun `the reading follows the stored session`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 11, target = 33))
        val states = observe(viewModel(repository = repository))
        runCurrent()

        assertEquals("11", latestReady(states).countDigits)
    }

    @Test
    fun `a Bengali interface language reaches the state as Bengali numerals`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 33, target = 33))
        val preferences = FakeUserPreferencesRepository(initialAppLanguage = AppLanguage.BANGLA)
        val states = observe(viewModel(repository = repository, preferences = preferences))
        runCurrent()

        assertEquals("৩৩", latestReady(states).countDigits)
    }

    @Test
    fun `an action taken before the store has been read is dropped`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository()
        val viewModel = viewModel(repository = repository)
        // Deliberately not collecting, so the state is still Loading: there is no session identifier to
        // attribute a count to yet, and guessing one would write to the wrong session.
        viewModel.count()

        runCurrent()

        assertEquals(0, repository.session.count)
        assertEquals(0, repository.writeAttempts)
    }

    // --- Counting ---------------------------------------------------------------

    @Test
    fun `counting records one count`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository()
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        viewModel.count()
        runCurrent()

        assertEquals(1, repository.session.count)
        assertEquals("1", latestReady(states).countDigits)
    }

    @Test
    fun `rapid successive counts all land`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository()
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        // Twenty taps with no waiting between them. Nothing is counted in memory, so every one of them is a
        // write that reads what the write before it left.
        repeat(20) { viewModel.count() }
        runCurrent()

        assertEquals(20, repository.session.count)
        assertEquals("20", latestReady(states).countDigits)
    }

    @Test
    fun `undo takes the last count back`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 6))
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        viewModel.undo()
        runCurrent()

        assertEquals(5, repository.session.count)
        assertEquals("5", latestReady(states).countDigits)
    }

    @Test
    fun `undo does nothing on a session with nothing counted`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 0))
        val viewModel = viewModel(repository = repository)
        observe(viewModel)
        runCurrent()

        viewModel.undo()
        runCurrent()

        assertEquals(0, repository.session.count)
    }

    // --- Target, reset and new session ------------------------------------------

    @Test
    fun `changing the target leaves the count alone`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 7, target = 33))
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        viewModel.setTarget(99)
        runCurrent()

        assertEquals(7, repository.session.count)
        assertEquals(99, repository.session.target)
        assertEquals("99", latestReady(states).targetDigits)
    }

    @Test
    fun `resetting clears the count and keeps the session's identity and target`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 12, target = 99))
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        viewModel.reset()
        runCurrent()

        assertEquals(0, repository.session.count)
        assertEquals("session-1", repository.session.id)
        assertEquals(99, repository.session.target)
        assertEquals("0", latestReady(states).countDigits)
    }

    @Test
    fun `starting a new session archives the current one and keeps its target`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialSession = tasbihSession(count = 8, target = 99))
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        viewModel.startNewSession()
        runCurrent()

        assertEquals(1, repository.storedHistory.size)
        assertEquals(8, repository.storedHistory.first().count)
        assertEquals(0, repository.session.count)
        assertNotEquals("session-1", repository.session.id)
        assertEquals(99, repository.session.target)
        assertEquals("0", latestReady(states).countDigits)
    }

    @Test
    fun `clearing the history leaves the count in progress alone`() = runTest(mainDispatcher) {
        val archived = tasbihSession(id = "session-old", count = 20, target = 33)
        val repository = FakeTasbihRepository(
            initialSession = tasbihSession(count = 4, target = 33),
            initialHistory = listOf(archived),
        )
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()
        assertEquals(1, latestReady(states).history.size)

        viewModel.clearHistory()
        runCurrent()

        assertTrue(repository.storedHistory.isEmpty())
        assertEquals(4, repository.session.count)
        assertTrue(latestReady(states).history.isEmpty())
    }

    @Test
    fun `the haptics preference is written and reaches the state`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository(initialHapticsEnabled = true)
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()
        assertTrue(latestReady(states).hapticsEnabled)

        viewModel.setHapticsEnabled(false)
        runCurrent()

        assertFalse(latestReady(states).hapticsEnabled)
    }

    // --- Failure ----------------------------------------------------------------

    @Test
    fun `a store that cannot be written is reported rather than swallowed`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository()
        repository.writeFailure = IOException("the store is not writable")
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        viewModel.count()
        runCurrent()

        assertEquals(TasbihMessage.SAVE_FAILED, latestReady(states).message)
        assertEquals(0, repository.session.count)
    }

    @Test
    fun `a failure on the next write clears the notice from the last one`() = runTest(mainDispatcher) {
        val repository = FakeTasbihRepository()
        val viewModel = viewModel(repository = repository)
        val states = observe(viewModel)
        runCurrent()

        repository.writeFailure = IOException("the store is not writable")
        viewModel.count()
        runCurrent()
        assertEquals(TasbihMessage.SAVE_FAILED, latestReady(states).message)

        repository.writeFailure = null
        viewModel.count()
        runCurrent()

        assertNull(latestReady(states).message)
        assertEquals(1, repository.session.count)
    }

    @Test
    fun `a write never disturbs an unrelated preference`() = runTest(mainDispatcher) {
        val preferences = FakeUserPreferencesRepository(initialTheme = ThemeMode.DARK)
        val viewModel = viewModel(preferences = preferences)
        observe(viewModel)
        runCurrent()

        viewModel.count()
        viewModel.setTarget(99)
        runCurrent()

        // The counter writes its own store through its own port; the settings repository is read for the
        // language and the clock format and is not written at all.
        assertEquals(ThemeMode.DARK, (preferences.themeMode as StateFlow<ThemeMode>).value)
    }
}

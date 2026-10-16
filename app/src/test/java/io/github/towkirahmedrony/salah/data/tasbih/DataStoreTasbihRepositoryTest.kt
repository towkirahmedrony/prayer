package io.github.towkirahmedrony.salah.data.tasbih

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.data.preferences.DataStoreUserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The Tasbih counter's real store, over a real DataStore file.
 *
 * The properties asserted here are the ones the feature promises and the ones a fake could not establish:
 * that a count survives a restart because it reached the disk, that a burst of updates all land because
 * DataStore serialises its transactions, that an update computed for a session which has since been replaced
 * is dropped, that archiving and starting a new session move together, and — the one that keeps the
 * single-store decision honest — that writing the counter touches no other preference.
 *
 * The clock is a fixed instant plus a counter the test moves, so every timestamp below is an exact value
 * rather than a bound.
 */
class DataStoreTasbihRepositoryTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val baseInstant: Instant = Instant.parse("2026-10-16T06:30:00Z")

    /** How far the test clock has been moved from [baseInstant]. */
    private var elapsedMillis: Long = 0L

    /** How many session identifiers the factory has handed out, so every new session is distinguishable. */
    private var generatedSessions: Int = 0

    private val tickingClock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = baseInstant.plusMillis(elapsedMillis)
    }

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("salah-preferences.preferences_pb")
        }
        elapsedMillis = 0L
        generatedSessions = 0
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repository(): DataStoreTasbihRepository = DataStoreTasbihRepository(
        dataStore = dataStore,
        clock = tickingClock,
        idFactory = { "session-${++generatedSessions}" },
    )

    /** A fresh instance over the same file, which is what a process restart looks like from here. */
    private fun restartedRepository(): DataStoreTasbihRepository = repository()

    /** One count, recorded a second after the one before it. */
    private suspend fun DataStoreTasbihRepository.countOnce() {
        val id = activeSession.first().id
        elapsedMillis += 1_000L
        updateActiveSession(id) { current, now -> TasbihCounter.counted(current, now) }
    }

    // --- The session ------------------------------------------------------------

    @Test
    fun `a fresh install reads an unstarted session rather than nothing`() = runTest {
        val session = repository().activeSession.first()

        assertEquals(TasbihSession.DEFAULT_SESSION_ID, session.id)
        assertEquals(0, session.count)
        assertEquals(TasbihSession.DEFAULT_TARGET, session.target)
        assertNull(session.completedAtMillis)
    }

    @Test
    fun `a count is written through and survives a restart`() = runTest {
        val repository = repository()
        val session = repository.activeSession.first()

        repository.countOnce()

        // A different instance over the same file stands in for the process being recreated.
        val restored = restartedRepository().activeSession.first()

        assertEquals(1, restored.count)
        assertEquals(session.id, restored.id)
        assertEquals(TasbihSession.DEFAULT_TARGET, restored.target)
        assertEquals(baseInstant.plusMillis(1_000L).toEpochMilli(), restored.updatedAtMillis)
    }

    @Test
    fun `rapid successive counts all land`() = runTest {
        val repository = repository()
        val id = repository.activeSession.first().id

        // Ten increments without waiting for any of them: DataStore serialises the transactions, so each one
        // reads the value the previous left rather than the one the caller started from.
        repeat(10) {
            repository.updateActiveSession(id) { current, now -> TasbihCounter.counted(current, now) }
        }

        assertEquals(10, repository.activeSession.first().count)
        assertEquals(10, restartedRepository().activeSession.first().count)
    }

    @Test
    fun `a count cannot be pushed below zero`() = runTest {
        val repository = repository()
        val id = repository.activeSession.first().id

        repository.updateActiveSession(id) { current, now -> TasbihCounter.undone(current, now) }

        assertEquals(0, repository.activeSession.first().count)
    }

    @Test
    fun `an increment and its undo leave the count where it started`() = runTest {
        val repository = repository()
        val id = repository.activeSession.first().id
        repeat(4) { repository.countOnce() }

        repository.updateActiveSession(id) { current, now -> TasbihCounter.undone(current, now) }

        assertEquals(3, repository.activeSession.first().count)
    }

    @Test
    fun `reaching the target stamps the moment and counting on keeps it`() = runTest {
        val repository = repository()
        repeat(TasbihSession.DEFAULT_TARGET) { repository.countOnce() }
        val completed = repository.activeSession.first()

        repository.countOnce()
        val beyond = repository.activeSession.first()

        assertTrue(completed.isComplete)
        assertEquals(TasbihSession.DEFAULT_TARGET, completed.count)
        assertEquals(TasbihSession.DEFAULT_TARGET + 1, beyond.count)
        // Counting on past the target does not rewrite when it was reached.
        assertEquals(completed.completedAtMillis, beyond.completedAtMillis)
        assertEquals(baseInstant.plusMillis(33_000L).toEpochMilli(), completed.completedAtMillis)
    }

    @Test
    fun `an update for a session that has been replaced is dropped`() = runTest {
        val repository = repository()
        val first = repository.activeSession.first()
        repository.countOnce()

        // A new session takes over the one key.
        repository.startNewSession()
        val second = repository.activeSession.first()
        assertNotEquals(first.id, second.id)

        // A count that was in flight while the session changed belongs to the session that is gone.
        repository.updateActiveSession(first.id) { current, now -> TasbihCounter.counted(current, now) }

        val after = repository.activeSession.first()
        assertEquals(second.id, after.id)
        assertEquals(0, after.count)
    }

    @Test
    fun `an unreadable stored session reads as an unstarted one`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("tasbih_active_session")] = "not-a-session-at-all"
        }

        val session = repository().activeSession.first()

        assertEquals(0, session.count)
        assertEquals(TasbihSession.DEFAULT_SESSION_ID, session.id)
    }

    @Test
    fun `resetting keeps the session identity and the target`() = runTest {
        val repository = repository()
        val id = repository.activeSession.first().id
        repository.updateActiveSession(id) { current, now ->
            TasbihCounter.retargeted(current, target = 99, nowMillis = now)
        }
        repeat(5) { repository.countOnce() }

        elapsedMillis += 5_000L
        repository.updateActiveSession(id) { current, now -> TasbihCounter.reset(current, now) }

        val reset = repository.activeSession.first()
        assertEquals(0, reset.count)
        assertEquals(id, reset.id)
        assertEquals(99, reset.target)
    }

    // --- The history ------------------------------------------------------------

    @Test
    fun `starting a new session archives the old one and carries the target forward`() = runTest {
        val repository = repository()
        val first = repository.activeSession.first()
        repository.updateActiveSession(first.id) { current, now ->
            TasbihCounter.retargeted(current, target = 99, nowMillis = now)
        }
        repeat(4) { repository.countOnce() }

        repository.startNewSession()

        val history = repository.history.first()
        assertEquals(1, history.size)
        assertEquals(first.id, history.first().id)
        assertEquals(4, history.first().count)
        assertEquals(99, history.first().target)

        val active = repository.activeSession.first()
        assertEquals("session-1", active.id)
        assertNotEquals(first.id, active.id)
        assertEquals(0, active.count)
        // The target the user chose is the one setting carried across sessions.
        assertEquals(99, active.target)
    }

    @Test
    fun `a session with nothing counted is not archived`() = runTest {
        val repository = repository()

        repository.startNewSession()

        assertTrue(repository.history.first().isEmpty())
    }

    @Test
    fun `the history keeps only the most recent sessions`() = runTest {
        val repository = repository()
        val rounds = TasbihSession.HISTORY_LIMIT + 3

        repeat(rounds) {
            repository.countOnce()
            repository.startNewSession()
        }

        val history = repository.history.first()
        assertEquals(TasbihSession.HISTORY_LIMIT, history.size)
        // Every kept session is a different one, and the newest is the last that was archived.
        assertEquals(TasbihSession.HISTORY_LIMIT, history.map { it.id }.distinct().size)
        assertEquals("session-${rounds - 1}", history.first().id)
        // The three oldest were dropped rather than the three newest.
        assertEquals("session-${rounds - TasbihSession.HISTORY_LIMIT}", history.last().id)
    }

    @Test
    fun `clearing the history leaves the session in progress alone`() = runTest {
        val repository = repository()
        repository.countOnce()
        repository.startNewSession()
        val counting = repository.activeSession.first().id
        repeat(2) { repository.countOnce() }

        repository.clearHistory()

        assertTrue(repository.history.first().isEmpty())
        // The count in progress is not a record of the past and is not touched by clearing one.
        val active = repository.activeSession.first()
        assertEquals(counting, active.id)
        assertEquals(2, active.count)
        assertEquals(2, restartedRepository().activeSession.first().count)
    }

    @Test
    fun `the history survives a restart`() = runTest {
        val repository = repository()
        repeat(7) { repository.countOnce() }
        repository.startNewSession()

        val history = restartedRepository().history.first()

        assertEquals(1, history.size)
        assertEquals(7, history.first().count)
    }

    @Test
    fun `an unreadable history reads as no history rather than failing`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("tasbih_history")] = "garbage;also-garbage|1|2"
        }

        assertTrue(repository().history.first().isEmpty())
    }

    // --- Haptics ----------------------------------------------------------------

    @Test
    fun `haptics default to on and the preference persists`() = runTest {
        assertTrue(repository().hapticsEnabled.first())

        repository().setHapticsEnabled(false)

        assertFalse(repository().hapticsEnabled.first())
        assertFalse(restartedRepository().hapticsEnabled.first())
    }

    @Test
    fun `counting is unaffected by the haptics preference`() = runTest {
        val repository = repository()

        repository.setHapticsEnabled(false)
        repeat(3) { repository.countOnce() }

        assertEquals(3, repository.activeSession.first().count)
    }

    // --- Sharing the one store --------------------------------------------------

    @Test
    fun `writing the counter touches no other preference`() = runTest {
        val settings = DataStoreUserPreferencesRepository(dataStore)
        settings.setThemeMode(ThemeMode.DARK)
        settings.updatePrayerSettings { current -> current.copy(method = PrayerCalculationMethod.KUWAIT) }
        settings.setAppLanguage(AppLanguage.BANGLA)
        val before = dataStore.data.first().asMap().keys

        // Every write the counter has: a count, the haptics preference, and starting a new session (which
        // both archives and installs one).
        val repository = repository()
        repository.countOnce()
        repository.setHapticsEnabled(false)
        repository.startNewSession()

        val added = dataStore.data.first().asMap().keys - before
        assertEquals(
            "the counter must not write a key that is not its own",
            setOf(
                stringPreferencesKey("tasbih_active_session"),
                stringPreferencesKey("tasbih_history"),
                booleanPreferencesKey("tasbih_haptics_enabled"),
            ),
            added,
        )
        // And the other preferences are exactly where they were.
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertEquals(PrayerCalculationMethod.KUWAIT, settings.prayerSettings.first().method)
        assertEquals(AppLanguage.BANGLA, settings.appLanguage.first())
    }

    @Test
    fun `clearing the history writes only the history key`() = runTest {
        val repository = repository()
        repository.countOnce()
        repository.startNewSession()
        val before = dataStore.data.first().asMap()

        repository.clearHistory()

        val after = dataStore.data.first().asMap()
        assertEquals(setOf(stringPreferencesKey("tasbih_history")), before.keys - after.keys)
        assertEquals(
            before[stringPreferencesKey("tasbih_active_session")],
            after[stringPreferencesKey("tasbih_active_session")],
        )
    }
}

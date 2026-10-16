package io.github.towkirahmedrony.salah.data.tasbih

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.repository.TasbihRepository
import io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter
import io.github.towkirahmedrony.salah.core.model.TasbihSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Clock
import java.util.UUID

/**
 * The Tasbih counter's state, stored in Salah's one preference store.
 *
 * ## One DataStore, not a second one
 *
 * This class takes the same `DataStore<Preferences>` instance the settings, the location and the
 * notification preferences are stored in, and writes three keys of its own.
 *
 * A second DataStore file was the alternative, and it is the wrong one twice over. It would need its own
 * instance, its own corruption story and its own place in the backup rules, for three values that are
 * neither large nor hot; and it would break the property `docs/ARCHITECTURE.md` §4 states about this
 * layer — that Salah has exactly one file a user's own data lives in. The counter's keys are namespaced
 * (`tasbih_…`) and every write names the keys it owns, so sharing the file does not let the counter
 * disturb a setting: `DataStoreTasbihRepositoryTest` asserts that no other preference moves when the
 * counter is written, and the mirror of that test already exists for the dashboard.
 *
 * ## Why the counter is *not* in `DataStoreUserPreferencesRepository`
 *
 * That class is Salah's settings repository: its fields are options a user chooses once and expects to
 * find again. The active session is not an option, it is the data a screen is built around, and the
 * history is a list. Folding them in would make a settings class the owner of a growing list it has no
 * reason to know about. The two share the store, which is what keeps "one file" true; they do not share
 * a class, which is what keeps each one's responsibilities legible.
 *
 * ## Why one write per tap is correct rather than wasteful
 *
 * Counting writes through on every tap: no debounce, no flush-on-pause, no in-memory counter that the
 * disk catches up with later. DataStore serialises its transactions, so each write reads the value the
 * previous one left and adds one to it — which is why a burst of fast taps cannot lose an increment, and
 * why the app can be killed mid-count without losing the count.
 *
 * The batching the brief asks about is deliberately not implemented, and it is not an omission: the
 * existing architecture offers no *safe* batching here. Any scheme that holds counts in memory and
 * writes them later has a window in which the value the user sees is not the value on disk, and the
 * window closes only when the process happens to die politely. The counter's taps are human-paced — a
 * few a second at the very most — so the cost being avoided is one small preference write per tap, while
 * the guarantee being risked is the number itself. Salah pays the write.
 *
 * ## What a write may assume
 *
 * Every mutation runs inside `DataStore.edit`, against the value found there. The moment an update is
 * applied comes from [clock] *inside* that transaction, so `updatedAtMillis` is the time the value was
 * actually written rather than the time the tap was seen. [updateActiveSession] refuses an update whose
 * expected identifier is not the stored session's, which is what stops a count that was in flight while a
 * new session started from landing in the new session.
 */
class DataStoreTasbihRepository(
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Generates the identifier of a new session.
     *
     * Injected so a test can name the session it expects instead of matching a random value. The default
     * is a UUID: unique across installs, which is all the history needs — the identifier is compared
     * within one device's stored list and is never sent anywhere.
     */
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : TasbihRepository {

    override val activeSession: Flow<TasbihSession> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences.sessionOrUnstarted(clock.millis()) }

    override val history: Flow<List<TasbihSession>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        // Re-applied on read as well as on write, so a history written before the retention limit existed,
        // or restored from a backup, is trimmed before the screen ever sees it.
        .map { preferences -> TasbihCounter.retained(TasbihSessionCodec.decodeHistory(preferences[Keys.HISTORY])) }

    override val hapticsEnabled: Flow<Boolean> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences[Keys.HAPTICS_ENABLED] ?: true }

    override suspend fun updateActiveSession(
        expectedId: String,
        transform: (TasbihSession, Long) -> TasbihSession,
    ) {
        dataStore.edit { preferences ->
            val now = clock.millis()
            val stored = preferences.storedSessionOrNull()
            // A session is stored and it is not the one this update was computed for: the update belongs
            // to a session that has been replaced, so applying it would move a count into its successor.
            if (stored != null && stored.id != expectedId) return@edit

            // With nothing stored there is no session to disagree with, and the base is the session the
            // caller was rendering — which is the identifier the read side stands in for "not yet
            // written". See TasbihSession.DEFAULT_SESSION_ID.
            val base = stored ?: TasbihSession.unstarted(nowMillis = now, id = expectedId)
            preferences[Keys.ACTIVE_SESSION] = TasbihSessionCodec.encode(transform(base, now))
        }
    }

    override suspend fun startNewSession() {
        dataStore.edit { preferences ->
            val now = clock.millis()
            val current = preferences.sessionOrUnstarted(now)

            // Archive first, and only what is worth archiving: a session with nothing counted would be a
            // row in the history that says the user opened the screen. The two writes share this one
            // transaction, so the counter is never stored with the finished session in neither place.
            TasbihCounter.archivable(current)?.let { archived ->
                val existing = TasbihSessionCodec.decodeHistory(preferences[Keys.HISTORY])
                preferences[Keys.HISTORY] = TasbihSessionCodec.encodeHistory(
                    TasbihCounter.retained(listOf(archived) + existing),
                )
            }

            preferences[Keys.ACTIVE_SESSION] =
                TasbihSessionCodec.encode(TasbihCounter.restarted(current, idFactory(), now))
        }
    }

    override suspend fun clearHistory() {
        // The history key, and no other. The active session is not read, not rewritten and not touched,
        // so clearing the record cannot end the count in progress.
        dataStore.edit { preferences -> preferences.remove(Keys.HISTORY) }
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.HAPTICS_ENABLED] = enabled }
    }

    /** The stored session, or `null` when there is none to read — either absent, or unreadable. */
    private fun Preferences.storedSessionOrNull(): TasbihSession? =
        TasbihSessionCodec.decode(this[Keys.ACTIVE_SESSION])

    /**
     * The stored session, or the session a fresh install is in.
     *
     * Absent and unreadable are the same answer on purpose: both mean "there is no session here", and both
     * are recovered by starting from [TasbihSession.unstarted] with the identifier the write side also
     * uses for that state, so the first count of the fresh install is stored rather than discarded.
     */
    private fun Preferences.sessionOrUnstarted(nowMillis: Long): TasbihSession =
        storedSessionOrNull() ?: TasbihSession.unstarted(nowMillis)

    /** The counter's own keys, namespaced so nothing else in Salah reads or writes them. */
    private object Keys {
        val ACTIVE_SESSION = stringPreferencesKey("tasbih_active_session")
        val HISTORY = stringPreferencesKey("tasbih_history")
        val HAPTICS_ENABLED = booleanPreferencesKey("tasbih_haptics_enabled")
    }
}

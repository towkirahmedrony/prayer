package io.github.towkirahmedrony.salah.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The single DataStore instance used for Salah's local preferences.
 *
 * DataStore is Salah's primary, offline source for user preferences: the UI observes these
 * values directly and no network call is required to read them.
 */
val Context.salahPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "salah_preferences",
)

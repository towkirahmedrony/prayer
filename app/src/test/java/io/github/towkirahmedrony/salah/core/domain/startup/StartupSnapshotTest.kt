package io.github.towkirahmedrony.salah.core.domain.startup

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot exists for one reason: the location gate and Home read it *during the first
 * composition*, while the main thread is busy producing that frame. So the test that matters is not
 * that a value eventually arrives — it is that once it has been read, a reader that starts later can
 * read it without suspending, and that "nothing is stored" is distinguishable from "nothing has been
 * read".
 *
 * The snapshot is given an unconfined test scope rather than `backgroundScope`, because that is the
 * closest the test harness gets to the production scope: the collectors start as soon as the snapshot
 * is built (as `Dispatchers.Default` does at application start-up, before any screen exists) and a
 * resume reaches the collector without a dispatch. `backgroundScope` would leave the collectors
 * unstarted until something else suspended, which would make these assertions test the scheduler
 * rather than the snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupSnapshotTest {

    @Test
    fun `nothing is decided until the source has been read`() = runTest {
        val scope = startupScope()
        try {
            val snapshot = StartupSnapshot(
                storedLocationSource = MutableSharedFlow(),
                prayerSettingsSource = MutableSharedFlow(),
                scope = scope,
            )

            assertTrue(snapshot.storedLocation.value is StoredLocationState.Reading)
            assertTrue(snapshot.prayerSettings.value is PrayerSettingsState.Reading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a location read early is readable later without suspending`() = runTest {
        val scope = startupScope()
        try {
            val locations = MutableSharedFlow<LocationInfo?>(replay = 1)
            val snapshot = StartupSnapshot(
                storedLocationSource = locations,
                prayerSettingsSource = MutableSharedFlow(replay = 1),
                scope = scope,
            )

            locations.emit(EAST_LONDON)

            // A plain synchronous read — exactly what the gate does while composing the first frame.
            // No dispatch, no suspension, no repository involved.
            val state = snapshot.storedLocation.value
            assertTrue("the location should already be readable", state is StoredLocationState.Loaded)
            assertEquals(EAST_LONDON, (state as StoredLocationState.Loaded).location)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `awaiting the location waits for the read and then reports nothing configured`() = runTest {
        val scope = startupScope()
        try {
            val locations = MutableSharedFlow<LocationInfo?>(replay = 1)
            val snapshot = StartupSnapshot(
                storedLocationSource = locations,
                prayerSettingsSource = MutableSharedFlow(replay = 1),
                scope = scope,
            )

            val awaited = async { snapshot.awaitStoredLocation() }
            runCurrent()
            assertFalse("nothing has been read yet", awaited.isCompleted)

            locations.emit(null)

            // No location configured. The read completed, and that is what lets the gate send the user
            // to setup instead of holding them on the skeleton.
            assertNull(awaited.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the snapshot follows a later change`() = runTest {
        val scope = startupScope()
        try {
            val locations = MutableSharedFlow<LocationInfo?>(replay = 1)
            val snapshot = StartupSnapshot(
                storedLocationSource = locations,
                prayerSettingsSource = MutableSharedFlow(replay = 1),
                scope = scope,
            )

            locations.emit(EAST_LONDON)
            assertEquals(
                EAST_LONDON,
                (snapshot.storedLocation.value as StoredLocationState.Loaded).location,
            )

            // Clearing the location must reach the snapshot too, or the gate would keep sending the
            // user into a Home that can no longer calculate anything for them.
            locations.emit(null)
            assertNull((snapshot.storedLocation.value as StoredLocationState.Loaded).location)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the prayer settings are published the same way`() = runTest {
        val scope = startupScope()
        try {
            val settings = MutableSharedFlow<PrayerSettings>(replay = 1)
            val snapshot = StartupSnapshot(
                storedLocationSource = MutableSharedFlow(replay = 1),
                prayerSettingsSource = settings,
                scope = scope,
            )

            settings.emit(PrayerSettings.DEFAULT)

            val state = snapshot.prayerSettings.value
            assertTrue("the settings should already be readable", state is PrayerSettingsState.Loaded)
            assertEquals(PrayerSettings.DEFAULT, (state as PrayerSettingsState.Loaded).settings)
            assertEquals(PrayerSettings.DEFAULT, snapshot.awaitPrayerSettings())
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        /** A real, validated location — the snapshot must never be tested against a made-up record. */
        val EAST_LONDON: LocationInfo = requireNotNull(
            LocationInfo.create(
                label = "East London",
                latitude = 51.5074,
                longitude = -0.1278,
                timeZoneId = "Europe/London",
            ),
        )
    }
}

/**
 * A scope for the snapshot under test whose collectors start immediately and resume inline, standing
 * in for the application-wide scope production resolves at start-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.startupScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

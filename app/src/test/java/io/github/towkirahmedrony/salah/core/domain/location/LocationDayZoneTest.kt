package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

/**
 * The one answer to "which zone is a location-dependent civil day decided in".
 *
 * The device's own zone is pinned to UTC so the fallback is a value the test states rather than one the
 * machine happens to have, and the two location zones used here (UTC+6 and UTC-7) are both real and both
 * different from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationDayZoneTest {

    private lateinit var originalDefault: TimeZone

    @Before
    fun pinDefaultZoneToUtc() {
        originalDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalDefault)
    }

    private val dhaka = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"))

    private val losAngeles =
        checkNotNull(LocationInfo.create("Los Angeles", 34.0522, -118.2437, "America/Los_Angeles"))

    @Test
    fun `answers with the device's own zone while no location is configured`() = runTest {
        val dayZone = LocationDayZone(MutableStateFlow<LocationInfo?>(null), backgroundScope)
        runCurrent()

        assertEquals(ZoneId.of("UTC"), dayZone.current())
        assertEquals(ZoneId.of("UTC"), dayZone.zone.first())
    }

    @Test
    fun `answers with the stored location's own zone once one is configured`() = runTest {
        val locations = MutableStateFlow<LocationInfo?>(null)
        val dayZone = LocationDayZone(locations, backgroundScope)
        runCurrent()

        locations.value = dhaka
        runCurrent()

        assertEquals(ZoneId.of("Asia/Dhaka"), dayZone.current())
    }

    @Test
    fun `re-emits when the location moves to another zone, and not when it is re-saved in the same one`() =
        runTest {
            val locations = MutableStateFlow<LocationInfo?>(dhaka)
            val dayZone = LocationDayZone(locations, backgroundScope)
            // Let the collector take the stored location before collecting the zone, so the first value
            // asserted is a settled one rather than a race with the initial `null`.
            runCurrent()

            val seen = mutableListOf<ZoneId>()
            backgroundScope.launch { dayZone.zone.toList(seen) }
            runCurrent()
            assertEquals(listOf(ZoneId.of("Asia/Dhaka")), seen)

            // A re-save of the same place does not move the zone, so it must not re-emit: the calendar
            // repository restarts its wait for the next local midnight on this flow.
            locations.value = dhaka.copy(label = "Dhaka (edited label)")
            runCurrent()
            assertEquals(listOf(ZoneId.of("Asia/Dhaka")), seen)

            locations.value = losAngeles
            runCurrent()
            assertEquals(
                listOf(ZoneId.of("Asia/Dhaka"), ZoneId.of("America/Los_Angeles")),
                seen,
            )
        }

    @Test
    fun `falls back to the device's zone again when the location is cleared`() = runTest {
        val locations = MutableStateFlow<LocationInfo?>(dhaka)
        val dayZone = LocationDayZone(locations, backgroundScope)
        runCurrent()
        assertEquals(ZoneId.of("Asia/Dhaka"), dayZone.current())

        locations.value = null
        runCurrent()

        assertEquals(ZoneId.of("UTC"), dayZone.current())
    }

    @Test
    fun `reads the device's zone fresh while no location is configured`() = runTest {
        val dayZone = LocationDayZone(MutableStateFlow<LocationInfo?>(null), backgroundScope)
        runCurrent()
        assertEquals(ZoneId.of("UTC"), dayZone.current())

        // The device moves to another zone. With no location to answer for, the next read must report
        // the new one rather than the value captured when this object was built.
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(ZoneId.of("Asia/Tokyo"), dayZone.current())
        } finally {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    }
}

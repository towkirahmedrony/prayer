package io.github.towkirahmedrony.salah.core.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The alarm payload is the contract between the scheduler and the receiver. It has to survive the
 * round trip exactly, and it has to fail closed: a payload Salah did not write must be rejected
 * rather than read with the wrong field order.
 */
class PrayerNotificationRequestTest {

    private val slot = PrayerAlarmSlot(
        date = LocalDate.of(2026, 7, 1),
        target = ReminderTarget.ASR,
        kind = PrayerReminderKind.ADVANCE,
    )

    @Test
    fun `a request round-trips through its encoding`() {
        val request = PrayerNotificationRequest(
            slot = slot,
            triggerAtEpochMillis = 1_750_000_000_000,
            eventAtEpochMillis = 1_750_000_600_000,
            advanceMinutes = 10,
        )

        assertEquals(request, PrayerNotificationRequest.decode(request.uri, request.encode()))
    }

    @Test
    fun `the uri identifies the slot and is independent of the times`() {
        val early = PrayerNotificationRequest(slot, 1L, 2L, 0)
        val moved = PrayerNotificationRequest(slot, 9_999L, 10_000L, 0)

        assertEquals(early.uri, moved.uri)
        assertEquals(PrayerNotificationIds.uri(slot), early.uri)
    }

    @Test
    fun `a payload without the version marker is rejected`() {
        assertNull(PrayerNotificationRequest.decode(PrayerNotificationIds.uri(slot), "trigger=1;event=2;advance=0"))
        assertNull(PrayerNotificationRequest.decode(PrayerNotificationIds.uri(slot), null))
        assertNull(PrayerNotificationRequest.decode(PrayerNotificationIds.uri(slot), ""))
    }

    @Test
    fun `a payload with a missing or unparsable field is rejected`() {
        val marker = PrayerNotificationRequest.PAYLOAD_MARKER

        assertNull(PrayerNotificationRequest.decode(PrayerNotificationIds.uri(slot), "$marker;event=2;advance=0"))
        assertNull(PrayerNotificationRequest.decode(PrayerNotificationIds.uri(slot), "$marker;trigger=x;event=2;advance=0"))
        assertNull(PrayerNotificationRequest.decode(PrayerNotificationIds.uri(slot), "$marker;trigger=1;event=2;advance=many"))
    }

    @Test
    fun `an unrecognised slot uri is rejected even with a valid payload`() {
        val payload = PrayerNotificationRequest(slot, 1L, 2L, 0).encode()

        assertNull(PrayerNotificationRequest.decode("salah://prayer-notification/nope", payload))
        assertNull(PrayerNotificationRequest.decode(null, payload))
    }
}

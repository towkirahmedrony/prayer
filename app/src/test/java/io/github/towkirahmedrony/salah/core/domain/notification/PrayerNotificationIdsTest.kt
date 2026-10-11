package io.github.towkirahmedrony.salah.core.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The identity scheme is the foundation of "no duplicate alarms", so it is tested as a property
 * rather than by spot-checking a few values.
 */
class PrayerNotificationIdsTest {

    private val dates = (0 until 400).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }

    @Test
    fun `every slot in a long range gets a distinct request code`() {
        val codes = dates
            .flatMap { date ->
                ReminderTarget.entries.flatMap { target ->
                    PrayerReminderKind.entries.map { kind -> PrayerAlarmSlot(date, target, kind) }
                }
            }
            .map(PrayerNotificationIds::requestCode)

        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `request codes stay positive and within Int range`() {
        dates.forEach { date ->
            ReminderTarget.entries.forEach { target ->
                PrayerReminderKind.entries.forEach { kind ->
                    val code = PrayerNotificationIds.requestCode(PrayerAlarmSlot(date, target, kind))
                    assertTrue("code must be non-negative for $date/$target/$kind", code >= 0)
                }
            }
        }
    }

    @Test
    fun `identity is stable across repeated calls`() {
        val slot = PrayerAlarmSlot(LocalDate.of(2026, 3, 4), ReminderTarget.FAJR, PrayerReminderKind.ADVANCE)

        assertEquals(PrayerNotificationIds.requestCode(slot), PrayerNotificationIds.requestCode(slot))
        assertEquals(PrayerNotificationIds.uri(slot), PrayerNotificationIds.uri(slot))
        assertEquals(PrayerNotificationIds.tag(slot), PrayerNotificationIds.tag(slot))
    }

    @Test
    fun `notification ids and tags are distinct per slot`() {
        val slots = dates.take(30).flatMap { date ->
            ReminderTarget.entries.map { target -> PrayerAlarmSlot(date, target, PrayerReminderKind.AT_TIME) }
        }

        assertEquals(slots.size, slots.map(PrayerNotificationIds::notificationId).toSet().size)
        assertEquals(slots.size, slots.map(PrayerNotificationIds::tag).toSet().size)
    }

    @Test
    fun `every slot round-trips through its uri`() {
        dates.take(10).forEach { date ->
            ReminderTarget.entries.forEach { target ->
                PrayerReminderKind.entries.forEach { kind ->
                    val slot = PrayerAlarmSlot(date, target, kind)
                    assertEquals(slot, PrayerNotificationIds.slotFromUri(PrayerNotificationIds.uri(slot)))
                }
            }
        }
    }

    @Test
    fun `a malformed uri is rejected instead of guessed`() {
        assertNull(PrayerNotificationIds.slotFromUri(null))
        assertNull(PrayerNotificationIds.slotFromUri(""))
        assertNull(PrayerNotificationIds.slotFromUri("https://example.com/1/FAJR/AT_TIME"))
        assertNull(PrayerNotificationIds.slotFromUri("salah://prayer-notification/notanumber/FAJR/AT_TIME"))
        assertNull(PrayerNotificationIds.slotFromUri("salah://prayer-notification/1/NOT_A_TARGET/AT_TIME"))
        assertNull(PrayerNotificationIds.slotFromUri("salah://prayer-notification/1/FAJR/NOT_A_KIND"))
        // Too few segments: a truncated uri must not parse as a different slot.
        assertNull(PrayerNotificationIds.slotFromUri("salah://prayer-notification/1/FAJR"))
        // Too many: an appended value must not be ignored.
        assertNull(PrayerNotificationIds.slotFromUri("salah://prayer-notification/1/FAJR/AT_TIME/extra"))
    }

    @Test
    fun `sunrise is a solar target and never an obligatory prayer`() {
        assertFalse(ReminderTarget.SUNRISE.isObligatoryPrayer)
        assertNull(ReminderTarget.SUNRISE.prayer)
        assertEquals(5, ReminderTarget.obligatoryPrayers.size)
        assertFalse(ReminderTarget.obligatoryPrayers.contains(ReminderTarget.SUNRISE))
    }
}

package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Decides what to do with a fired alarm.
 *
 * This is the last line of defence between an alarm and the user's notification shade, and it exists
 * because **an alarm is not a decision**. An alarm scheduled days ago can fire into a world where
 * the user has since turned notifications off, disabled that prayer, moved time zone or revoked the
 * permission. Re-checking at delivery time is what makes those changes take effect immediately
 * instead of at the next reconciliation, and it is why a stale alarm can never surprise the user.
 *
 * The checks, in order:
 *
 *  1. **Master switch** — off means nothing is shown, whatever the alarm says.
 *  2. **Target enabled** — a disabled prayer or the sunrise reminder does not appear.
 *  3. **Notifications permitted** — without `POST_NOTIFICATIONS` (or with app notifications turned
 *     off) the reminder is suppressed and logged rather than posted into the void.
 *  4. **Not stale** — an alarm that fires more than
 *     [DeliveryOutcome.STALE_TOLERANCE_MILLIS] after its trigger instant is suppressed. A reminder
 *     that arrives hours late, because the phone was off or a Doze window landed much later, is not
 *     information the user can act on.
 *
 * Then it renders the reminder through the user's **current** preferences, not the ones that were
 * stored when the alarm was scheduled, so a sound changed yesterday is heard today.
 *
 * Azan playback is attempted only for an at-time prayer reminder, after the notification has been
 * posted, and its outcome can never affect the notification: audio is an extra, the reminder is the
 * point. Today the bundled audio source reports that no licensed recording ships, so this resolves
 * to [AzanPlaybackResult.Unavailable] and the notification carries the alert (see
 * `docs/PRAYER_NOTIFICATIONS.md` §5).
 *
 * Never throws: every path returns a [DeliveryOutcome].
 */
class PrayerNotificationDeliverer(
    private val notificationSettings: PrayerNotificationPreferencesRepository,
    private val userPreferences: UserPreferencesRepository,
    private val locations: LocationRepository,
    private val permissions: NotificationPermissionStatusProvider,
    private val presenter: PrayerNotificationPresenter,
    private val azanPlayback: AzanPlaybackManager,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
) {

    /** Handles one fired alarm. */
    suspend fun deliver(request: PrayerNotificationRequest): DeliveryOutcome {
        val slot = request.slot

        val settings = notificationSettings.settings.first()
        if (!settings.masterEnabled) {
            return suppress(
                slot.identity,
                SuppressionReason.MASTER_DISABLED,
                "The master switch is off, so this reminder was not shown.",
            )
        }

        val prayer = slot.target.prayer
        val enabled = if (prayer == null) {
            settings.sunriseReminderEnabled
        } else {
            settings.preferenceFor(prayer).enabled
        }
        if (!enabled) {
            return suppress(
                slot.identity,
                SuppressionReason.TARGET_DISABLED,
                "This reminder is disabled in the current settings.",
            )
        }

        if (!permissions.status().notificationsAllowed) {
            return suppress(
                slot.identity,
                SuppressionReason.NOTIFICATIONS_NOT_ALLOWED,
                "Notification permission is not granted, so nothing could be shown.",
            )
        }

        val now = clock.instant()
        val triggerAt = Instant.ofEpochMilli(request.triggerAtEpochMillis)
        val lateness = now.toEpochMilli() - triggerAt.toEpochMilli()
        if (lateness > DeliveryOutcome.STALE_TOLERANCE_MILLIS) {
            return suppress(
                slot.identity,
                SuppressionReason.STALE,
                "The alarm fired ${lateness / 60_000} minutes late, so it was not shown.",
            )
        }

        // The zone the reminder is rendered in: the prayer location's, so it matches the
        // timetable the user sees. Falls back to the device zone only when no location is stored.
        val zone = locations.location.first()?.zoneId ?: ZoneId.systemDefault()
        val preference = prayer?.let { settings.preferenceFor(it) }
        val timeFormat = userPreferences.prayerSettings.first().timeFormat

        val delivery = PrayerReminderDelivery(
            slot = slot,
            eventAt = Instant.ofEpochMilli(request.eventAtEpochMillis).atZone(zone),
            advanceMinutes = request.advanceMinutes,
            sound = preference?.sound ?: NotificationSound.SYSTEM_DEFAULT,
            vibration = preference?.vibration ?: true,
            timeFormat = timeFormat,
        )

        val presented = try {
            presenter.present(delivery)
        } catch (error: Exception) {
            logger.error(SalahDiagnosticEvents.DELIVERY_FAILED, error, mapOf("slot" to slot.identity))
            return DeliveryOutcome.Failed(error.message ?: error::class.simpleName.orEmpty())
        }

        if (!presented) {
            logger.error(
                SalahDiagnosticEvents.DELIVERY_FAILED,
                null,
                mapOf("slot" to slot.identity, "reason" to "presenter_refused"),
            )
            return DeliveryOutcome.Failed("The platform refused to show the reminder.")
        }

        logger.info(
            SalahDiagnosticEvents.DELIVERY_POSTED,
            mapOf(
                "slot" to slot.identity,
                "channel" to delivery.channelId,
                "advanceMinutes" to request.advanceMinutes,
            ),
        )

        // Azan playback is attempted only for the prayer's own time: a lead-in reminder is a
        // notification, not a call to prayer.
        val azan = if (delivery.isAtEventTime && prayer != null) azanPlayback.play() else null

        return DeliveryOutcome.Posted(channelId = delivery.channelId, azan = azan)
    }

    private fun suppress(slotIdentity: String, reason: SuppressionReason, detail: String): DeliveryOutcome {
        logger.info(
            SalahDiagnosticEvents.DELIVERY_SUPPRESSED,
            mapOf("slot" to slotIdentity, "reason" to reason.name),
        )
        return DeliveryOutcome.Suppressed(reason = reason, detail = detail)
    }
}

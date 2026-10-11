package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The result of one reconciliation, with enough detail to log and to show in diagnostics. */
sealed interface ReconcileOutcome {

    /** A plan was applied. */
    data class Applied(
        val scheduled: Int,
        val cancelled: Int,
        val failed: Int,
        val datesWithSchedule: List<LocalDate>,
        val datesWithoutSchedule: List<LocalDate>,
        val exactAlarmFallbackInUse: Boolean,
    ) : ReconcileOutcome

    /** The master switch is off; everything Salah manages was cancelled. */
    data class Disabled(val cancelled: Int) : ReconcileOutcome

    /** No location is configured, so there is no calculation input. Everything was cancelled. */
    data class NoLocation(val cancelled: Int) : ReconcileOutcome

    /** Reconciliation could not run at all. */
    data class Failed(val detail: String) : ReconcileOutcome
}

/**
 * The single place that decides what Salah's alarm set should be, and makes it so.
 *
 * Everything that can invalidate a schedule funnels through [reconcile]: app start, a settings
 * change, a location change, a reboot, a clock or time-zone change, an app update, and the
 * delivery path itself. There is exactly one scheduler and exactly one reconciliation entry point,
 * which is what makes "no duplicate alarms" and "nothing stale is left behind" properties of the
 * design rather than of careful call sites.
 *
 * **The window.** Salah keeps a rolling two-day window: today and tomorrow, in the **prayer
 * location's** zone. Today alone is not enough — after Isha the next reminder belongs to tomorrow,
 * and a phone that spends the night in Doze must already have tomorrow's alarms registered.
 *
 * **What it cancels.** The cancellation set is derived, not remembered: for every date a previous
 * plan could have used, the planner enumerates the full `(target, kind)` cross-product and the
 * coordinator cancels everything not in the new plan. There is no registry to drift out of date,
 * and a reminder scheduled under settings the user has since changed is still found.
 *
 * The set spans more days than the plan does, because the *previous* run's notion of "today" may
 * not have been this one — the location's zone and the device's zone can put the same instant on
 * different dates, and a location change can shift it too. See [cancellationDates] for the bound.
 *
 * **What it deliberately does not do.** If a date's schedule cannot be produced, that date's
 * existing alarms are left alone — unless the date is already in the past, where no alarm can ever
 * be legitimate again. Prayer times for a given date and place do not change on their own, so the
 * alarms already registered for it are still the best known times; cancelling them because of a
 * transient calculation failure would silently drop reminders.
 *
 * **Offline.** Nothing here performs a network request. The schedule comes from the existing
 * offline-first [PrayerScheduleRepository], whose provider is a local astronomical calculation, so
 * reboot recovery works with the radio off.
 *
 * Failures are values: [reconcile] returns a [ReconcileOutcome] and never throws for an expected
 * problem.
 */
class PrayerNotificationCoordinator(
    private val planner: PrayerNotificationPlanner,
    private val notificationSettings: PrayerNotificationPreferencesRepository,
    private val userPreferences: UserPreferencesRepository,
    private val locations: LocationRepository,
    private val schedules: PrayerScheduleRepository,
    private val scheduler: PrayerNotificationScheduler,
    private val permissions: NotificationPermissionStatusProvider,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
) {

    /**
     * Re-derives the alarm set and applies it. Safe to call repeatedly: repeating a
     * reconciliation is idempotent, so a duplicated broadcast cannot duplicate alarms.
     */
    suspend fun reconcile(reason: ReconcileReason): ReconcileOutcome {
        logger.info(SalahDiagnosticEvents.RECONCILE_STARTED, mapOf("reason" to reason.name))

        return try {
            val settings = notificationSettings.settings.first()
            val location = locations.location.first()
            val zone = location?.zoneId ?: ZoneId.systemDefault()
            val today = todayIn(zone)

            // Every date a *previous* plan could have used. This is wider than the planning window
            // because the previous plan's notion of "today" may not have been this one — see
            // [cancellationDates].
            val cancellableDates = cancellationDates(today)

            if (!settings.masterEnabled) {
                val cancelled = cancelAll(cancellableDates, reason = ReconcileReason.SETTINGS_CHANGED)
                logger.info(
                    SalahDiagnosticEvents.RECONCILE_MASTER_DISABLED,
                    mapOf("cancelled" to cancelled),
                )
                return ReconcileOutcome.Disabled(cancelled = cancelled)
            }

            if (location == null) {
                val cancelled = cancelAll(cancellableDates, reason = ReconcileReason.LOCATION_CHANGED)
                logger.warn(
                    SalahDiagnosticEvents.RECONCILE_NO_LOCATION,
                    mapOf("cancelled" to cancelled),
                )
                return ReconcileOutcome.NoLocation(cancelled = cancelled)
            }

            val plannedDates = planner.windowDates(today)
            val resolved = resolveSchedules(plannedDates, location)
            val available = resolved.mapNotNull { it.value }
            val unavailableDates = resolved.filterValues { it == null }.keys.toList()

            val planned = planner.plan(
                settings = settings,
                schedules = available,
                now = ZonedDateTime.ofInstant(clock.instant(), zone),
            )

            // A date may be reconciled only when we know what belongs there:
            //   * a date in the past can never be a legitimate future event, so its slots always go;
            //   * a date with a schedule is reconciled precisely against the new plan;
            //   * a date with no schedule is left untouched, because a transient calculation failure
            //     must not silently drop reminders whose times are still the best known ones.
            val datesWithSchedule = available.mapTo(mutableSetOf()) { it.date }
            val plannedSlots = planned.mapTo(mutableSetOf()) { it.slot }

            var cancelled = 0
            planner.windowSlots(cancellableDates)
                .filter { slot ->
                    slot !in plannedSlots && (slot.date < today || slot.date in datesWithSchedule)
                }
                .forEach { slot ->
                    if (attempt(SalahDiagnosticEvents.ALARM_CANCELLED) { scheduler.cancel(slot) }) cancelled++
                }

            var scheduled = 0
            var failed = 0
            planned.forEach { notification ->
                if (attempt(SalahDiagnosticEvents.ALARM_SCHEDULED) { scheduler.schedule(notification) }) {
                    scheduled++
                } else {
                    failed++
                }
            }

            val status = permissions.status()
            if (status.exactAlarmFallbackInUse) {
                logger.warn(SalahDiagnosticEvents.EXACT_ALARM_UNAVAILABLE, emptyMap())
            }
            if (!status.notificationsAllowed) {
                logger.warn(SalahDiagnosticEvents.NOTIFICATIONS_NOT_ALLOWED, emptyMap())
            }

            logger.info(
                SalahDiagnosticEvents.RECONCILE_FINISHED,
                mapOf(
                    "reason" to reason.name,
                    "scheduled" to scheduled,
                    "cancelled" to cancelled,
                    "failed" to failed,
                    "dates" to plannedDates.size,
                    "zone" to zone.id,
                ),
            )

            ReconcileOutcome.Applied(
                scheduled = scheduled,
                cancelled = cancelled,
                failed = failed,
                datesWithSchedule = available.map { it.date },
                datesWithoutSchedule = unavailableDates,
                exactAlarmFallbackInUse = status.exactAlarmFallbackInUse,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.error(SalahDiagnosticEvents.RECONCILE_UNAVAILABLE, error, emptyMap())
            ReconcileOutcome.Failed(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    /**
     * Every date a previous reconciliation could have scheduled against, given today is [today].
     *
     * **Why this is wider than the planning window.** A cancel has to reach alarms that were
     * planned by an *earlier* run, and that run's "today" may not be this one. Two things can move
     * it:
     *
     *  1. **The zone the date is derived in.** Dates come from the prayer location's zone, but when
     *     no location is configured the fallback is the device's own zone. Legal offsets span
     *     UTC-12:00 to UTC+14:00 — 26 hours — so the same instant can be a different calendar date,
     *     and in the worst case two days apart.
     *  2. **A location change.** Moving to a different zone can shift the location's local date, and
     *     therefore which days the previous plan covered.
     *
     * With a planning window of two days, a previous plan therefore covers at most
     * `[today - 2, today + 3]`. The margin of [CANCELLATION_MARGIN_DAYS] on each side yields
     * `[today - 3, today + 4]`, which contains that whole range with room to spare.
     *
     * Cancelling a slot that has no alarm is a harmless no-op, so a superset is the cheap and
     * correct choice. Narrowing this to "exactly what was scheduled" would require remembering it,
     * and a remembered set is one more thing that can disagree with the platform.
     */
    private fun cancellationDates(today: LocalDate): List<LocalDate> = planner.windowDates(
        today = today.minusDays(CANCELLATION_MARGIN_DAYS),
        days = PrayerNotificationPlanner.WINDOW_DAYS + 2 * CANCELLATION_MARGIN_DAYS,
    )

    /**
     * The cancellation dates for [today], exposed so a test can assert the span covers every local
     * date a previous plan could have used, on any device zone.
     */
    internal fun cancellationDatesFor(today: LocalDate): List<LocalDate> = cancellationDates(today)

    /**
     * Cancels every alarm Salah could have for [slots]' dates.
     *
     * Used when there is nothing to plan: the master switch is off, or no location is configured.
     * Both cases must leave no alarm behind, so this cancels the whole cross-product rather than
     * the subset the current settings would produce.
     */
    private fun cancelAll(dates: List<LocalDate>, reason: ReconcileReason): Int {
        var cancelled = 0
        planner.windowSlots(dates).forEach { slot ->
            if (attempt(SalahDiagnosticEvents.ALARM_CANCELLED) { scheduler.cancel(slot) }) cancelled++
        }
        logger.info(
            SalahDiagnosticEvents.ALARM_CANCELLED,
            mapOf("reason" to reason.name, "count" to cancelled),
        )
        return cancelled
    }

    /**
     * Loads one schedule per date from the existing repository.
     *
     * The repository is the offline-first one the Home screen already uses, so there is one
     * calculation path and one cache, and the times the user sees and the times Salah schedules
     * can never disagree. A date that cannot be produced is reported as `null` — never guessed.
     */
    private suspend fun resolveSchedules(
        dates: List<LocalDate>,
        location: LocationInfo,
    ): Map<LocalDate, PrayerSchedule?> {
        val profile = userPreferences.prayerSettings.first().toProfile()
        return dates.associateWith { date ->
            val request = PrayerScheduleRequest(
                date = date,
                location = location,
                profile = profile,
            )
            val state: PrayerScheduleState? = try {
                schedules.ensureSchedule(request).first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.warn(
                    SalahDiagnosticEvents.SCHEDULE_UNAVAILABLE,
                    mapOf("date" to date.toString(), "reason" to "threw"),
                )
                null
            }

            when (state) {
                // A schema the repository does not produce yet, or an aborted load.
                null -> null

                is PrayerScheduleState.Available -> {
                    logger.info(
                        SalahDiagnosticEvents.SCHEDULE_LOADED,
                        mapOf("date" to date.toString(), "zone" to location.timeZoneId),
                    )
                    state.schedule
                }

                is PrayerScheduleState.Unavailable -> {
                    logger.warn(
                        SalahDiagnosticEvents.SCHEDULE_UNAVAILABLE,
                        mapOf("date" to date.toString(), "reason" to state.reason.name),
                    )
                    null
                }

                is PrayerScheduleState.Failed -> {
                    logger.warn(
                        SalahDiagnosticEvents.SCHEDULE_UNAVAILABLE,
                        mapOf("date" to date.toString(), "reason" to "provider_failed"),
                    )
                    null
                }

                PrayerScheduleState.Loading -> null
            }
        }
    }

    /** Runs [block], reporting `false` instead of propagating a platform refusal. */
    private fun attempt(event: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.error(event, error, mapOf("outcome" to "rejected_by_platform"))
        false
    }

    private fun todayIn(zone: ZoneId): LocalDate =
        ZonedDateTime.ofInstant(clock.instant(), zone).toLocalDate()

    companion object {
        /**
         * Extra days cancelled on each side of the planning window.
         *
         * Not a fudge factor: it is the radius needed to cover the difference between the local
         * date this run derives and the one an earlier run derived. See [cancellationDates].
         */
        const val CANCELLATION_MARGIN_DAYS: Int = 3
    }
}

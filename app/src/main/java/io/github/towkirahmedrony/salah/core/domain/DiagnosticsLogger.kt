package io.github.towkirahmedrony.salah.core.domain

/**
 * Structured diagnostics, as a port.
 *
 * The background parts of Salah — alarms, receivers, reboot recovery — have no screen, so the log
 * is the only place their behaviour can be observed. That makes a predictable, greppable event
 * vocabulary worth having: every call site passes a stable `event` name from
 * [SalahDiagnosticEvents] rather than a free-form sentence, and the Android implementation renders
 * it as `event key=value key=value`.
 *
 * **What must never be logged.** Coordinates, notification text, and anything else private. The
 * call sites in the notification subsystem pass counts, identities, zone ids and reason codes —
 * never a latitude, a longitude or a user's message. See `docs/PRAYER_NOTIFICATIONS.md` §8.
 *
 * Kept Android-free so the scheduler, the planner and the coordinator can be unit-tested with a
 * recording fake instead of a mocked `android.util.Log`.
 */
interface DiagnosticsLogger {

    /** Normal operation worth recording at a coarse level. */
    fun info(event: String, details: Map<String, Any?> = emptyMap())

    /** Something was skipped or degraded but Salah continued correctly. */
    fun warn(event: String, details: Map<String, Any?> = emptyMap())

    /** Something failed. Never used to report success. */
    fun error(event: String, throwable: Throwable? = null, details: Map<String, Any?> = emptyMap())
}

/**
 * The diagnostic event vocabulary.
 *
 * Constants rather than literals so a log line can be grepped reliably and so tests can assert on
 * an event without duplicating a string.
 */
object SalahDiagnosticEvents {

    /** A reconciliation started, with its trigger reason. */
    const val RECONCILE_STARTED = "notification_reconcile_started"

    /** A reconciliation finished, with the number of alarms scheduled and cancelled. */
    const val RECONCILE_FINISHED = "notification_reconcile_finished"

    /** A reconciliation could not run against a real schedule. */
    const val RECONCILE_UNAVAILABLE = "notification_reconcile_unavailable"

    /** The master switch is off, so nothing is scheduled and managed alarms are cancelled. */
    const val RECONCILE_MASTER_DISABLED = "notification_reconcile_master_disabled"

    /** No location is configured, so no calculation input exists. */
    const val RECONCILE_NO_LOCATION = "notification_reconcile_no_location"

    /** A prayer schedule was loaded for a reconciliation. */
    const val SCHEDULE_LOADED = "notification_schedule_loaded"

    /** A prayer schedule could not be produced for a date. */
    const val SCHEDULE_UNAVAILABLE = "notification_schedule_unavailable"

    /** An alarm was registered with the platform. */
    const val ALARM_SCHEDULED = "notification_alarm_scheduled"

    /** An alarm was removed. */
    const val ALARM_CANCELLED = "notification_alarm_cancelled"

    /** An alarm trigger instant was already in the past, so it was not registered. */
    const val ALARM_SKIPPED_PAST = "notification_alarm_skipped_past"

    /** Exact alarms are not permitted, so the reminder was scheduled inexactly instead. */
    const val EXACT_ALARM_UNAVAILABLE = "notification_exact_alarm_unavailable"

    /** Notification permission is not granted, so scheduling continues but delivery cannot occur. */
    const val NOTIFICATIONS_NOT_ALLOWED = "notification_permission_missing"

    /** A fired alarm was delivered. */
    const val DELIVERY_POSTED = "notification_delivery_posted"

    /** A fired alarm was deliberately not shown. */
    const val DELIVERY_SUPPRESSED = "notification_delivery_suppressed"

    /** A fired alarm could not be delivered at all. */
    const val DELIVERY_FAILED = "notification_delivery_failed"

    /** Azan playback started. */
    const val AZAN_STARTED = "azan_playback_started"

    /** Azan playback stopped. */
    const val AZAN_STOPPED = "azan_playback_stopped"

    /** Azan playback was requested but could not run. */
    const val AZAN_UNAVAILABLE = "azan_playback_unavailable"

    /** Azan playback failed. */
    const val AZAN_FAILED = "azan_playback_failed"

    // Qibla compass -----------------------------------------------------------

    /** The user asked for a position. */
    const val QIBLA_LOCATION_REQUESTED = "qibla_location_requested"

    /** A position was obtained. Logged with its accuracy and source, never its coordinates. */
    const val QIBLA_LOCATION_ACQUIRED = "qibla_location_acquired"

    /** A position could not be obtained. */
    const val QIBLA_LOCATION_FAILED = "qibla_location_failed"

    /** The location the bearing is currently calculated from changed. */
    const val QIBLA_LOCATION_SOURCE_CHANGED = "qibla_location_source_changed"

    /** Which sensor combination, if any, the device offers. */
    const val QIBLA_SENSOR_AVAILABILITY = "qibla_sensor_availability"

    /** A sensor was found but could not be registered. */
    const val QIBLA_SENSOR_REGISTRATION_FAILED = "qibla_sensor_registration_failed"

    /** Heading updates started. */
    const val QIBLA_COMPASS_STARTED = "qibla_compass_started"

    /** Heading updates stopped, because the screen no longer needs them. */
    const val QIBLA_COMPASS_STOPPED = "qibla_compass_stopped"

    /** The platform reported magnetic accuracy poor enough to suggest calibration. */
    const val QIBLA_CALIBRATION_RECOMMENDED = "qibla_calibration_recommended"

    /** A bearing was calculated. Logged with the resulting angle, which is not private. */
    const val QIBLA_BEARING_CALCULATED = "qibla_bearing_calculated"

    /** A bearing could not be calculated, and why. */
    const val QIBLA_BEARING_FAILED = "qibla_bearing_failed"
}

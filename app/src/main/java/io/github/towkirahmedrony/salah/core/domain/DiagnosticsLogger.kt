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

    /**
     * A position was obtained but not adopted, so the stored location was kept. Logged with the
     * rejection reason and distance, never the coordinates.
     */
    const val QIBLA_LOCATION_NOT_ADOPTED = "qibla_location_not_adopted"

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

    // Location system ---------------------------------------------------------
    // Coordinates are never logged here either: the events record counts, levels, distances in metres
    // and outcome codes, which is enough to diagnose a failed reverse look-up without recording where
    // the user was standing.

    /** The bundled Bangladesh dataset was parsed. Logged with its size and how much of it was refused. */
    const val LOCATION_DATASET_LOADED = "location_dataset_loaded"

    /** The bundled Bangladesh dataset could not be read at all. */
    const val LOCATION_DATASET_UNAVAILABLE = "location_dataset_unavailable"

    /** The first-launch location requirement was (re)evaluated. */
    const val LOCATION_SETUP_EVALUATED = "location_setup_evaluated"

    /** A location was saved. Logged with its origin and whether an area was attached. */
    const val LOCATION_SAVED = "location_saved"

    /** A location save was rejected by validation. */
    const val LOCATION_SAVE_REJECTED = "location_save_rejected"

    /** A device position was rejected because it is outside Bangladesh. Logged with the distance. */
    const val LOCATION_OUTSIDE_SUPPORTED_COUNTRY = "location_outside_supported_country"

    /** A device position could not be attributed to an administrative area. */
    const val LOCATION_AREA_UNRESOLVED = "location_area_unresolved"

    /** A device position was attributed to an administrative area. */
    const val LOCATION_AREA_RESOLVED = "location_area_resolved"

    /**
     * A device position was matched to a named locality. Logged with the kind of place and the distance.
     */
    const val LOCATION_LOCALITY_RESOLVED = "location_locality_resolved"

    /**
     * A device position could not be matched to a named locality.
     *
     * Logged with a reason code — `no_layer`, `no_district`, `not_precise_enough`, `too_far` — because
     * the four mean different things: only two of them are worth acting on, and a distance in metres is
     * attached to the last one so a threshold that is systematically too tight can be recognised.
     */
    const val LOCATION_LOCALITY_UNRESOLVED = "location_locality_unresolved"

    /**
     * A manual location search could not be run. Logged with the failure reason — never with the query,
     * which would say what the user typed — because a search reads the bundled dataset in memory and
     * the only realistic failures are an unreadable dataset and a parser exception.
     */
    const val LOCATION_SEARCH_FAILED = "location_search_failed"

    // Local dataset foundation -------------------------------------------------
    // Dataset events record versions, counts and rejection codes. Never a coordinate and never a
    // payload's contents: a version string identifies a publication, not a person.

    /** A validated dataset payload became the active dataset. */
    const val DATASET_ACTIVATED = "dataset_activated"

    /** A candidate payload was refused, with the distinct problem codes that refused it. */
    const val DATASET_ACTIVATION_REJECTED = "dataset_activation_rejected"

    /**
     * An applied payload named by the record could not be read, so a different one was used.
     *
     * Logged with the slot that failed and, when one was found, the slot it was recovered from — or
     * nothing when the fallback had to go all the way to the bundled assets. This is the event that
     * says "the app recovered", and it is a warning rather than an error because the app did recover.
     */
    const val DATASET_APPLIED_UNAVAILABLE = "dataset_applied_unavailable"

    // Remote dataset synchronisation ------------------------------------------
    // The remote path is invisible by construction — it runs off the main thread and changes no screen —
    // so the log is the only evidence it ran. Events record the trigger, the dataset slot, the publication
    // id and failure codes. Never a credential: the anon key is a header and is never passed here.

    /** A synchronisation attempt began, with the trigger that started it. */
    const val DATASET_SYNC_STARTED = "dataset_sync_started"

    /** A check found nothing to change, so no payload was downloaded. */
    const val DATASET_SYNC_UP_TO_DATE = "dataset_sync_up_to_date"

    /** A validated remote publication became the active dataset. */
    const val DATASET_SYNC_ACTIVATED = "dataset_sync_activated"

    /** A remote publication (or an activation candidate) was refused, with the problem codes. */
    const val DATASET_SYNC_REJECTED = "dataset_sync_rejected"

    /** A synchronisation attempt failed, with its reason code. The active dataset is unchanged. */
    const val DATASET_SYNC_FAILED = "dataset_sync_failed"

    /** No remote dataset is configured, so nothing was attempted. The app runs on local data. */
    const val DATASET_SYNC_NOT_CONFIGURED = "dataset_sync_not_configured"

    /** A check ran too recently and was suppressed by the throttle policy. */
    const val DATASET_SYNC_THROTTLED = "dataset_sync_throttled"

    /**
     * The device was clearly offline, so no request was attempted. Expected operation, not a failure:
     * the active dataset is untouched and the last-success timestamp is unchanged.
     */
    const val DATASET_SYNC_OFFLINE = "dataset_sync_offline"

    /** A transient failure was recorded and a later retry was scheduled with exponential backoff. */
    const val DATASET_SYNC_RETRY_SCHEDULED = "dataset_sync_retry_scheduled"

    // Remote prayer-configuration synchronisation ------------------------------
    // Separate from the dataset events on purpose, and with the same discipline: events record the trigger,
    // the configuration slot, the revision id and failure codes. Never a credential, a raw payload or a
    // coordinate.

    /** A prayer-configuration synchronisation began, with the trigger that started it. */
    const val PRAYER_CONFIG_SYNC_STARTED = "prayer_config_sync_started"

    /** A check found nothing to change, so no payload was downloaded. */
    const val PRAYER_CONFIG_SYNC_UP_TO_DATE = "prayer_config_sync_up_to_date"

    /** A validated remote revision became the active prayer configuration. */
    const val PRAYER_CONFIG_SYNC_ACTIVATED = "prayer_config_sync_activated"

    /** A remote revision (or an activation candidate) was refused, with the problem codes. */
    const val PRAYER_CONFIG_SYNC_REJECTED = "prayer_config_sync_rejected"

    /** A synchronisation attempt failed, with its reason code. The active configuration is unchanged. */
    const val PRAYER_CONFIG_SYNC_FAILED = "prayer_config_sync_failed"

    /** No remote prayer configuration is configured, so nothing was attempted. */
    const val PRAYER_CONFIG_SYNC_NOT_CONFIGURED = "prayer_config_sync_not_configured"

    /** A check ran too recently and was suppressed by the throttle policy. */
    const val PRAYER_CONFIG_SYNC_THROTTLED = "prayer_config_sync_throttled"

    /** The device was clearly offline, so no request was attempted. Not a failure. */
    const val PRAYER_CONFIG_SYNC_OFFLINE = "prayer_config_sync_offline"

    /** A transient failure was recorded and a later retry was scheduled with exponential backoff. */
    const val PRAYER_CONFIG_SYNC_RETRY_SCHEDULED = "prayer_config_sync_retry_scheduled"

    /** The stored prayer configuration could not be read, so the bundled defaults are used. */
    const val PRAYER_CONFIG_STORED_UNAVAILABLE = "prayer_config_stored_unavailable"

    /** The effective calculation settings were reapplied from the active configuration and user overrides. */
    const val PRAYER_CONFIG_EFFECTIVE_APPLIED = "prayer_config_effective_applied"

    /** The user reset a calculation override back to the applicable default. */
    const val PRAYER_CONFIG_OVERRIDE_RESET = "prayer_config_override_reset"
}

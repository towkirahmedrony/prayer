package io.github.towkirahmedrony.salah.core.domain.notification

/**
 * Why a reconciliation was requested.
 *
 * Recorded in the diagnostics log so a surprising schedule can be traced to the event that caused
 * it. The set is deliberately closed: every trigger in the app maps onto exactly one of these, and
 * anything that does not is a signal that a trigger is missing rather than a reason to add a code.
 */
enum class ReconcileReason {

    /** The app process started. */
    APP_START,

    /** The user changed a notification preference, or turned the master switch on or off. */
    SETTINGS_CHANGED,

    /** The prayer calculation settings changed, so the times themselves changed. */
    CALCULATION_SETTINGS_CHANGED,

    /** The configured location changed, so times, zone and possibly the date changed. */
    LOCATION_CHANGED,

    /** The device rebooted. Alarms do not survive a reboot, so they are rebuilt from scratch. */
    BOOT_COMPLETED,

    /**
     * The device clock, its time zone, or the calendar date changed.
     *
     * One reason covers `TIME_SET`, `TIMEZONE_CHANGED` and `DATE_CHANGED` because the response is
     * identical for all three: discard the window and re-derive it from the current instant.
     */
    TIME_OR_DATE_CHANGED,

    /** The app was updated; the platform drops the app's alarms when it is replaced. */
    PACKAGE_REPLACED,

    /** A reminder was just delivered, so the rolling window is advanced while we are awake. */
    AFTER_DELIVERY,

    /** The user asked for a recalculation, or Salah is checking itself on screen resume. */
    EXPLICIT,
    ;
}

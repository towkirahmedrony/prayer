package io.github.towkirahmedrony.salah.core.model

/**
 * Machine-readable reason why a section of Salah has no data to show.
 *
 * Salah only displays values that were actually produced by a real calculation, a verified
 * rule set, or the device clock. Every other section reports one of these reasons and is
 * explained in the UI, so a placeholder value is never presented as religious information.
 */
enum class UnavailabilityReason {
    /** No location has been configured, so no calculation input exists. */
    NO_LOCATION_CONFIGURED,

    /** No prayer-time provider is integrated yet. */
    CALCULATION_NOT_CONFIGURED,

    /** Prohibited-time rules have not been reviewed and encoded yet. */
    RULES_NOT_VERIFIED,

    /** The calendar conversion for this system is not implemented yet. */
    CALENDAR_NOT_IMPLEMENTED,

    /** A remote provider could not be reached; local data, if any, is still shown. */
    NETWORK_UNAVAILABLE,

    /** Anything not covered by a more specific reason. */
    UNKNOWN,
}

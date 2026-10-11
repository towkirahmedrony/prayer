package io.github.towkirahmedrony.salah.core.model

/** The three calendar systems Salah presents, once all of them are implemented. */
enum class CalendarSystem {
    GREGORIAN,

    /** Bangladesh civil (Bangla) calendar. */
    BANGLA,

    HIJRI,
}

/**
 * State of one calendar representation.
 *
 * [Available] is only used for values that were really computed, from the device clock for
 * Gregorian, or from a verified conversion implementation for the others. [NotAvailable]
 * carries the reason so the UI can explain the gap instead of inventing a date.
 */
sealed interface CalendarDateState {
    val system: CalendarSystem

    data class Available(
        override val system: CalendarSystem,
        /** Already formatted for display, in the user's locale. */
        val formattedDate: String,
        /** True when the value comes from a source Salah considers authoritative for display. */
        val isVerifiedSource: Boolean,
    ) : CalendarDateState

    data class NotAvailable(
        override val system: CalendarSystem,
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : CalendarDateState
}

/** Snapshot of the calendar information shown on the Home screen. */
data class CalendarSnapshot(
    val gregorian: CalendarDateState,
    val bangla: CalendarDateState,
    val hijri: CalendarDateState,
    /** Localised weekday name for today, if available. */
    val weekdayLabel: String?,
)

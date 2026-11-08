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

/**
 * One calendar's date for the Home hero card, split into the parts the card sets in different sizes.
 *
 * The same date [CalendarSnapshot.gregorian], [CalendarSnapshot.bangla] and [CalendarSnapshot.hijri]
 * state as one string; this is that date taken apart so the card can show `8 Nov` large and
 * `Sunday · 2026` small without parsing a formatted string. Every part is already written in the
 * script and numerals the date language chose, so the card formats nothing.
 *
 * The card's secondary line is built from these parts and a localised label, which is why they are
 * carried separately instead of as two ready-made lines: the label (`Bangla`, `বাংলা`) is a string
 * resource, and this model stays free of Android.
 */
data class CalendarHeadline(
    val system: CalendarSystem,
    /** The large part: day and month, for example `8 Nov`, `২৩ কার্তিক` or `২৬ جُمادى الأولى`. */
    val primary: String,
    /** The year, in the same numerals as [primary]. */
    val year: String,
    /** The weekday name, for the Gregorian headline only; `null` for the other two. */
    val weekday: String? = null,
)

/** Snapshot of the calendar information shown on the Home screen. */
data class CalendarSnapshot(
    val gregorian: CalendarDateState,
    val bangla: CalendarDateState,
    val hijri: CalendarDateState,
    /** Localised weekday name for today, if available. */
    val weekdayLabel: String?,
    /**
     * The hero card's date columns, in Gregorian, Bangla, Hijri order.
     *
     * Only the calendars that actually resolved are present: a date outside a converter's supported
     * range is left out, never approximated. Empty by default so a snapshot built without it (as the
     * domain-level tests do) still reads as "no headline dates".
     */
    val headlines: List<CalendarHeadline> = emptyList(),
)

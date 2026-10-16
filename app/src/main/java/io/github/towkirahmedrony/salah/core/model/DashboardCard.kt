package io.github.towkirahmedrony.salah.core.model

/**
 * One card the Home dashboard can show.
 *
 * **Each member wraps a feature Salah already implements.** A card is never a new calculation: it
 * renders, from the same state the corresponding screen reads, the prayer period and countdown, the
 * day's timetable, the calendar dates, the prohibited intervals, the night periods, the solar
 * geometry, the Ramadan window or the Qibla bearing. Nothing here is a placeholder, and a member is
 * added only when a real feature already exists to back it.
 *
 * **The identifier is stable and never a translated label.** [id] is what is persisted in
 * `DataStore`, so a card's name can be re-worded or re-translated in any language without moving the
 * user's saved order or flipping their show/hide choice. It is deliberately a plain, lower-case
 * token, the same convention every other Salah preference uses.
 *
 * **Declaration order is the default order** and the order in which cards unknown to a saved layout
 * are appended, so a newly introduced card appears in a sensible place without disturbing the cards
 * the user already arranged. The Home screen's management list, and the set of cards the dashboard
 * can render, are both derived from [entries], so a card cannot be persisted without a composable or
 * rendered without a switch.
 *
 * [defaultEnabled] is the default show/hide state: the cards Home has always shown, plus the
 * today's-date and Ramadan cards, are on; the more occasional readings (solar geometry and the Qibla
 * bearing) are off, so a fresh install opens on the daily prayer routine rather than on every
 * feature at once. Selections and the order are stored independently of every other preference —
 * prayer calculation, location, language and reminders are untouched by anything here.
 */
enum class DashboardCard(
    val id: String,
    val defaultEnabled: Boolean = true,
) {
    /** The prayer period that is open now — or the next one — with its countdown, arc and solar span. */
    NEXT_PRAYER("next_prayer"),

    /** Today's five prayers as a list, with the current and next prayer marked. */
    TODAY_TIMETABLE("today_timetable"),

    /** The prohibited intervals around the day's solar events. */
    FORBIDDEN_TIMES("forbidden_times"),

    /** Today's Gregorian, Bangladesh Bangla and Hijri dates. */
    CALENDAR("calendar"),

    /** Sehri and Iftar while Ramadan is in progress, or how long until it begins. */
    RAMADAN("ramadan"),

    /** Islamic midnight, the last third and Tahajjud for the coming night. */
    NIGHT_PERIODS("night_periods"),

    /** Shortcuts to the Qibla compass, the calendar, Ramadan and the tools. */
    DAILY_TOOLS("daily_tools"),

    /** Solar midnight and the measured day and night lengths. */
    SOLAR_GEOMETRY("solar_geometry", defaultEnabled = false),

    /** The Qibla bearing and distance from the saved location. */
    QIBLA("qibla", defaultEnabled = false),
    ;

    companion object {
        /** The card whose persisted [id] is [id], or `null` when no supported card claims it. */
        fun fromId(id: String): DashboardCard? = entries.firstOrNull { card -> card.id == id }

        /** The default order: the declaration order, which is also what a fresh install is on. */
        val defaultOrder: List<DashboardCard> get() = entries.toList()

        /** The cards a fresh install shows. */
        val defaultEnabled: List<DashboardCard> get() = entries.filter { card -> card.defaultEnabled }
    }
}

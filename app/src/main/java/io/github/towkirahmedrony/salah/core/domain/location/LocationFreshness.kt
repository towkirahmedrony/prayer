package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import java.time.Duration
import java.time.Instant

/**
 * How much the stored location should be trusted as a description of where the user is *now*.
 *
 * This exists to stop Salah making a specific false claim: that a saved position is the user's
 * current one. A position captured on a phone three days ago and a position the user typed by hand
 * are both "the location", but they mean different things, and the UI says which it is showing.
 */
enum class LocationFreshness {

    /**
     * The user chose this place — by typing coordinates, or by selecting an administrative area.
     *
     * Not "stale" and not "fresh": the user asserted a place, and that assertion does not decay with
     * time. A location chosen for the area the user lives in is as valid a year later as the day it
     * was chosen, which is exactly why a declared location is the offline fallback.
     */
    DECLARED,

    /** A device fix from within the last [FRESH_WINDOW]. */
    FRESH,

    /** A device fix from the last [RECENT_WINDOW] — usable, but not a live position. */
    RECENT,

    /** A device fix older than [RECENT_WINDOW]. Still a real position; simply not a current one. */
    STALE,

    /** A device fix with no recorded time, which cannot be aged. */
    UNKNOWN,
    ;

    /** True when the UI should offer to refresh rather than present this as current. */
    val shouldOfferRefresh: Boolean get() = this == RECENT || this == STALE || this == UNKNOWN

    companion object {

        /** A fix this recent is treated as a live position. */
        val FRESH_WINDOW: Duration = Duration.ofMinutes(10)

        /** Beyond this a fix is labelled as a saved position rather than a current one. */
        val RECENT_WINDOW: Duration = Duration.ofHours(24)

        /**
         * Ages [location] against [now].
         *
         * A timestamp in the future — a device clock that moved backwards, or a fix whose time came
         * from a provider with a skewed clock — is treated as [FRESH] rather than as an error: the
         * position itself is still real, and refusing to use it would break the feature for a
         * cosmetic reason.
         */
        fun of(location: LocationInfo, now: Instant): LocationFreshness {
            // Everything that is not an observation is a declaration: a typed coordinate and a selected
            // area are both statements about where the user is, and neither decays. Only a device fix
            // can go out of date.
            if (location.origin != LocationOrigin.DEVICE_GPS) return DECLARED
            val acquiredAt = location.acquiredAt ?: return UNKNOWN
            val age = Duration.between(acquiredAt, now)
            return when {
                age.isNegative -> FRESH
                age <= FRESH_WINDOW -> FRESH
                age <= RECENT_WINDOW -> RECENT
                else -> STALE
            }
        }
    }
}

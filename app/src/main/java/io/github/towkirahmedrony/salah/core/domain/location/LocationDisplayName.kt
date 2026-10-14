package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin

/**
 * The one place a location's display name is composed.
 *
 * **Why this exists.** Home, the Prayer screen, the Qibla screen, the location screen and the settings
 * detail all render the same stored location, and before this existed each of them rendered
 * `LocationInfo.label` — a string built once, at save time, by whichever path happened to write it.
 * That is how a city corporation ended up displayed as "Dhaka South City Corporation, Dhaka": the name
 * was an administrative label concatenated with its own district, repeated, and nobody owned the rule.
 * The rule now lives here, is pure, and every screen and every save path reads it, so the same location
 * cannot be described two ways.
 *
 * **Composed from facts, not from the stored string.** The name is derived from the stored *parts* — the
 * administrative hierarchy, the locality, the origin — rather than from [LocationInfo.label]. A record
 * written by an earlier build therefore gets the current policy applied to it the next time it is shown,
 * without a migration and without a rewrite, and a stored label can never drift away from the facts it
 * describes. [LocationInfo.label] is still written at save time (so a record is self-describing when
 * read without this code) and is still the fallback whenever the facts do not compose a name.
 *
 * **What it will not do.** It never invents a place. A name is only built from a locality the resolver
 * matched and verified against a district, or from the administrative hierarchy the dataset published;
 * when neither is present it returns the stored label unchanged, which is how a manually typed name, a
 * legacy "GPS location" and any record whose area is unknown keep saying exactly what they said.
 *
 * **Formatting policy**, deepest reliable name first:
 *
 * | Available | Result |
 * |---|---|
 * | locality verified in the resolved district | `Mirpur, Dhaka` |
 * | upazila/thana inside the district | `Savar, Dhaka` |
 * | city corporation whose name already contains the district | `Dhaka South City Corporation` |
 * | district only | `Dhaka` |
 * | a locality with no resolved district | `Mirpur` |
 * | a typed name | as typed |
 * | nothing usable | the stored label, unchanged |
 *
 * The district is dropped when the deeper name already contains it, which is the rule that removes the
 * duplicated "…Dhaka" from every one of the twelve city corporations: "Gazipur City Corporation,
 * Gazipur" is the same fact written twice. A division on its own cannot reach this function, because a
 * [BangladeshAreaRef] always carries a district — the dataset has no way to express a selection that
 * stopped at a division.
 */
object LocationDisplayName {

    /** The display name for a stored location, derived from the location's own parts. */
    fun of(location: LocationInfo): String = of(
        origin = location.origin,
        storedLabel = location.label,
        area = location.area,
        locality = location.locality,
    )

    /**
     * The display name for a location described by its parts.
     *
     * Used by both the save paths (which have the parts before a [LocationInfo] exists) and the display
     * paths (which have the stored location), so the name written and the name shown are produced by one
     * function rather than by two that must agree.
     *
     * @param storedLabel what to fall back to when the parts compose nothing: a typed name, an existing
     *   label, or the device-fix placeholder.
     */
    fun of(
        origin: LocationOrigin,
        storedLabel: String,
        area: BangladeshAreaRef?,
        locality: BangladeshLocalityRef?,
    ): String {
        val typed = storedLabel.trim()

        // A manually typed name is the user's own words for a place Salah cannot know. Composing over it
        // would replace what they wrote with what the app guessed, which is the one case where the
        // stored label outranks every fact.
        if (origin == LocationOrigin.MANUAL_ENTRY) return typed

        val deepest = locality?.name?.takeIf { it.isNotBlank() }
            ?: area?.upazilaName?.takeIf { it.isNotBlank() }
            ?: area?.districtName?.takeIf { it.isNotBlank() }
            ?: return typed

        val district = area?.districtName?.takeIf { it.isNotBlank() }
        return joinDistinct(deepest, district)
    }

    /**
     * `Mirpur, Dhaka` — the deeper name first, with the district after it, unless the deeper name already
     * says it.
     *
     * Compared on letters and digits only, so "Dhaka South City Corporation" is recognised as already
     * containing "Dhaka" and "Dhaka" is not repeated, while "Dhamrai" is correctly *not* treated as
     * containing it.
     */
    private fun joinDistinct(deepest: String, district: String?): String {
        if (district == null || district.isBlank()) return deepest
        val deepestKey = key(deepest)
        val districtKey = key(district)
        if (districtKey.isEmpty() || deepestKey.contains(districtKey)) return deepest
        return "$deepest, $district"
    }

    /** Case-, space- and punctuation-insensitive form, for deciding whether one name contains another. */
    private fun key(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}

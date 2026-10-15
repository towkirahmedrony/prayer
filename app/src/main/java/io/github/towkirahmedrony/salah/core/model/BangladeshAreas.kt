package io.github.towkirahmedrony.salah.core.model

/**
 * The administrative levels Salah can identify a location by.
 *
 * Three levels, because that is what the bundled dataset provides and what a user in Bangladesh
 * recognises: a division, the district inside it, and the upazila (or, for a city, the thana) inside
 * that. A fourth level — the union — exists in Bangladesh's administration but is not part of Salah's
 * location system: it is a rural unit with no published representative coordinate in the adopted
 * dataset, so offering it would mean inventing coordinates rather than selecting an area.
 */
enum class BangladeshAreaLevel {
    DIVISION,
    DISTRICT,
    UPAZILA,
    ;

    /** The level one step below this one, or `null` for the deepest level. */
    val child: BangladeshAreaLevel?
        get() = when (this) {
            DIVISION -> DISTRICT
            DISTRICT -> UPAZILA
            UPAZILA -> null
        }
}

/**
 * One administrative area, with the representative coordinate published for it.
 *
 * **What [latitude] and [longitude] are, and what they are not.** They are the area's *published
 * centre* — the point OCHA publishes with the boundary it defines — not the user's position and not
 * necessarily the area's administrative office or its population-weighted centre. Selecting an area
 * therefore resolves to a real, creditable coordinate, but the resulting prayer times are the times
 * for that centre point, which may be minutes away from the user's own position. `docs/LOCATION_SYSTEM.md`
 * states that limitation, and the UI labels a selected area as a selected area rather than a GPS fix.
 *
 * [name] is the dataset's English name and is always present. [banglaName] is joined from a second,
 * MIT-licensed source and is `null` for the units where the two sources spell the name differently
 * enough that no match could be made; those units display their English name rather than a guessed
 * transliteration. *Which* of the two names a screen shows is decided by
 * `core.domain.location.nameIn(area, language)` against the interface language — the two names are
 * never concatenated, because a picker set to one language should read as that language.
 */
data class BangladeshArea(
    val id: String,
    /** The id of the containing area: a division's parent is the country, a district's is its division. */
    val parentId: String,
    val level: BangladeshAreaLevel,
    val name: String,
    val banglaName: String?,
    val latitude: Double,
    val longitude: Double,
) {
}

/**
 * Which administrative areas a stored location belongs to.
 *
 * Names travel with the ids on purpose. The dataset is versioned and can be replaced by a later
 * build, so a stored location keeps the names it was chosen with and the user sees the area they
 * picked even before the new dataset has been consulted — and even if a later dataset no longer
 * contains the id, which is what lets the app recognise that case and ask again instead of showing
 * an id or a blank.
 *
 * [upazilaId] is nullable because an area may legitimately be resolved only as far as a district:
 * a stored location written before this dataset existed has no area at all, and a GPS fix at the edge
 * of the resolvable range is reported as a district-level estimate rather than an invented upazila.
 */
data class BangladeshAreaRef(
    val divisionId: String,
    val divisionName: String,
    val districtId: String,
    val districtName: String,
    val upazilaId: String? = null,
    val upazilaName: String? = null,
) {
    /** The deepest selected id: the upazila when there is one, otherwise the district. */
    val deepestId: String get() = upazilaId ?: districtId

    /** A short human-readable label, deepest first, e.g. `Bandarban Sadar, Bandarban`. */
    val label: String
        get() = if (upazilaName.isNullOrBlank()) districtName else "$upazilaName, $districtName"

    /** The full hierarchy, for a details section. */
    val hierarchyLabel: String
        get() = listOfNotNull(upazilaName?.takeIf { it.isNotBlank() }, districtName, divisionName)
            .joinToString(", ")

    companion object {
        /** Builds a reference from the areas the user selected or that were resolved for a fix. */
        fun of(
            division: BangladeshArea,
            district: BangladeshArea,
            upazila: BangladeshArea?,
        ): BangladeshAreaRef = BangladeshAreaRef(
            divisionId = division.id,
            divisionName = division.name,
            districtId = district.id,
            districtName = district.name,
            upazilaId = upazila?.id,
            upazilaName = upazila?.name,
        )
    }
}

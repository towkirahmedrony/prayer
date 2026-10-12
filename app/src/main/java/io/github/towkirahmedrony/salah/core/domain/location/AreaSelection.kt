package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * What the user has chosen so far, as three ids.
 *
 * The rules that make a hierarchy valid are here rather than in the screen, because "a district must
 * belong to the chosen division" is a fact about the data, not about a dropdown:
 *
 *  - choosing a division clears the district and the upazila;
 *  - choosing a district requires it to belong to the chosen division, and clears the upazila;
 *  - choosing an upazila requires it to belong to the chosen district.
 *
 * An id that does not satisfy those rules is **ignored** rather than stored, so an invalid
 * combination cannot exist even for one frame — the state is the guard, and the UI cannot save what
 * the state will not hold.
 */
data class AreaSelection(
    val divisionId: String? = null,
    val districtId: String? = null,
    val upazilaId: String? = null,
) {

    /** The deepest level chosen, which is what the UI highlights. */
    val deepestLevel: BangladeshAreaLevel?
        get() = when {
            upazilaId != null -> BangladeshAreaLevel.UPAZILA
            districtId != null -> BangladeshAreaLevel.DISTRICT
            divisionId != null -> BangladeshAreaLevel.DIVISION
            else -> null
        }

    /**
     * True when a location can be resolved from this selection.
     *
     * All three levels are required to *save* a manually selected location: the upazila is the unit
     * whose published coordinate is used, so stopping at a district would mean saving a district-wide
     * coordinate and calling it the user's area, which is a precision claim Salah does not have.
     */
    val isComplete: Boolean
        get() = divisionId != null && districtId != null && upazilaId != null

    /** The divisions to offer. Always all of them; there is no country picker to filter by. */
    fun divisions(dataset: BangladeshLocationDataset): List<BangladeshArea> = dataset.divisions

    /** The districts to offer: those of the chosen division, and none before one is chosen. */
    fun districts(dataset: BangladeshLocationDataset): List<BangladeshArea> =
        divisionId?.let { dataset.districtsOf(it) }.orEmpty()

    /** The upazilas/thanas to offer: those of the chosen district, and none before one is chosen. */
    fun upazilas(dataset: BangladeshLocationDataset): List<BangladeshArea> =
        districtId?.let { dataset.upazilasOf(it) }.orEmpty()

    /** Chooses a division. Unknown ids are ignored; the district and upazila are cleared. */
    fun selectDivision(id: String, dataset: BangladeshLocationDataset): AreaSelection {
        val division = dataset.area(id) ?: return this
        if (division.level != BangladeshAreaLevel.DIVISION) return this
        if (division.id == divisionId) return this
        return AreaSelection(divisionId = division.id)
    }

    /** Chooses a district inside the chosen division. An unrelated district is ignored. */
    fun selectDistrict(id: String, dataset: BangladeshLocationDataset): AreaSelection {
        val parent = divisionId ?: return this
        val district = dataset.area(id) ?: return this
        if (district.parentId != parent) return this
        if (district.id == districtId) return this
        return copy(districtId = district.id, upazilaId = null)
    }

    /** Chooses an upazila inside the chosen district. An unrelated upazila is ignored. */
    fun selectUpazila(id: String, dataset: BangladeshLocationDataset): AreaSelection {
        val parent = districtId ?: return this
        val upazila = dataset.area(id) ?: return this
        if (upazila.parentId != parent) return this
        if (upazila.id == upazilaId) return this
        return copy(upazilaId = upazila.id)
    }

    /** Clears the deepest level, for a "change" affordance in the UI. */
    fun clearUpazila(): AreaSelection = copy(upazilaId = null)

    /** Clears the district and the upazila together. */
    fun clearDistrict(): AreaSelection = copy(districtId = null, upazilaId = null)

    /**
     * The hierarchy this selection describes, or `null` when it is incomplete or its ids are unknown
     * to [dataset].
     *
     * Saving goes through this and requires a non-null result, so a location can never be written from
     * a partial selection.
     */
    fun reference(dataset: BangladeshLocationDataset): BangladeshAreaRef? {
        val upazilaId = upazilaId ?: return null
        val districtId = districtId ?: return null
        val divisionId = divisionId ?: return null

        val division = dataset.area(divisionId) ?: return null
        val district = dataset.area(districtId) ?: return null
        val upazila = dataset.area(upazilaId) ?: return null
        if (district.parentId != division.id || upazila.parentId != district.id) return null
        return BangladeshAreaRef.of(division = division, district = district, upazila = upazila)
    }

    /**
     * The area whose published coordinate a manual selection saves, or `null` when the selection is
     * incomplete. The upazila is the deepest level, so its coordinate is the one that gets saved.
     */
    fun resolvedArea(dataset: BangladeshLocationDataset): BangladeshArea? =
        reference(dataset)?.let { dataset.area(it.upazilaId) }

    companion object {
        /** An empty selection: nothing chosen yet. */
        val EMPTY: AreaSelection = AreaSelection()
    }
}

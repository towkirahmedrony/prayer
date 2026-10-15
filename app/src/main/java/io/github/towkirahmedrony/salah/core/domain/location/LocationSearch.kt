package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshLocality
import java.text.Normalizer

/** Which part of the dataset a [LocationSearchResult] points at. */
enum class LocationSearchLevel {
    DIVISION,
    DISTRICT,
    UPAZILA,
    LOCALITY,
    ;

    companion object {
        fun of(level: BangladeshAreaLevel): LocationSearchLevel = when (level) {
            BangladeshAreaLevel.DIVISION -> DIVISION
            BangladeshAreaLevel.DISTRICT -> DISTRICT
            BangladeshAreaLevel.UPAZILA -> UPAZILA
        }
    }
}

/**
 * One location the query matched, already rendered for the language the search ran in.
 *
 * Carries ids rather than a pre-built selection so applying a result stays the job of
 * [AreaSelection] — the same guard the dropdowns use — and an id set that does not form a valid
 * hierarchy cannot be produced here either.
 *
 * [contextNames] is the administrative context shallow-to-deep (`division → district`), empty for a
 * division: it is what lets "Gazipur" be told from "Gazipur Sadar" and either of them from the
 *locality of the same name, because two areas may share a name but not a parentage.
 */
data class LocationSearchResult(
    /** Stable identity of this result, unique within one search. Used to deduplicate and to key lists. */
    val key: String,
    val level: LocationSearchLevel,
    /** The matched name, in the language the search ran in. */
    val displayName: String,
    /** Ancestors of the matched place, shallow to deep, in the searched language. */
    val contextNames: List<String>,
    val divisionId: String?,
    val districtId: String?,
    val upazilaId: String?,
)

/**
 * Language-aware search over the bundled dataset — the local files, with no network call.
 *
 * **What is searched.** Every division, district and upazila/thana in `bd_areas.tsv` and every named
 * locality in `bd_localities.tsv`: 8 + 64 + 507 + 1 216 entries, all parsed in memory, so a query is a
 * single linear scan of about 1 800 short strings. Nothing is fetched, nothing is indexed on disk and
 * nothing is held beyond the scan — the local dataset already satisfies the requirement, so no search
 * service is involved.
 *
 * **Which names match.** The name *in the requested language* first (`[LocationNameLanguage]`), then,
 * as a strictly lower-priority fallback, the name in the other language. In Bangla mode "কুমিল্লা"
 * therefore matches the Bangla name the dataset joined for Cumilla, and typing "Cumilla" in Bangla
 * mode still finds it — displayed in Bangla, because display never follows the query, it follows the
 * language. An area with no Bangla name has no same-language name at all, so its English name is
 * matched as a fallback there, while under English it is the primary: one band or the other, never
 * both, and never a second copy of the row.
 *
 * **Ranking** is exact match, then starts-with, then partial, and every primary-language band ranks
 * above every alternate-language band — a place whose name *begins* with the query outranks a place
 * that happens to contain it, whatever language each matched in. Results are deduplicated by
 * [LocationSearchResult.key] and capped at [MAX_RESULTS] so a one- or two-character query returns the
 * most specific matches rather than half the country.
 *
 * **Normalization** (`[normalize]`) is applied to the query and to every candidate name identically:
 * Unicode NFC, zero-width joiners and similar invisible characters stripped (a Bangla keyboard inserts
 * them freely), the three precomposed Bengali nukta letters folded to their base-letter-plus-nukta
 * spelling so both keyboard forms of ড়/ঢ়/য় compare equal, whitespace collapsed, ends trimmed and
 * case folded. It is deliberately not a transliteration scheme: names are never converted between
 * scripts, only compared within one.
 */
object LocationSearch {

    /**
     * The most results one query returns.
     *
     * A single character can match most of the dataset, and a wall of rows is not a result list: the
     * cap keeps short queries useful by keeping the best-ranked ones, and 30 is enough to reach every
     * sensible spelling of a real place while scrolling stays short.
     */
    const val MAX_RESULTS: Int = 30

    /** Ranking bands. Each language gets three; the alternate language starts after the primary's. */
    private const val RANK_EXACT = 0
    private const val RANK_PREFIX = 1
    private const val RANK_CONTAINS = 2
    private const val ALTERNATE_BAND = 3

    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The entries of [dataset] matching [query], best first, names in [language].
     *
     * An empty list means "nothing matched" or "nothing to match" (a blank query); a dataset that
     * could not be read has no entries to scan and returns the same empty list, so the caller decides
     * how to say which of the two happened.
     */
    fun search(
        dataset: BangladeshLocationDataset,
        query: String,
        language: LocationNameLanguage,
    ): List<LocationSearchResult> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<Int, LocationSearchResult>>()

        for (area in dataset.areas) {
            val rank = rank(area.nameInOrNull(language), needle, primary = true)
                ?: rank(area.alternateNameIn(language), needle, primary = false)
                ?: continue
            scored += rank to areaResult(area, dataset, language)
        }

        for (locality in dataset.localities) {
            // The locality layer ships English names only (docs/LOCATION_SYSTEM.md §5), so under English
            // the stored name is the primary name and under Bangla it is a fallback — the same two bands
            // an area with no Bangla name falls into, for the same reason: the name is not in the
            // requested language.
            val primary = language == LocationNameLanguage.ENGLISH
            val rank = rank(locality.name, needle, primary = primary) ?: continue
            scored += rank to localityResult(locality, dataset, language)
        }

        return scored
            .sortedWith(
                compareBy({ it.first }, { it.second.level.ordinal }, { it.second.displayName }, { it.second.key }),
            )
            // Deduplicated by key after ranking so the cap below counts distinct rows: the locality layer
            // may hold one name under two kinds in the same district, and both would render as the same
            // row — an id, a name and a parentage that cannot be told apart.
            .distinctBy { it.second.key }
            .take(MAX_RESULTS)
            .map { it.second }
    }

    /**
     * The query-and-name normalization described on [LocationSearch], applied identically to both sides
     * of every comparison. Public so tests state the rule directly instead of inferring it.
     */
    fun normalize(value: String): String {
        val composed = Normalizer.normalize(value, Normalizer.Form.NFC)
        val visible = ZERO_WIDTH.replace(composed, "")
        val folded = visible
            // A Bengali keyboard may produce either the precomposed letter or the base letter plus
            // nukta; folding the three affected letters to one spelling makes both spellings compare
            // equal. Nothing else is rewritten: ড (\u09A1) and ড় stay different letters.
            .replace("\u09DC", "\u09A1\u09BC")
            .replace("\u09DD", "\u09A2\u09BC")
            .replace("\u09DF", "\u09AF\u09BC")
        return WHITESPACE.replace(folded, " ").trim().lowercase()
    }

    /** The ranking band of [name] against the normalized [needle], or `null` when it does not match. */
    private fun rank(name: String?, needle: String, primary: Boolean): Int? {
        if (name == null) return null
        val candidate = normalize(name)
        if (candidate.isEmpty()) return null
        val base = if (primary) 0 else ALTERNATE_BAND
        return when {
            candidate == needle -> base + RANK_EXACT
            candidate.startsWith(needle) -> base + RANK_PREFIX
            candidate.contains(needle) -> base + RANK_CONTAINS
            else -> null
        }
    }

    /** An administrative area's result, with its ancestors as context in [language]. */
    private fun areaResult(
        area: BangladeshArea,
        dataset: BangladeshLocationDataset,
        language: LocationNameLanguage,
    ): LocationSearchResult {
        val parent = dataset.area(area.parentId)
        // Read from the level down rather than from `parentId` alone: an upazila's parent is its district,
        // so the division has to be reached through that district, and an area that *is* the district is
        // its own district — which is what makes a district result carry the id a selection needs.
        val district = when (area.level) {
            BangladeshAreaLevel.DIVISION -> null
            BangladeshAreaLevel.DISTRICT -> area
            BangladeshAreaLevel.UPAZILA -> parent?.takeIf { it.level == BangladeshAreaLevel.DISTRICT }
        }
        val division = when (area.level) {
            BangladeshAreaLevel.DIVISION -> area
            BangladeshAreaLevel.DISTRICT -> parent?.takeIf { it.level == BangladeshAreaLevel.DIVISION }
            BangladeshAreaLevel.UPAZILA -> district?.parentId?.let { dataset.area(it) }
                ?.takeIf { it.level == BangladeshAreaLevel.DIVISION }
        }
        // The ancestors *above* the matched place, shallow to deep — a division has none, a district's is
        // its division, an upazila's is its division and then its district — because the row already shows
        // the matched name itself and repeating it would read as a second place.
        val ancestors = when (area.level) {
            BangladeshAreaLevel.DIVISION -> emptyList<String>()
            BangladeshAreaLevel.DISTRICT -> listOfNotNull(division?.nameIn(language))
            BangladeshAreaLevel.UPAZILA -> listOfNotNull(
                division?.nameIn(language),
                district?.nameIn(language),
            )
        }
        val context = contextOf(ancestors)
        return LocationSearchResult(
            key = "area:${area.id}",
            level = LocationSearchLevel.of(area.level),
            displayName = area.nameIn(language),
            contextNames = context,
            divisionId = division?.id,
            districtId = district?.id,
            upazilaId = area.takeIf { it.level == BangladeshAreaLevel.UPAZILA }?.id,
        )
    }

    /**
     * A locality's result. A locality belongs to a verified district but is not an administrative unit,
     * so it carries no upazila: selecting it fills in division and district, and the upazila — the level
     * the dataset cannot express for it — is still the user's to choose.
     */
    private fun localityResult(
        locality: BangladeshLocality,
        dataset: BangladeshLocationDataset,
        language: LocationNameLanguage,
    ): LocationSearchResult {
        val district = dataset.area(locality.districtId)
        val division = district?.parentId?.let { dataset.area(it) }
        return LocationSearchResult(
            key = "locality:${locality.districtId}:${locality.name}",
            level = LocationSearchLevel.LOCALITY,
            // The layer has no Bangla name to show, so the stored (English) name is the display name in
            // both languages — a stated coverage limitation, not a translation the dataset lacks.
            displayName = locality.name,
            contextNames = contextOf(
                ancestors = listOfNotNull(
                    division?.nameIn(language),
                    district?.nameIn(language),
                ),
            ),
            divisionId = division?.id,
            districtId = district?.id,
            upazilaId = null,
        )
    }

    /**
     * Ancestors shallow-to-deep with a repeated name collapsed — the Dhaka division and the Dhaka
     * district are one word in a row, and "Dhaka → Dhaka" would read as two places.
     */
    private fun contextOf(ancestors: List<String>): List<String> =
        ancestors.fold(emptyList()) { kept, next ->
            if (kept.isNotEmpty() && normalize(kept.last()) == normalize(next)) kept else kept + next
        }
}

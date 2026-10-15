package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * The language a location's *name* should be read and matched in.
 *
 * This is not a UI-string language: labels, buttons and messages come from the string resources,
 * resolved through the composition's `LocalResources`. It is the language of the **data** — the
 * bundled dataset stores an English name for every area and, for most of them, a Bangla one joined
 * from a second source — and it is what decides which of those two names a dropdown shows and a
 * search matches against.
 *
 * The screens derive the value from the resources the app is currently rendering with, so the two
 * can never disagree: `AppLanguage.BANGLA` and a Bangla device locale under `AppLanguage.SYSTEM`
 * both resolve to [BANGLA] because both render the same resources.
 */
enum class LocationNameLanguage {

    /** The dataset's English names, which every area always has. */
    ENGLISH,

    /** The dataset's Bangla names, falling back to the English name where no Bangla name exists. */
    BANGLA,
    ;

    /** The language searched when [this] one does not match — the alternate-language fallback. */
    val alternate: LocationNameLanguage
        get() = if (this == ENGLISH) BANGLA else ENGLISH
}

/**
 * The name to display [this area] in [language].
 *
 * Bangla prefers [BangladeshArea.banglaName] and falls back to the English name for the units where
 * no Bangla name could be joined — 69 of the 507 upazilas are in that state, and showing the English
 * name is the honest answer rather than a transliteration Salah invented. English always shows
 * [BangladeshArea.name], which is the dataset's own English name.
 */
fun BangladeshArea.nameIn(language: LocationNameLanguage): String = when (language) {
    LocationNameLanguage.BANGLA -> banglaName?.takeIf { it.isNotBlank() } ?: name
    LocationNameLanguage.ENGLISH -> name
}

/**
 * The area's name **in** [language], or `null` when the dataset has no name in that language.
 *
 * Unlike [nameIn] this does not fall back, because it answers a different question: not "what should
 * this be shown as" but "does this area have a name in the requested language". It is the
 * *primary-match* name — a match found through it is a same-language match; everything else is a
 * fallback (see [alternateNameIn]).
 */
fun BangladeshArea.nameInOrNull(language: LocationNameLanguage): String? = when (language) {
    LocationNameLanguage.ENGLISH -> name
    LocationNameLanguage.BANGLA -> banglaName?.takeIf { it.isNotBlank() }
}

/**
 * This area's name in the *other* language, for fallback matching, or `null` when it has none.
 *
 * Under [LocationNameLanguage.ENGLISH] that is the Bangla name, when one was joined. Under
 * [LocationNameLanguage.BANGLA] it is always the English name — including for an area with no Bangla
 * name, whose display name already *is* the English one: the English name is then a fallback rather
 * than the primary, which keeps "matched in the requested language" and "matched as a fallback" two
 * distinct bands no matter which of the two spellings the dataset holds.
 */
fun BangladeshArea.alternateNameIn(language: LocationNameLanguage): String? = when (language) {
    LocationNameLanguage.ENGLISH -> banglaName?.takeIf { it.isNotBlank() }
    LocationNameLanguage.BANGLA -> name
}

/**
 * The name of [areaId] in [language] as it should be shown for a *stored* reference, or `null`.
 *
 * Stored locations carry the names they were saved with (`BangladeshAreaRef`), so a record written by
 * an earlier build — or one whose id the current dataset no longer contains — still has a name to
 * show. The dataset's own name wins when it has one, which is what lets a saved location render in
 * Bangla even though it was stored with English names, with no migration.
 */
fun locationNameOf(
    areaId: String?,
    storedName: String?,
    dataset: BangladeshLocationDataset?,
    language: LocationNameLanguage,
): String? {
    val fromDataset = areaId?.let { dataset?.area(it) }?.nameIn(language)
    return fromDataset ?: storedName?.takeIf { it.isNotBlank() }
}

/**
 * [reference]'s hierarchy in [language], deepest first — `upazila, district, division` — the same
 * composition `BangladeshAreaRef.hierarchyLabel` builds from the stored names, resolved through
 * [dataset] so the current language's names are used when the dataset still knows the ids.
 */
fun hierarchyOf(
    reference: BangladeshAreaRef,
    dataset: BangladeshLocationDataset?,
    language: LocationNameLanguage,
): String = listOfNotNull(
    locationNameOf(reference.upazilaId, reference.upazilaName, dataset, language),
    locationNameOf(reference.districtId, reference.districtName, dataset, language),
    locationNameOf(reference.divisionId, reference.divisionName, dataset, language),
).joinToString(", ")

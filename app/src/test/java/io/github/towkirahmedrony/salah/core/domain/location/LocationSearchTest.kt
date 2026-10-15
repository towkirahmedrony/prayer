package io.github.towkirahmedrony.salah.core.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual picker's search: what matches, in which language, in what order, and with what context.
 *
 * The search is a pure function of the bundled dataset, so every case runs against a miniature of that
 * dataset — three divisions' worth of shape in two, three districts, five upazilas and three
 * localities — parsed by the same parser the shipped files go through. The fixture deliberately
 * contains the awkward cases: an area with no Bangla name, a district that shares its division's name,
 * a name shared by a district and a locality in a *different* district, a name that is a prefix of
 * another, and a Bangla name containing the precomposed nukta letter `য়`.
 */
class LocationSearchTest {

    // --- Matching, per language ------------------------------------------------

    @Test
    fun `an English query matches each level by its English name`() {
        val dataset = dataset()

        // The division and its same-named district both match exactly; level order puts the division
        // first, and the upazila whose name continues the phrase follows as a starts-with match.
        assertEquals(
            listOf("area:BD10", "area:BD1003", "area:BD1003001"),
            keys(LocationSearch.search(dataset, "Khulna", ENGLISH)),
        )
        assertEquals(
            listOf("area:BD1001001"),
            keys(LocationSearch.search(dataset, "Kumarkhali", ENGLISH)),
        )
    }

    @Test
    fun `a Bangla query matches each level by its Bangla name`() {
        val dataset = dataset()

        assertEquals(
            listOf("area:BD10", "area:BD1003", "area:BD1003001"),
            keys(LocationSearch.search(dataset, "খুলনা", BANGLA)),
        )
        assertEquals(
            listOf("area:BD1001", "area:BD1001003"),
            keys(LocationSearch.search(dataset, "কুষ্টিয়া", BANGLA)),
        )
        assertEquals(
            listOf("area:BD1001001"),
            keys(LocationSearch.search(dataset, "কুমারখালী", BANGLA)),
        )
    }

    @Test
    fun `partial names match in both languages`() {
        val dataset = dataset()

        assertEquals(listOf("area:BD1001001"), keys(LocationSearch.search(dataset, "kuma", ENGLISH)))
        assertEquals(listOf("area:BD1001001"), keys(LocationSearch.search(dataset, "কুমার", BANGLA)))
    }

    @Test
    fun `case and surrounding whitespace are ignored`() {
        val dataset = dataset()

        assertEquals(
            listOf("area:BD1001001"),
            keys(LocationSearch.search(dataset, "   KuMarKhali   ", ENGLISH)),
        )
        assertEquals(
            listOf("area:BD1001001"),
            keys(LocationSearch.search(dataset, "  kumarkhali", ENGLISH)),
        )
    }

    @Test
    fun `repeated spaces inside a query are collapsed`() {
        val dataset = dataset()

        assertEquals(
            listOf("area:BD1002001"),
            keys(LocationSearch.search(dataset, "sherpur   sadar", ENGLISH)),
        )
        assertEquals(
            listOf("area:BD1002001"),
            keys(LocationSearch.search(dataset, "  শেরপুর    সদর ", BANGLA)),
        )
    }

    @Test
    fun `a name with no Bangla spelling is still findable under Bangla`() {
        val dataset = dataset()

        // Bheramara has no joined Bangla name, so Bangla display falls back to the English one and the
        // English name is matched as a *fallback* — found either way, displayed exactly as the dataset
        // spells it, with no transliteration invented and no second copy of the row.
        val results = LocationSearch.search(dataset, "Bheramara", BANGLA)
        assertEquals(listOf("area:BD1001002"), keys(results))
        assertEquals("Bheramara", results.single().displayName)
    }

    @Test
    fun `an English query under Bangla is matched as a fallback and displayed in Bangla`() {
        val dataset = dataset()

        // Typed in English while the app is in Bangla. Every match comes through the fallback (English)
        // band, ordered by strength and then by level; the district's *result* is rendered — name and
        // context — in Bangla, because display follows the language and never the query.
        val results = LocationSearch.search(dataset, "Kushtia", BANGLA)
        assertEquals(
            listOf("area:BD1001", "locality:BD1001:Kushtia", "area:BD1001003", "locality:BD1001:New Kushtia"),
            keys(results),
        )
        val district = results.first()
        assertEquals("কুষ্টিয়া", district.displayName)
        assertEquals(listOf("খুলনা"), district.contextNames)
    }

    @Test
    fun `the Bangla name matches as a fallback under English too`() {
        val dataset = dataset()

        val results = LocationSearch.search(dataset, "কুমারখালী", ENGLISH)
        assertEquals(listOf("area:BD1001001"), keys(results))
        assertEquals("Kumarkhali", results.single().displayName)
    }

    // --- Ranking --------------------------------------------------------------

    @Test
    fun `exact matches rank above starts-with matches, which rank above partial matches`() {
        val dataset = dataset()

        val results = LocationSearch.search(dataset, "kushtia", ENGLISH)
        assertEquals(
            "the exact district and the exact locality first (level order), then the upazila whose " +
                "name starts with the query, then the locality that merely contains it",
            listOf("area:BD1001", "locality:BD1001:Kushtia", "area:BD1001003", "locality:BD1001:New Kushtia"),
            keys(results),
        )
    }

    // --- Context and identity --------------------------------------------------

    @Test
    fun `a result carries its administrative context in the searched language`() {
        val dataset = dataset()

        val english = LocationSearch.search(dataset, "Kumarkhali", ENGLISH).single()
        assertEquals(listOf("Khulna", "Kushtia"), english.contextNames)
        assertEquals("BD10", english.divisionId)
        assertEquals("BD1001", english.districtId)
        assertEquals("BD1001001", english.upazilaId)

        val bangla = LocationSearch.search(dataset, "কুমারখালী", BANGLA).single()
        assertEquals(listOf("খুলনা", "কুষ্টিয়া"), bangla.contextNames)

        // A division has no ancestors, so its context is empty rather than a repeated name.
        val division = LocationSearch.search(dataset, "Mymensingh", ENGLISH).single()
        assertEquals(emptyList<String>(), division.contextNames)
    }

    @Test
    fun `an ancestor name repeated by the next level appears once`() {
        val dataset = dataset()

        // The Khulna division and the Khulna district inside it would print as "Khulna → Khulna";
        // the shared word is one place, not two.
        val upazila = LocationSearch.search(dataset, "Khulna Sadar", ENGLISH).single()
        assertEquals(listOf("Khulna"), upazila.contextNames)
    }

    @Test
    fun `a locality matches with its district context and without an upazila`() {
        val dataset = dataset()

        val locality = LocationSearch.search(dataset, "New Kushtia", ENGLISH).single()
        assertEquals(LocationSearchLevel.LOCALITY, locality.level)
        // Division then district, shallow to deep, like every other row's context.
        assertEquals(listOf("Khulna", "Kushtia"), locality.contextNames)
        assertEquals("BD10", locality.divisionId)
        assertEquals("BD1001", locality.districtId)
        assertEquals(null, locality.upazilaId)
    }

    @Test
    fun `a name shared by two places is returned once each and told apart by context`() {
        val dataset = dataset()

        val results = LocationSearch.search(dataset, "Sherpur", ENGLISH)
        assertEquals(
            listOf("area:BD1002", "locality:BD1001:Sherpur", "area:BD1002001"),
            keys(results),
        )
        val district = results.first { it.level == LocationSearchLevel.DISTRICT }
        val locality = results.first { it.level == LocationSearchLevel.LOCALITY }
        assertEquals(listOf("Khulna"), district.contextNames)
        assertEquals(listOf("Khulna", "Kushtia"), locality.contextNames)
    }

    @Test
    fun `no query returns the same entry twice`() {
        val dataset = dataset()

        val keys = keys(LocationSearch.search(dataset, "e", ENGLISH))
        assertEquals("duplicate results: $keys", keys.toSet().size, keys.size)
    }

    // --- Empty and capped states ------------------------------------------------

    @Test
    fun `a blank or whitespace-only query returns nothing`() {
        val dataset = dataset()

        assertTrue(LocationSearch.search(dataset, "", ENGLISH).isEmpty())
        assertTrue(LocationSearch.search(dataset, "   ", BANGLA).isEmpty())
    }

    @Test
    fun `an unmatched query returns nothing rather than everything`() {
        val dataset = dataset()

        assertTrue(LocationSearch.search(dataset, "zzzz", ENGLISH).isEmpty())
        assertTrue(LocationSearch.search(dataset, "zzzz", BANGLA).isEmpty())
    }

    @Test
    fun `a broad query is capped at the result limit`() {
        val dataset = dataset(
            extraAreas = (1..40).joinToString("\n") { index ->
                "UPAZILA\tBD10019$index\tBD1001\tArea $index\t\t23.90\t89.12"
            },
        )

        val results = LocationSearch.search(dataset, "area", ENGLISH)
        assertEquals(LocationSearch.MAX_RESULTS, results.size)
    }

    // --- Normalization ---------------------------------------------------------

    @Test
    fun `normalization folds case, spaces and zero-width joiners`() {
        assertEquals("kumarkhali", LocationSearch.normalize("  KuMarKhali  "))
        assertEquals("kumarkhali", LocationSearch.normalize("kumar\u200Ckhali"))
        assertEquals("kusa hta", LocationSearch.normalize("kusa\u200D  hta"))
        assertEquals("sherpur sadar", LocationSearch.normalize("sherpur    sadar"))
    }

    @Test
    fun `normalization folds both spellings of the Bengali nukta letters`() {
        // The precomposed letters ড় ঢ় য় and the base letter plus nukta are the same word written two
        // ways; a query typed on either keyboard must match a dataset stored with either.
        assertEquals(LocationSearch.normalize("\u09A1\u09BC"), LocationSearch.normalize("\u09DC"))
        assertEquals(LocationSearch.normalize("\u09A2\u09BC"), LocationSearch.normalize("\u09DD"))
        assertEquals(LocationSearch.normalize("\u09AF\u09BC"), LocationSearch.normalize("\u09DF"))
    }

    @Test
    fun `a Bangla name containing the precomposed nukta letter is found either way`() {
        val dataset = dataset()

        // ময়মনসিংহ, stored precomposed (য় = U+09DF), typed as য + nukta (U+09AF U+09BC).
        val precomposed = "\u09AE\u09DF\u09AE\u09A8\u09B8\u09BF\u0982\u09B9"
        val typedWithNukta = "\u09AE\u09AF\u09BC\u09AE\u09A8\u09B8\u09BF\u0982\u09B9"

        assertEquals(listOf("area:BD20"), keys(LocationSearch.search(dataset, precomposed, BANGLA)))
        assertEquals(listOf("area:BD20"), keys(LocationSearch.search(dataset, typedWithNukta, BANGLA)))
    }

    // --- Fixture ----------------------------------------------------------------

    private val ENGLISH = LocationNameLanguage.ENGLISH
    private val BANGLA = LocationNameLanguage.BANGLA

    private fun keys(results: List<LocationSearchResult>): List<String> = results.map { it.key }

    private fun dataset(extraAreas: String = ""): BangladeshLocationDataset {
        val areas = if (extraAreas.isEmpty()) AREAS else "$AREAS\n$extraAreas"
        return BangladeshLocationDataset.parse(
            version = "test",
            areasText = areas,
            boundaryText = SQUARE_BOUNDARY,
            localitiesText = LOCALITIES,
        )
    }

    private companion object {
        val AREAS: String = listOf(
            "DIVISION\tBD10\tBD\tKhulna\tখুলনা\t22.80\t89.30",
            "DIVISION\tBD20\tBD\tMymensingh\tময়মনসিংহ\t24.85\t90.40",
            "DISTRICT\tBD1001\tBD10\tKushtia\tকুষ্টিয়া\t23.90\t89.12",
            "DISTRICT\tBD1002\tBD10\tSherpur\tশেরপুর\t24.50\t89.40",
            // A district that carries its division's name — the repeated-context case.
            "DISTRICT\tBD1003\tBD10\tKhulna\tখুলনা\t22.80\t89.35",
            "UPAZILA\tBD1001001\tBD1001\tKumarkhali\tকুমারখালী\t23.86\t89.24",
            // No Bangla name: the dataset could not join one, and none is invented.
            "UPAZILA\tBD1001002\tBD1001\tBheramara\t\t23.99\t88.97",
            // A name that begins with another entry's name, and one that another level shares.
            "UPAZILA\tBD1001003\tBD1001\tKushtia Sadar\tকুষ্টিয়া সদর\t23.92\t89.15",
            "UPAZILA\tBD1002001\tBD1002\tSherpur Sadar\tশেরপুর সদর\t24.60\t89.45",
            "UPAZILA\tBD1003001\tBD1003\tKhulna Sadar\tখুলনা সদর\t22.85\t89.40",
        ).joinToString("\n")

        val LOCALITIES: String = listOf(
            "QUARTER\tBD1001\tSherpur\t23.910\t89.130",
            "QUARTER\tBD1001\tNew Kushtia\t23.920\t89.140",
            "QUARTER\tBD1001\tKushtia\t23.930\t89.150",
        ).joinToString("\n")

        val SQUARE_BOUNDARY: String = listOf(
            "0\t22.0\t88.5",
            "0\t22.0\t91.0",
            "0\t26.0\t91.0",
            "0\t26.0\t88.5",
        ).joinToString("\n")
    }
}

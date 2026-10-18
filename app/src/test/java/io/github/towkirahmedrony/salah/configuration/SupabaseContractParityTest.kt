package io.github.towkirahmedrony.salah.configuration

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetPublicationParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The SQL read surfaces and the Kotlin clients that read them, checked against each other.
 *
 * This is the test the remote contracts did not have. Every one of the three remote reads is a **pair** — a
 * migration that defines what the server exposes, and a parser that decides what the app requires — and a
 * pair can drift in a way neither half's own tests can see: a column renamed in SQL, a column claimed by the
 * client but never defined, a `payload` column dropped from a view while the content query still selects it.
 * Each half would keep passing its own tests while the feature was broken end to end, and the breakage would
 * only appear against a live database — which is exactly the environment CI does not have.
 *
 * So the migrations are read here as *files*, the columns they expose are extracted, and each is compared
 * with the list of fields taken from the client's own constants rather than retyped. A missing column fails
 * here, on the JVM, with the column named.
 *
 * **What this cannot check.** Whether a migration has been applied, whether the live project actually has
 * these objects, whether row-level security behaves as the policies claim, or whether a grant is effective.
 * Those need a real project and are recorded as unverified in `docs/SUPABASE_CONTRACT_MATRIX.md`. This test
 * claims exactly what it is: the SQL in this repository lines up with the Kotlin in this repository.
 */
class SupabaseContractParityTest {

    // Dataset publication ------------------------------------------------------------------------

    @Test
    fun `the dataset view exposes every field the manifest parser requires`() {
        val columns = viewColumns(datasetSql(), "dataset_current_publication")

        // Taken from the parser's own constants, so a renamed field cannot pass here by being renamed in
        // both places by hand without the migration being touched.
        val required = listOf(
            DatasetPublicationParser.FIELD_DATASET_ID,
            DatasetPublicationParser.FIELD_PUBLICATION_ID,
            DatasetPublicationParser.FIELD_VERSION,
            DatasetPublicationParser.FIELD_PAYLOAD_SCHEMA_VERSION,
            DatasetPublicationParser.FIELD_STATUS,
            DatasetPublicationParser.FIELD_CHECKSUM,
            DatasetPublicationParser.FIELD_PAYLOAD_PATH,
            DatasetPublicationParser.FIELD_PAYLOAD_SIZE_BYTES,
            DatasetPublicationParser.FIELD_AREA_COUNT,
            DatasetPublicationParser.FIELD_LOCALITY_COUNT,
            DatasetPublicationParser.FIELD_BOUNDARY_RING_COUNT,
            DatasetPublicationParser.FIELD_PUBLISHED_AT,
            DatasetPublicationParser.FIELD_NOTES,
        )

        val missing = required - columns.toSet()
        assertTrue("the dataset view does not expose $missing (it exposes $columns)", missing.isEmpty())
    }

    @Test
    fun `the dataset view is readable by the publishable key and is still a definer view`() {
        val sql = datasetSql()

        assertGrantsSelectToAnon(sql, "dataset_current_publication")
        // A `security_invoker` view would run under the caller's rights, and therefore under the row-level
        // security of the base tables — which has no policy for anon, so every read would return nothing.
        // The client's read path depends on this being a definer view; so does the dataset contract.
        assertTrue(
            "dataset_current_publication must stay a definer view",
            !statementOf(sql, "create or replace view public.dataset_current_publication as")
                .contains("security_invoker"),
        )
    }

    // Prayer-configuration publication -----------------------------------------------------------

    @Test
    fun `the prayer-configuration view exposes the manifest fields and the payload`() {
        val columns = viewColumns(prayerConfigSql(), "prayer_config_current_publication")

        // The first query selects the manifest columns; the second, issued only when the revision changed,
        // selects `payload`. Both read this one view, so it has to expose both — a view without `payload`
        // would answer the content query with a column-not-found error instead of a revision's bytes.
        val manifest = listOf(
            PrayerConfigurationParser.FIELD_CONFIGURATION_ID,
            PrayerConfigurationParser.FIELD_REVISION_ID,
            PrayerConfigurationParser.FIELD_REVISION_NUMBER,
            PrayerConfigurationParser.FIELD_SCHEMA_VERSION,
            PrayerConfigurationParser.FIELD_STATUS,
            PrayerConfigurationParser.FIELD_CHECKSUM,
            PrayerConfigurationParser.FIELD_PUBLISHED_AT,
            PrayerConfigurationParser.FIELD_NOTES,
        )
        val required = manifest + "payload"

        val missing = required - columns.toSet()
        assertTrue(
            "the prayer-configuration view does not expose $missing (it exposes $columns)",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the prayer-configuration view is readable by the publishable key and is still a definer view`() {
        val sql = prayerConfigSql()

        assertGrantsSelectToAnon(sql, "prayer_config_current_publication")
        assertTrue(
            "prayer_config_current_publication must stay a definer view",
            !statementOf(sql, "create or replace view public.prayer_config_current_publication as")
                .contains("security_invoker"),
        )
    }

    // Published reference prices -----------------------------------------------------------------

    @Test
    fun `the published-price table exposes exactly the columns the price query selects`() {
        val columns = tableColumns(metalPricesSql(), "metal_prices")

        // Equality rather than containment, deliberately: the table is the read contract, so a column the
        // client does not select is a column nobody has agreed on. The two permitted extras are the surrogate
        // key the publishing workflow owns and the row-creation timestamp; the client reads neither.
        assertEquals(
            MetalPriceParser.SELECTED_COLUMNS.toSet() + setOf("id", "created_at"),
            columns.toSet(),
        )
    }

    @Test
    fun `the published-price table is read-only for the publishable key`() {
        val sql = withoutComments(metalPricesSql())

        assertTrue(
            "row-level security must be enabled on metal_prices",
            Regex("alter\\s+table\\s+public\\.metal_prices\\s+enable\\s+row\\s+level\\s+security", RegexOption.IGNORE_CASE)
                .containsMatchIn(sql),
        )
        assertTrue(
            "metal_prices must have a select policy for client roles",
            Regex("create\\s+policy\\s+\\w+\\s+on\\s+public\\.metal_prices\\s+for\\s+select", RegexOption.IGNORE_CASE)
                .containsMatchIn(sql),
        )
        assertGrantsSelectToAnon(sql, "metal_prices")
        // No client role may write. The app has no write path, and a grant here would be the first one.
        assertTrue(
            "no insert, update or delete may be granted to a client role on metal_prices",
            !Regex(
                "grant\\s+(insert|update|delete|all)[^;]*\\b(anon|authenticated)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(sql),
        )
    }

    // Reading the real files ----------------------------------------------------------------

    /** The columns a `create ... view` statement selects, in order. */
    private fun viewColumns(sql: String, viewName: String): List<String> =
        statementOf(sql, "create or replace view public.$viewName as")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { it.equals("select", ignoreCase = true) }
            .takeWhile { !it.startsWith("from ", ignoreCase = true) }
            .map { it.removeSuffix(",").trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('.') }
            .toList()

    /** The columns a `create table if not exists ...` statement declares, in order. */
    private fun tableColumns(sql: String, tableName: String): List<String> =
        statementOf(sql, "create table if not exists public.$tableName (")
            .lineSequence()
            .map { it.trim() }
            // The statement's closing line — `);` in the file, `)` once the terminating semicolon has
            // bounded the statement — is not a column declaration.
            .takeWhile { !it.startsWith(")") }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("constraint ", ignoreCase = true) }
            .map { it.substringBefore(' ') }
            .toList()

    /**
     * The text of one statement, from [marker] to the terminating semicolon.
     *
     * Comments are removed *before* the boundary is chosen. These migrations are documented in place, and a
     * `;` inside a comment would otherwise end the statement early and make the column list come back empty —
     * a test that passes by finding nothing is worse than one that fails.
     */
    private fun statementOf(sql: String, marker: String): String {
        val code = withoutComments(sql)
        val index = code.indexOf(marker)
        assertTrue("the migration no longer contains '$marker'", index >= 0)
        if (index < 0) return ""
        return code.substring(index + marker.length).substringBefore(";")
    }

    private fun assertGrantsSelectToAnon(sql: String, objectName: String) {
        assertTrue(
            "select on public.$objectName is not granted to anon, so the publishable key could read nothing",
            Regex(
                "grant\\s+select\\s+on\\s+public\\.$objectName\\s+to\\s+[^;]*\\banon\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(withoutComments(sql)),
        )
    }

    /**
     * The `supabase/migrations` directory, found by walking up from the working directory.
     *
     * The same walk-up search `BackupRulesConfigurationTest` uses: unit tests run with the module directory
     * as their working directory in some invocations and the repository root in others, and a silent empty
     * result would make every assertion above vacuous. Not finding it fails instead.
     */
    private fun migrationsDirectory(): File {
        var current: File? = File(".").absoluteFile
        var depth = 0
        while (current != null && depth < SEARCH_DEPTH) {
            val candidate = File(current, MIGRATIONS_PATH)
            if (candidate.isDirectory) return candidate
            current = current.parentFile
            depth += 1
        }
        error(
            "supabase/migrations was not found. Searched upwards from ${File(".").absolutePath} for " +
                MIGRATIONS_PATH,
        )
    }

    /** The one migration whose name ends with [fileName] — matched by suffix, so the date prefix is not copied. */
    private fun migration(fileName: String): String {
        val directory = migrationsDirectory()
        val file = requireNotNull(directory.listFiles()?.firstOrNull { it.name.endsWith(fileName) }) {
            "no migration ending in '$fileName' exists in ${directory.absolutePath}; found " +
                (directory.list()?.sorted()?.joinToString() ?: "nothing")
        }
        return file.readText()
    }

    private fun datasetSql(): String = migration("dataset_publications.sql")

    private fun prayerConfigSql(): String = migration("prayer_config_publications.sql")

    private fun metalPricesSql(): String = migration("metal_prices.sql")

    private companion object {
        const val SEARCH_DEPTH = 5
        const val MIGRATIONS_PATH = "supabase/migrations"

        /** A block comment, KDoc included — SQL's `/* ... */`, which these migrations use in places. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    }

    /** [sql] with block and line comments removed, so prose can never be read as schema. */
    private fun withoutComments(sql: String): String =
        BLOCK_COMMENT.replace(sql, " ")
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("--") }
}

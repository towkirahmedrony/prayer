package io.github.towkirahmedrony.salah.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup configuration, checked as a configuration.
 *
 * This is a test about a mismatch that no compiler can see and that produces no error at runtime: the
 * rules said `sharedpref` while the preferences were in DataStore, so the intent written in the file's
 * own comment ("the theme and the manual location are small and safe to back up") was not being carried
 * out, and a device transfer quietly lost both. The app worked perfectly; the data was simply gone.
 *
 * Asserting the rules as *files* is what makes that class of mistake reviewable. What can be checked
 * here is that the paths named match where the data actually lives, that the replaceable dataset cache
 * is not swept up as irreplaceable user data, and that the manifest still points at these files.
 *
 * **What this cannot check.** Whether the platform actually backs the files up and restores them.
 * That needs a device or emulator and a real backup/restore cycle; it is documented as a manual
 * procedure in `docs/ARCHITECTURE.md` §12. This test deliberately does not claim to have replaced it.
 */
class BackupRulesConfigurationTest {

    private val resourceDirectory: File = requireNotNull(resDirectory()) {
        "app/src/main/res could not be found from ${File(".").absolutePath}"
    }

    private val backupRules: Element = parse(File(resourceDirectory, "xml/backup_rules.xml"))
    private val extractionRules: Element = parse(File(resourceDirectory, "xml/data_extraction_rules.xml"))

    @Test
    fun `the Android 12 and below rules include the directory DataStore actually writes to`() {
        val includes = includesOf(backupRules)

        assertTrue(
            "backup_rules.xml must include the datastore directory, which is where " +
                "androidx.datastore.preferences keeps Salah's preferences and saved location. " +
                "Included: $includes",
            includes.contains(Include("file", "datastore/")) ||
                includes.contains(Include("file", "datastore")),
        )
    }

    @Test
    fun `both device transfer and cloud backup include the datastore directory`() {
        // Android 12+ separates the two flows, and a rule that only covered one of them would mean a
        // new phone restoring nothing while a cloud restore worked — a difference nobody would notice
        // until it mattered.
        for (section in listOf("cloud-backup", "device-transfer")) {
            val includes = includesOf(childElement(extractionRules, section))

            assertTrue(
                "$section must include the datastore directory. Included: $includes",
                includes.any { it.domain == "file" && it.path.trimEnd('/') == "datastore" },
            )
        }
    }

    @Test
    fun `a replaceable dataset payload is not captured as user data`() {
        // The applied dataset under files/datasets can be produced again from a publication, can be
        // large, and restoring a stale copy over a newer one would be worse than restoring nothing.
        for (rules in listOf("backup_rules.xml" to backupRules, "data_extraction_rules.xml" to extractionRules)) {
            val includes = includesOf(rules.second)

            assertFalse(
                "${rules.first} must not capture the applied dataset cache: $includes",
                includes.any { it.domain == "file" && it.path.contains("datasets") },
            )
        }
    }

    @Test
    fun `no rule widens what is captured`() {
        // With only <include> entries, everything not listed is excluded. Adding an <exclude> would
        // invert that for the domains it names, silently capturing whatever was not excluded — which is
        // the quiet way the dataset cache would end up in a backup after all.
        for (rules in listOf("backup_rules.xml" to backupRules, "data_extraction_rules.xml" to extractionRules)) {
            assertTrue(
                "${rules.first} must not use <exclude>, which would widen the captured set",
                rules.second.allElements("exclude").isEmpty(),
            )
        }
    }

    @Test
    fun `the manifest points at these rule files`() {
        val manifest = File(resourceDirectory.parentFile, "AndroidManifest.xml").readText()

        assertTrue(
            "the manifest must reference backup_rules so these rules are the ones in force",
            manifest.contains("@xml/backup_rules"),
        )
        assertTrue(
            "the manifest must reference data_extraction_rules",
            manifest.contains("@xml/data_extraction_rules"),
        )
        assertTrue(
            "backup must be enabled for the rules to matter",
            manifest.contains("android:allowBackup=\"true\""),
        )
    }

    @Test
    fun `both rule files exist and parse as XML`() {
        // A rule file that does not parse is ignored by the platform, which is a silent loss of the
        // configuration rather than an error.
        assertEquals("full-backup-content", backupRules.tagName)
        assertEquals("data-extraction-rules", extractionRules.tagName)
    }

    private data class Include(val domain: String, val path: String)

    private fun includesOf(root: Element): List<Include> = root.allElements("include").mapNotNull { element ->
        val domain = element.getAttribute("domain").trim()
        val path = element.getAttribute("path").trim()
        if (domain.isEmpty() || path.isEmpty()) null else Include(domain, path)
    }

    /** Elements with [tagName] beneath [this]. Comments are not elements, so they cannot be matched. */
    private fun Element.allElements(tagName: String): List<Element> =
        getElementsByTagName(tagName).let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }

    private fun childElement(root: Element, tagName: String): Element {
        val elements = root.allElements(tagName)
        assertTrue("$tagName is missing from ${root.tagName}", elements.isNotEmpty())
        return elements.first()
    }

    private fun parse(file: File): Element {
        assertTrue("missing configuration file: ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    /** Finds `app/src/main/res`, working from either the repository root or the module directory. */
    private fun resDirectory(): File? {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return null
            RELATIVE_CANDIDATES
                .map { relative -> File(current, relative) }
                .firstOrNull { candidate -> candidate.isDirectory }
                ?.let { return it }
            base = current.parentFile
        }
        return null
    }

    private companion object {
        val RELATIVE_CANDIDATES = listOf(
            "app/src/main/res",
            "src/main/res",
            "res",
        )
        const val SEARCH_DEPTH = 5
    }
}

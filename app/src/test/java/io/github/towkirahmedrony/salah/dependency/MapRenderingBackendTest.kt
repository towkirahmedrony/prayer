package io.github.towkirahmedrony.salah.dependency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the MapLibre artifact the map picker is built against to the **OpenGL ES** one.
 *
 * Since MapLibre Android 13.0.0 the plain `org.maplibre.gl:android-sdk` artifact is Vulkan-only, and its
 * Vulkan renderer crashes natively on Android 16: a SIGSEGV, `Cause: null pointer dereference`, inside
 * `mln::android::MapRenderer::render()`, reached from `MapRenderer.onDrawFrame` on the
 * `MapLibreVulkanSurfaceView$VulkanThread` draw thread. That is upstream `maplibre/maplibre-native#4274`
 * — the same function and thread, Android 16 only, stable on Android 8–15 across four OEMs and four GPU
 * vendors — and it was closed unimplemented, so there is no release to upgrade to that fixes it.
 *
 * **Why the artifact coordinate is the fix, and why this test exists.** A native SIGSEGV on a render
 * thread cannot be caught in Kotlin and is not a version problem, so the fix is to link the OpenGL ES
 * artifact: `MapLibreVulkanSurfaceView` is compiled only into the SDK's `vulkan` and `multiBackend`
 * flavors, so it stops being part of this application at all. That makes the coordinate load-bearing —
 * a later dependency tidy-up that "restored" the plain artifact, or a hard-coded coordinate added
 * beside the catalog alias, would quietly bring the Vulkan crash back with nothing to notice it.
 *
 * Nothing at runtime can assert this: the artifact lives in the version catalog and the native library
 * is chosen by Gradle before any test runs. So this reads the catalog and the module's build script
 * directly — the same file-reading approach `BundledDataset` uses for the shipped assets — and fails the
 * build instead. It is a static check: it proves which backend is requested, not that the picker runs on
 * the affected hardware. See `docs/MAP_PICKER.md` §2.1 and §12.
 */
class MapRenderingBackendTest {

    private val catalog: String = readProjectFile(CATALOG_PATH)

    @Test
    fun `the map library alias resolves to the OpenGL ES artifact`() {
        val entry = catalogEntry(MAP_LIBRARY_ALIAS)

        assertEquals("org.maplibre.gl", entry["group"])
        assertEquals(
            "the map picker must link the OpenGL ES artifact: the plain android-sdk artifact is " +
                "Vulkan-only and its Vulkan renderer crashes on Android 16 (upstream #4274)",
            "android-sdk-opengl",
            entry["name"],
        )
    }

    @Test
    fun `no catalog entry declares the Vulkan-only android-sdk artifact`() {
        // The other direction: a second alias pointing at `android-sdk` would reintroduce the Vulkan
        // backend even with the picker's own alias left correct.
        val offending = libraryEntries()
            .filter { (_, fields) ->
                fields["group"] == "org.maplibre.gl" && fields["name"] == "android-sdk"
            }
            .keys

        assertTrue("the Vulkan-only MapLibre artifact is declared by $offending", offending.isEmpty())
    }

    @Test
    fun `the artifact version is pinned rather than dynamic`() {
        val entry = catalogEntry(MAP_LIBRARY_ALIAS)
        val versionRef = requireNotNull(entry["version.ref"]) {
            "$MAP_LIBRARY_ALIAS pins no version"
        }
        val version = versionValue(versionRef)

        assertFalse("a dynamic version cannot be audited: $version", version.contains("+"))
        assertTrue(
            "the version must be an exact release, was '$version'",
            version.matches(EXACT_VERSION),
        )
    }

    @Test
    fun `the application module depends on the catalog alias and hard-codes no MapLibre coordinate`() {
        val buildScript = readProjectFile(APP_BUILD_SCRIPT_PATH)

        assertTrue(
            "app/build.gradle.kts must depend on the catalog alias libs.maplibre.android.sdk",
            buildScript.contains(CATALOG_ALIAS_REFERENCE),
        )
        // A hard-coded coordinate beside the alias would bypass every assertion above.
        assertFalse(
            "app/build.gradle.kts hard-codes a MapLibre coordinate; it must go through the catalog",
            buildScript.contains("org.maplibre.gl"),
        )
    }

    // Catalog reading ----------------------------------------------------------

    private fun catalogEntry(alias: String): Map<String, String> =
        requireNotNull(libraryEntries()[alias]) { "the catalog has no [$alias] library entry" }

    /** Every `alias = { … }` line of `[libraries]`, alias mapped to its inline-table fields. */
    private fun libraryEntries(): Map<String, Map<String, String>> =
        section("libraries")
            .lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.contains('=') && it.contains('{') }
            .associate { line ->
                line.substringBefore('=').trim() to parseInlineTable(line.substringAfter('='))
            }

    private fun parseInlineTable(value: String): Map<String, String> =
        value.trim().removePrefix("{").removeSuffix("}")
            .split(',')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isBlank()) null else key.trim() to part.substringAfter('=').trim().trim('"')
            }
            .toMap()

    /** The resolved `[versions]` value for a `version.ref`, so nothing here assumes a version. */
    private fun versionValue(ref: String): String {
        val value = section("versions")
            .lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }
            .firstOrNull { (key, _) -> key == ref }
            ?.second

        return requireNotNull(value) { "the catalog has no [versions] entry named $ref" }
    }

    /** The body of a `[name]` table, up to the next table header. */
    private fun section(name: String): String {
        val lines = catalog.lines()
        val start = lines.indexOfFirst { it.trim() == "[$name]" }
        require(start >= 0) { "the version catalog has no [$name] table" }
        val next = lines.drop(start + 1).indexOfFirst { it.trim().startsWith("[") }

        return lines.subList(start + 1, if (next < 0) lines.size else start + 1 + next).joinToString("\n")
    }

    /**
     * Reads a file from the repository, searching upwards from the test working directory.
     *
     * Gradle's Android unit-test working directory is the module (`app/`) rather than the repository
     * root, and it is not worth asserting on which; the same candidates-then-parents search that
     * `BundledDataset` uses for the shipped assets works either way.
     */
    private fun readProjectFile(relative: String): String {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return@repeat
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate.readText()
            base = current.parentFile
        }
        error(
            "the project file '$relative' was not found. Searched upwards from " +
                "${File(".").absolutePath}.",
        )
    }

    private companion object {
        const val MAP_LIBRARY_ALIAS: String = "maplibre-android-sdk"
        const val CATALOG_ALIAS_REFERENCE: String = "libs.maplibre.android.sdk"

        const val CATALOG_PATH: String = "gradle/libs.versions.toml"
        const val APP_BUILD_SCRIPT_PATH: String = "app/build.gradle.kts"

        val EXACT_VERSION = Regex("""\d+\.\d+\.\d+""")
        const val SEARCH_DEPTH: Int = 4
    }
}

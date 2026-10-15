package io.github.towkirahmedrony.salah.feature.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring contract of the location selection flow, checked against the sources themselves.
 *
 * Salah has no Compose UI test harness — the unit tests are JVM tests over the domain, the view models
 * and the data layer — so the one thing a behaviour test cannot see is the *arrangement*: that there is
 * exactly one location selection screen, that both entry points render that one screen, that the
 * hierarchical picker has one implementation, and that a per-entry copy has not crept back in. This
 * checks the real files (as the localization suite does, and for the same reason), failing loudly when
 * a file or directory cannot be found rather than passing over an empty list.
 *
 * What it deliberately does **not** cover: how the screen looks, and how it behaves once tapped. Those
 * are the screen's own composition under the state the view model provides, and the behaviour behind
 * them is asserted in `LocationViewModelTest` — the same state, the same rules, for both contexts.
 */
class LocationSelectionWiringTest {

    @Test
    fun `exactly one location selection screen is declared`() {
        val declarations = mainSources()
            .filter { file -> file.readText().contains("fun LocationSelectionScreen(") }
            .map { file -> file.name }

        assertEquals(
            "the app must declare one canonical selection screen, and this is where it was declared: " +
                "$declarations",
            listOf(CANONICAL_SCREEN),
            declarations.sorted(),
        )
    }

    @Test
    fun `the canonical screen states the entry context it is rendering`() {
        val screen = source("$APP_PACKAGE_PATH/feature/location/$CANONICAL_SCREEN").readText()

        assertTrue(
            "the screen must take the entry context rather than being duplicated per entry point",
            screen.contains("context: LocationSelectionContext,"),
        )
        assertTrue(
            "both contexts must still exist: the first launch saves a fix on arrival, a change confirms it",
            screen.contains("FIRST_LAUNCH,") && screen.contains("CHANGE,"),
        )
    }

    @Test
    fun `no per-entry location selection screen exists`() {
        val obsolete = mainSources()
            .filter { file ->
                val text = file.readText()
                text.contains("fun LocationSetupScreen(") || text.contains("fun LocationScreen(")
            }
            .map { file -> file.name }

        assertTrue(
            "these files still declare a location selection screen of their own: $obsolete",
            obsolete.isEmpty(),
        )
    }

    @Test
    fun `the first-launch gate renders the canonical screen`() {
        val shell = source("$APP_PACKAGE_PATH/navigation/SalahApp.kt").readText()

        assertTrue(
            "the first-launch gate must render the canonical screen with the first-launch context",
            shell.contains("LocationSelectionScreen(") &&
                shell.contains("LocationSelectionContext.FIRST_LAUNCH"),
        )
    }

    @Test
    fun `exactly one navigation destination renders the canonical screen`() {
        val host = source("$APP_PACKAGE_PATH/navigation/SalahNavHost.kt").readText()
        val destinations = host.split("composable(").drop(1)

        val selectionDestinations = destinations.filter { destination ->
            destination.contains("LocationSelectionScreen(")
        }
        assertEquals(
            "one destination selects a location; a second one would be a duplicate of it",
            1,
            selectionDestinations.size,
        )
        assertTrue(
            "the LOCATION route renders the canonical screen with the change context",
            selectionDestinations.single().contains("LocationSelectionContext.CHANGE"),
        )
        assertTrue(
            "the destination must return to whatever opened it",
            selectionDestinations.single().contains("popBackStack()"),
        )
    }

    @Test
    fun `the hierarchical picker has one implementation`() {
        val declarations = mainSources()
            .filter { file -> file.readText().contains("fun ManualAreaSection(") }
            .map { file -> file.name }

        assertEquals(
            "the division → district → upazila/thana picker must be declared once: $declarations",
            listOf(PICKER_FILE),
            declarations.sorted(),
        )
    }

    @Test
    fun `both contexts render the one picker`() {
        val screen = source("$APP_PACKAGE_PATH/feature/location/$CANONICAL_SCREEN").readText()

        assertTrue(
            "the screen must render the shared picker rather than a framing of its own",
            screen.contains("ManualAreaSection("),
        )
        assertTrue(
            "the picker is offered in both contexts: outright on a first launch, on request on a change",
            screen.contains("if (context.isFirstLaunch || state.isPickerOpen)"),
        )
    }

    // --- Reading the real files --------------------------------------------------

    private fun source(relative: String): File {
        val root = repositoryRoot()
        val file = File(root, "$SOURCE_ROOT/$relative")
        assertTrue("missing source file: ${file.absolutePath}", file.isFile)
        return file
    }

    /** Every Kotlin source of the app's main source set, in a stable order. */
    private fun mainSources(): List<File> =
        File(repositoryRoot(), SOURCE_ROOT)
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .sortedBy { file -> file.path }
            .toList()

    /**
     * The repository root, found by walking up from the working directory.
     *
     * Android unit tests run with the module directory as their working directory while the same tests
     * are also run from the repository root, so both are tried — and a miss fails with the path that was
     * searched rather than yielding no sources.
     */
    private fun repositoryRoot(): File {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base
            if (current != null && File(current, "$SOURCE_ROOT/$APP_PACKAGE_PATH").isDirectory) {
                return current
            }
            base = current?.parentFile
        }
        error(
            "The app's sources were not found. Searched upwards from " +
                "${File(".").absolutePath} for $SOURCE_ROOT.",
        )
    }

    private companion object {
        const val SEARCH_DEPTH = 5

        /** The app's main source set, relative to the repository root or the module directory. */
        const val SOURCE_ROOT: String = "app/src/main/java"

        /** The app's package path under [SOURCE_ROOT]. */
        const val APP_PACKAGE_PATH: String = "io/github/towkirahmedrony/salah"

        const val CANONICAL_SCREEN: String = "LocationSelectionScreen.kt"

        const val PICKER_FILE: String = "LocationSelectionComponents.kt"
    }
}

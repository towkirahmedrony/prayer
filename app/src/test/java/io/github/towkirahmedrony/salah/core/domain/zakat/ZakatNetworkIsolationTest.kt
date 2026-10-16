package io.github.towkirahmedrony.salah.core.domain.zakat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The calculation engine reaches no network, asserted against the source file itself.
 *
 * The engine's signature already says this — it takes a `ZakatInputs` and returns a `ZakatResult`, and has no
 * constructor at all — but a signature is easy to widen one parameter at a time. This reads the real file that
 * ships and asserts it mentions no networking type, no price-service type and no URL, so adding either to the
 * calculator fails here rather than in review. Comments are stripped first, so the file's own prose cannot make
 * the check vacuous or trip it.
 *
 * This mirrors the source-reading check `LocalizationResourcesTest` already applies to the Home and Settings
 * composables: the same walk-up search for the module's real source directory, and the same refusal to pass
 * vacuously when the directory cannot be found.
 */
class ZakatNetworkIsolationTest {

    @Test
    fun `the calculation engine mentions no network or price-service type`() {
        val source = read(calculatorFile())

        listOf(
            "MetalPriceRepository",
            "RemoteMetalPriceSource",
            "HttpTransport",
            "RemoteFetch",
            "Supabase",
            "http://",
            "https://",
            "rest/v1",
            "java.net",
            "okhttp",
            "Retrofit",
        ).forEach { token ->
            assertTrue(
                "ZakatCalculator must not reference $token: the calculation has to stay independent of the " +
                    "network and of any price service",
                !source.contains(token),
            )
        }
    }

    @Test
    fun `the calculation engine is an object with no injected collaborator`() {
        val source = read(calculatorFile())

        // `object` rather than a class: nothing can be handed to it, so nothing can reach it. A class with a
        // constructor would be the first step back towards a dependency.
        assertTrue("ZakatCalculator must stay a pure object", source.contains("object ZakatCalculator"))
        assertTrue("ZakatCalculator must have no constructor", !source.contains("class ZakatCalculator("))
    }

    /** The engine's source file, found by walking up from the working directory. */
    private fun calculatorFile(): File {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return@repeat
            CANDIDATES
                .map { relative -> File(current, relative) }
                .firstOrNull { candidate -> candidate.isFile }
                ?.let { return it }
            base = current.parentFile
        }
        error(
            "The Zakat calculator source was not found. Searched upwards from ${File(".").absolutePath} for " +
                CANDIDATES.joinToString(),
        )
    }

    /** [file]'s text with block and line comments removed, so prose is never mistaken for code. */
    private fun read(file: File): String = BLOCK_COMMENT.replace(file.readText(), " ")
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private companion object {
        const val SEARCH_DEPTH = 5

        val CANDIDATES = listOf(
            "app/src/main/java/io/github/towkirahmedrony/salah/core/domain/zakat/ZakatCalculator.kt",
            "src/main/java/io/github/towkirahmedrony/salah/core/domain/zakat/ZakatCalculator.kt",
        )

        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    }
}

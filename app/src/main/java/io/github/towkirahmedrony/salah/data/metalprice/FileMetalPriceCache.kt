package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonReader
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonValue
import io.github.towkirahmedrony.salah.core.domain.metalprice.CachedMetalRate
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceCache
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceReadResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceSnapshot
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePricePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The local published-price cache: one file, replaced in one atomic step.
 *
 * ## Layout
 *
 * ```text
 * <root>/
 *   cache.json     the last validated K22/BDT rates and when each was fetched
 *   cache.json.tmp a sibling temporary file used to make the swap atomic
 * ```
 *
 * The snapshot is written to the temporary file, flushed and synced, then renamed over `cache.json`. Before
 * the rename the previous set is in force; after it the new one is. A crash at any other moment leaves either
 * the old file or a stray temporary one, never a half-written cache — and a stray temporary file is ignored.
 *
 * ## What is written
 *
 * Only the rates the repository has already selected — at most one per metal, in the app's K22/BDT basis — in
 * the **same field vocabulary the published table uses**, plus a cache-only `cache_fetched_at_epoch_millis`
 * per rate. That is deliberate: a cache read goes back through [MetalPriceParser], the same validator a
 * downloaded response goes through, so a hand-edited, truncated or partly-written file cannot smuggle in an
 * inactive, unverified, foreign-currency or non-positive rate.
 *
 * `price_per_gram` is written as a JSON **string** holding the exact decimal digits. A JSON number would
 * still be read exactly by the parser (it keeps the literal), but the string form makes it impossible for any
 * intermediary — a text editor, a JSON pretty-printer, a script — to normalise the value through a `Double`.
 *
 * The file also carries `schema_version`, so a future format change can refuse an old cache instead of
 * misreading it. An unknown version, a missing or non-positive fetch time, a malformed document, any rejected
 * row, or two rows for one metal all make the cache `null`: the app behaves as if it had never cached
 * anything, asks the service, and manual entry keeps working.
 *
 * ## What is not written
 *
 * No user data. The snapshot has no field for a weight, a balance, a debt or a result, and this class has no
 * other write method. The calculator's inputs remain in memory only, as `docs/ZAKAT_METHODOLOGY.md` §8.4
 * requires.
 */
class FileMetalPriceCache(
    /** The cache directory. In the app this is a directory under `filesDir`. */
    private val root: File,
) : MetalPriceCache {

    private val mutex = Mutex()

    override suspend fun read(): MetalPriceSnapshot? = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { readFile() }.getOrNull() }
    }

    override suspend fun write(snapshot: MetalPriceSnapshot) {
        // An empty set is never persisted: it carries no rate, and replacing a known-good snapshot with an
        // empty one would turn "nothing new was published today" into "the device forgot yesterday's rate".
        if (snapshot.isEmpty) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    root.mkdirs()
                    writeReplacing(File(root, CACHE_FILE), toJson(snapshot))
                }
            }
        }
    }

    // Reading ---------------------------------------------------------------------------------------

    private fun readFile(): MetalPriceSnapshot? {
        val file = File(root, CACHE_FILE)
        if (!file.isFile) return null

        val document = JsonReader.parse(file.readText()) as? JsonValue.ObjectValue ?: return null
        if (document.intOrNull(FIELD_SCHEMA_VERSION) != SCHEMA_VERSION) return null

        val rows = (document.child(FIELD_PRICES) as? JsonValue.ArrayValue)?.elements ?: return null
        if (rows.isEmpty() || rows.size > MetalType.entries.size) return null

        val rates = rows.map { row -> readRate(row) ?: return null }
        if (rates.map { rate -> rate.price.metal }.distinct().size != rates.size) return null

        return MetalPriceSnapshot(
            gold = rates.firstOrNull { rate -> rate.price.metal == MetalType.GOLD },
            silver = rates.firstOrNull { rate -> rate.price.metal == MetalType.SILVER },
        )
    }

    /** One stored row, re-validated by the same parser a downloaded row goes through. */
    private fun readRate(row: JsonValue): CachedMetalRate? {
        if (row !is JsonValue.ObjectValue) return null
        val fetchedAt = row.longOrNull(FIELD_CACHE_FETCHED_AT) ?: return null
        if (fetchedAt <= 0L) return null

        val parsed = MetalPriceParser.parse(listOf(row))
        if (parsed !is MetalPriceReadResult.Parsed) return null
        if (parsed.rejections.isNotEmpty() || parsed.ignoredCount != 0) return null
        val price = parsed.prices.singleOrNull() ?: return null
        if (!price.isCachedBasis()) return null

        return CachedMetalRate(price = price, fetchedAtEpochMillis = fetchedAt)
    }

    /** True when a stored row is exactly the basis this cache is allowed to hold. */
    private fun PublishedMetalPrice.isCachedBasis(): Boolean =
        purity == ReferencePricePolicy.DEFAULT_PURITY &&
            currency.equals(ReferencePricePolicy.DEFAULT_CURRENCY, ignoreCase = true) &&
            pricePerGram.signum() > 0

    // Writing ---------------------------------------------------------------------------------------

    /** Replaces [target] in one atomic step: write a sibling temporary file, flush it, then rename it over. */
    private fun writeReplacing(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}$TEMPORARY_SUFFIX")
        FileOutputStream(temporary).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Could not replace ${target.name}.")
        }
    }

    private fun toJson(snapshot: MetalPriceSnapshot): String = buildString {
        append('{')
        // Numbers, not strings: the reader uses the numeric accessors for both fields.
        append('"').append(FIELD_SCHEMA_VERSION).append("\":").append(SCHEMA_VERSION)
        append(",\"").append(FIELD_PRICES).append("\":[")
        listOfNotNull(snapshot.gold, snapshot.silver).forEachIndexed { index, rate ->
            if (index > 0) append(',')
            append(rateJson(rate))
        }
        append("]}")
    }

    private fun rateJson(rate: CachedMetalRate): String = buildString {
        val price = rate.price
        append('{')
        appendJsonField(MetalPriceParser.FIELD_METAL_TYPE, price.metal.name)
        append(',')
        appendJsonField(MetalPriceParser.FIELD_PURITY, price.purity.name)
        append(',')
        // A string, not a bare number: the exact digits are what the parser reads back.
        appendJsonField(MetalPriceParser.FIELD_PRICE_PER_GRAM, price.pricePerGram.toPlainString())
        append(',')
        appendJsonField(MetalPriceParser.FIELD_CURRENCY, price.currency)
        append(',')
        appendJsonField(MetalPriceParser.FIELD_SOURCE, price.source)
        append(',')
        appendJsonField(MetalPriceParser.FIELD_SOURCE_URL, price.sourceUrl)
        append(",\"").append(MetalPriceParser.FIELD_OVERRIDE_ACTIVE).append("\":")
        append(if (price.overrideActive) "true" else "false")
        append(",\"").append(MetalPriceParser.FIELD_IS_ACTIVE).append("\":true")
        append(',')
        appendJsonField(MetalPriceParser.FIELD_VERIFICATION_STATUS, "VERIFIED")
        append(',')
        appendJsonField(MetalPriceParser.FIELD_EFFECTIVE_AT, price.effectiveAt)
        append(',')
        appendJsonField(MetalPriceParser.FIELD_PROVIDER_LAST_UPDATE, price.providerLastUpdate)
        append(',')
        appendJsonField(MetalPriceParser.FIELD_FETCHED_AT, price.fetchedAt)
        append(',')
        appendJsonField(MetalPriceParser.FIELD_UPDATED_AT, price.updatedAt)
        append(',')
        append('"').append(FIELD_CACHE_FETCHED_AT).append("\":").append(rate.fetchedAtEpochMillis)
        append('}')
    }

    /** `"key":null` for an absent optional value, so the validator sees an explicit absence. */
    private fun StringBuilder.appendJsonField(key: String, value: String?) {
        append('"').append(key).append("\":")
        if (value == null) {
            append("null")
        } else {
            appendJsonString(value)
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val CACHE_FILE = "cache.json"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val SCHEMA_VERSION = 1
        const val FIELD_SCHEMA_VERSION = "schema_version"
        const val FIELD_PRICES = "prices"
        const val FIELD_CACHE_FETCHED_AT = "cache_fetched_at_epoch_millis"
    }
}

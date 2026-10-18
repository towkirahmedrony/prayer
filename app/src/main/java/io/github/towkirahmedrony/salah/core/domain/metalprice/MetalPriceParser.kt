package io.github.towkirahmedrony.salah.core.domain.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonReader
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonValue

/** One reason a row of the published-price response could not be used, with the row's own detail. */
data class MetalPriceRejection(
    /** Which metal the rejected row claimed, when it said so. `null` when the metal itself was unreadable. */
    val metal: MetalType?,
    val reason: MetalPriceRejectionReason,
    val detail: String,
)

/** Why one row of the published-price response was not usable. */
enum class MetalPriceRejectionReason {
    /** A required field was absent or of the wrong JSON kind. */
    MISSING_FIELD,

    /** `metal_type` is not one of the metals this app knows. */
    UNKNOWN_METAL_TYPE,

    /** `purity` is not one of the purity categories the contract defines. */
    UNKNOWN_PURITY,

    /** `verification_status` is not one of the statuses the contract defines. */
    UNKNOWN_VERIFICATION_STATUS,

    /** `currency` is not the currency an automatic reference price may be stated in. */
    NON_BDT_CURRENCY,

    /** `price_per_gram` is absent, not a number, zero, negative or otherwise not a usable price. */
    INVALID_PRICE,
}

/**
 * The outcome of reading a published-price response.
 *
 * A row that is well-formed but not *published* — inactive, or awaiting or refused verification — is neither
 * a price nor a problem: it is counted in [Parsed.ignoredCount] and otherwise left alone. Only a row that is
 * unusable as a row is a [MetalPriceRejection].
 */
sealed interface MetalPriceReadResult {

    /**
     * The response was a readable array of records.
     *
     * [prices] are the rows that are published, verified and usable. A caller must still decide *which*
     * metal's rate it is looking for and whether the result is unambiguous; that is
     * [SupabaseMetalPriceRepository]'s job, because a duplication is a property of the whole result rather
     * than of one row.
     */
    data class Parsed(
        val prices: List<PublishedMetalPrice>,
        val rejections: List<MetalPriceRejection>,
        val ignoredCount: Int,
    ) : MetalPriceReadResult

    /** The response was a readable, empty result: the service has no published prices at all. */
    data object NoRecords : MetalPriceReadResult

    /** The response could not be read as a published-price response. */
    data class Malformed(val detail: String) : MetalPriceReadResult
}

/**
 * Reads the published-price contract from a Supabase/PostgREST response.
 *
 * Pure, total and free of Android and of any transport: it takes the response body and returns a result, so
 * the whole contract — the column names, the accepted purities, the price validation — is testable against
 * synthetic bodies without a server.
 *
 * ## Why the price is not read as a floating-point number
 *
 * `price_per_gram` is money. The JSON number's text is read through
 * [JsonValue.decimalOrNull], which uses the digits the service sent rather than their nearest `Double`, so a
 * published `19550.00` becomes the exact `BigDecimal("19550.00")` and not `19549.999999999996`. A JSON string
 * is accepted for the same field, because PostgREST renders a `numeric` column either way.
 *
 * ## What is enforced here, and what is not
 *
 * This reader decides whether a row *is a price at all*. It does not decide whether two rows conflict, and it
 * does not choose between them — see [SupabaseMetalPriceRepository].
 */
object MetalPriceParser {

    /** The precision scale the app will not exceed when reading a published price. */
    private const val MAX_PRICE_SCALE = 6

    /** Reads [body], the raw response from the published-price query. */
    fun parse(body: String): MetalPriceReadResult {
        val root = JsonReader.parse(body)
            ?: return MetalPriceReadResult.Malformed("The published-price response is not valid JSON.")
        val elements = when (root) {
            is JsonValue.ArrayValue -> root.elements
            is JsonValue.ObjectValue -> listOf(root)
            else -> return MetalPriceReadResult.Malformed(
                "The published-price response is neither a JSON array of records nor a single record.",
            )
        }
        return parse(elements)
    }

    /**
     * Reads already-parsed JSON records.
     *
     * The same row validation as [parse] over a list, used by the local cache reader so that a stored
     * snapshot is re-validated on read by exactly the rules a downloaded response is validated by. An empty
     * list is [MetalPriceReadResult.NoRecords], not malformed: a cache with no rows is a readable empty set.
     */
    fun parse(elements: List<JsonValue>): MetalPriceReadResult {
        if (elements.isEmpty()) return MetalPriceReadResult.NoRecords

        val prices = mutableListOf<PublishedMetalPrice>()
        val rejections = mutableListOf<MetalPriceRejection>()
        var ignored = 0

        elements.forEach { element ->
            if (element !is JsonValue.ObjectValue) {
                rejections += MetalPriceRejection(
                    metal = null,
                    reason = MetalPriceRejectionReason.MISSING_FIELD,
                    detail = "A published-price record is not a JSON object.",
                )
                return@forEach
            }
            when (val row = readRow(element)) {
                is RowOutcome.Usable -> prices += row.price
                is RowOutcome.NotPublished -> ignored += 1
                is RowOutcome.Rejected -> rejections += row.rejection
            }
        }

        return MetalPriceReadResult.Parsed(prices, rejections, ignored)
    }

    private sealed interface RowOutcome {
        data class Usable(val price: PublishedMetalPrice) : RowOutcome
        data object NotPublished : RowOutcome
        data class Rejected(val rejection: MetalPriceRejection) : RowOutcome
    }

    private fun readRow(row: JsonValue.ObjectValue): RowOutcome {
        val rawMetal = row.stringOrNull(FIELD_METAL_TYPE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(null, FIELD_METAL_TYPE)
        val metal = MetalType.of(rawMetal)
            ?: return RowOutcome.Rejected(
                MetalPriceRejection(
                    metal = null,
                    reason = MetalPriceRejectionReason.UNKNOWN_METAL_TYPE,
                    detail = "A published-price record names the unknown metal '$rawMetal'.",
                ),
            )

        val rawPurity = row.stringOrNull(FIELD_PURITY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(metal, FIELD_PURITY)
        val purity = MetalPurity.of(rawPurity)
            ?: return RowOutcome.Rejected(
                MetalPriceRejection(
                    metal = metal,
                    reason = MetalPriceRejectionReason.UNKNOWN_PURITY,
                    detail = "A $rawMetal record names the unknown purity '$rawPurity'.",
                ),
            )

        val rawCurrency = row.stringOrNull(FIELD_CURRENCY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(metal, FIELD_CURRENCY)
        if (!rawCurrency.equals(ReferencePricePolicy.DEFAULT_CURRENCY, ignoreCase = true)) {
            return RowOutcome.Rejected(
                MetalPriceRejection(
                    metal = metal,
                    reason = MetalPriceRejectionReason.NON_BDT_CURRENCY,
                    detail = "A $rawMetal record is stated in '$rawCurrency', not " +
                        "${ReferencePricePolicy.DEFAULT_CURRENCY}.",
                ),
            )
        }

        val rawStatus = row.stringOrNull(FIELD_VERIFICATION_STATUS)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(metal, FIELD_VERIFICATION_STATUS)
        val status = PriceVerificationStatus.of(rawStatus)
            ?: return RowOutcome.Rejected(
                MetalPriceRejection(
                    metal = metal,
                    reason = MetalPriceRejectionReason.UNKNOWN_VERIFICATION_STATUS,
                    detail = "A $rawMetal record names the unknown verification status '$rawStatus'.",
                ),
            )

        val active = row.booleanOrNull(FIELD_IS_ACTIVE)
            ?: return missing(metal, FIELD_IS_ACTIVE)

        val price = row.decimalOrNull(FIELD_PRICE_PER_GRAM)
        if (price == null || price.signum() <= 0 || price.scale() > MAX_PRICE_SCALE) {
            return RowOutcome.Rejected(
                MetalPriceRejection(
                    metal = metal,
                    reason = MetalPriceRejectionReason.INVALID_PRICE,
                    // The rejected value is not repeated: the field is a published market price, not a
                    // personal figure, but there is nothing to gain from echoing a malformed value back.
                    detail = "A $rawMetal record has no usable positive price per gram.",
                ),
            )
        }

        // Well-formed, but not published in the sense the contract requires: an inactive record, or one
        // awaiting or refused verification, must never be presented as the current approved rate.
        if (!active || status != PriceVerificationStatus.VERIFIED) {
            return RowOutcome.NotPublished
        }

        return RowOutcome.Usable(
            PublishedMetalPrice(
                metal = metal,
                purity = purity,
                pricePerGram = price,
                currency = rawCurrency.uppercase(),
                source = row.stringOrNull(FIELD_SOURCE)?.trim()?.takeIf { it.isNotEmpty() },
                sourceUrl = row.stringOrNull(FIELD_SOURCE_URL)?.trim()?.takeIf { it.isNotEmpty() },
                overrideActive = row.booleanOrNull(FIELD_OVERRIDE_ACTIVE) ?: false,
                effectiveAt = row.stringOrNull(FIELD_EFFECTIVE_AT)?.trim()?.takeIf { it.isNotEmpty() },
                providerLastUpdate = row.stringOrNull(FIELD_PROVIDER_LAST_UPDATE)?.trim()?.takeIf { it.isNotEmpty() },
                fetchedAt = row.stringOrNull(FIELD_FETCHED_AT)?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = row.stringOrNull(FIELD_UPDATED_AT)?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun missing(metal: MetalType?, field: String): RowOutcome = RowOutcome.Rejected(
        MetalPriceRejection(
            metal = metal,
            reason = MetalPriceRejectionReason.MISSING_FIELD,
            detail = "A published-price record is missing the required field '$field'.",
        ),
    )

    /** The column names the reader understands, kept in one place so the DB contract has one mirror. */
    const val FIELD_METAL_TYPE: String = "metal_type"
    const val FIELD_PURITY: String = "purity"
    const val FIELD_PRICE_PER_GRAM: String = "price_per_gram"
    const val FIELD_CURRENCY: String = "currency"
    const val FIELD_SOURCE: String = "source"
    const val FIELD_SOURCE_URL: String = "source_url"
    const val FIELD_OVERRIDE_ACTIVE: String = "override_active"
    const val FIELD_IS_ACTIVE: String = "is_active"
    const val FIELD_VERIFICATION_STATUS: String = "verification_status"
    const val FIELD_EFFECTIVE_AT: String = "effective_at"
    const val FIELD_PROVIDER_LAST_UPDATE: String = "provider_last_update"
    const val FIELD_FETCHED_AT: String = "fetched_at"
    const val FIELD_UPDATED_AT: String = "updated_at"

    /** The columns the query selects, so the request and the reader cannot disagree about the contract. */
    val SELECTED_COLUMNS: List<String> = listOf(
        FIELD_METAL_TYPE,
        FIELD_PURITY,
        FIELD_PRICE_PER_GRAM,
        FIELD_CURRENCY,
        FIELD_SOURCE,
        FIELD_SOURCE_URL,
        FIELD_OVERRIDE_ACTIVE,
        FIELD_IS_ACTIVE,
        FIELD_VERIFICATION_STATUS,
        FIELD_EFFECTIVE_AT,
        FIELD_PROVIDER_LAST_UPDATE,
        FIELD_FETCHED_AT,
        FIELD_UPDATED_AT,
    )
}

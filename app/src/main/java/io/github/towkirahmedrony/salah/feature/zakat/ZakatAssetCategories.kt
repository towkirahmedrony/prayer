package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.core.model.ZakatLine
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
import io.github.towkirahmedrony.salah.core.model.ZakatLineTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatResult
import java.math.RoundingMode

/**
 * The five asset groups the Assets step shows.
 *
 * A group is a view over rows the engine already has: it adds no rule and classifies nothing. Which form
 * fields and which result lines belong to a group is the only knowledge held here.
 */
enum class ZakatAssetCategory(
    val formFields: Set<ZakatFormField>,
    val lineFields: Set<ZakatLineField>,
) {
    CASH(
        formFields = setOf(ZakatFormField.CASH),
        lineFields = setOf(ZakatLineField.CASH),
    ),
    FOREIGN_CURRENCY(
        formFields = setOf(ZakatFormField.FOREIGN_CURRENCY, ZakatFormField.FOREIGN_RATE),
        lineFields = setOf(ZakatLineField.FOREIGN_CURRENCY),
    ),
    METALS(
        formFields = setOf(
            ZakatFormField.GOLD_WEIGHT,
            ZakatFormField.GOLD_PRICE,
            ZakatFormField.SILVER_WEIGHT,
            ZakatFormField.SILVER_PRICE,
        ),
        lineFields = setOf(ZakatLineField.GOLD, ZakatLineField.SILVER),
    ),
    STOCK(
        formFields = setOf(ZakatFormField.INVENTORY),
        lineFields = setOf(ZakatLineField.INVENTORY),
    ),
    OWED(
        formFields = setOf(ZakatFormField.COLLECTIBLE_RECEIVABLES, ZakatFormField.DOUBTFUL_RECEIVABLES),
        lineFields = setOf(ZakatLineField.COLLECTIBLE_RECEIVABLES, ZakatLineField.DOUBTFUL_RECEIVABLES),
    ),
}

/** Where one group stands. */
enum class ZakatCategoryStatus {
    /** Nothing entered. */
    EMPTY,

    /** Something is entered and the engine counts it. */
    COMPLETE,

    /** Something is entered but the engine still needs another value (for example a price). */
    INCOMPLETE,

    /** Entered, and the engine does not count it under the rules in force. */
    NOT_COUNTED,

    /** Text that is not a number. */
    INVALID,
}

/**
 * One group as the screen draws it.
 *
 * [share] is the fraction (0..1) of the counted total, for the composition bar. [amount] is only what the engine
 * counts; what it does not count is carried separately in [notCountedAmount] so the two are never mixed.
 */
data class ZakatAssetCategoryState(
    val category: ZakatAssetCategory,
    val status: ZakatCategoryStatus,
    val amount: String?,
    val share: Float,
    val sharePercent: Int?,
    val notCountedAmount: String?,
)

internal fun buildAssetCategories(
    result: ZakatResult,
    invalid: Set<ZakatFormField>,
    format: (ZakatMoney) -> String,
): List<ZakatAssetCategoryState> {
    fun sum(lines: List<ZakatLine>, treatment: ZakatLineTreatment): ZakatMoney =
        lines
            .filter { line -> line.treatment == treatment }
            .mapNotNull { line -> line.amount }
            .fold(ZakatMoney.ZERO) { acc, money -> acc + money }

    val grouped = ZakatAssetCategory.entries.map { category ->
        category to result.lines.filter { line -> line.field in category.lineFields }
    }
    val total = grouped.fold(ZakatMoney.ZERO) { acc, (_, lines) ->
        acc + sum(lines, ZakatLineTreatment.INCLUDED)
    }

    return grouped.map { (category, lines) ->
        val counted = sum(lines, ZakatLineTreatment.INCLUDED)
        val excluded = sum(lines, ZakatLineTreatment.EXCLUDED)
        val hasIncluded = lines.any { line -> line.treatment == ZakatLineTreatment.INCLUDED }
        val status = when {
            category.formFields.any { field -> field in invalid } -> ZakatCategoryStatus.INVALID
            lines.isEmpty() -> ZakatCategoryStatus.EMPTY
            lines.any { line -> line.treatment == ZakatLineTreatment.INCOMPLETE } -> ZakatCategoryStatus.INCOMPLETE
            hasIncluded -> ZakatCategoryStatus.COMPLETE
            else -> ZakatCategoryStatus.NOT_COUNTED
        }
        val share = if (counted.isZero || total.isZero) {
            0f
        } else {
            counted.amount.divide(total.amount, 4, RoundingMode.HALF_UP).toFloat()
        }
        ZakatAssetCategoryState(
            category = category,
            status = status,
            amount = if (hasIncluded) format(counted) else null,
            share = share,
            sharePercent = if (share > 0f) Math.round(share * 100f) else null,
            notCountedAmount = if (!excluded.isZero) format(excluded) else null,
        )
    }
}

package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatResult
import java.math.RoundingMode

/**
 * The debts step: a list of debts, each split into what is due within the coming year and what is later.
 *
 * ## How the list meets the engine
 *
 * The engine has two liability inputs and this list is only a friendlier way of filling them:
 *  - every counted entry's *due within 12 months* adds to `deductibleLiabilities`;
 *  - every counted entry's *remainder* (owed − due) adds to `longTermLiabilities`, which the engine never
 *    deducts — so no amount is ever entered in both rows.
 *
 * Nothing here decides whether a debt is deducted: that is the engine's rule and the user's choice in the
 * method step. The summary's "net if debts are deducted" is plain subtraction, labelled as conditional.
 *
 * Like the rest of the form, none of it is stored anywhere.
 */
enum class ZakatDebtType { LOAN, CARD, BORROWED, BILL, OTHER }

enum class ZakatDebtStatus {
    /** Nothing entered yet. Not an error, and not counted. */
    EMPTY,

    /** One amount is entered, the other is still blank. Not counted. */
    INCOMPLETE,

    /** Both amounts are valid and consistent. The only status that is counted. */
    COMPLETE,

    /** Text that is not a number, or an amount due that is larger than the total owed. Not counted. */
    INVALID,
}

/** One debt as typed. Text rather than numbers, for the same reason the rest of the form is. */
data class ZakatDebtEntry(
    val id: Long,
    val type: ZakatDebtType = ZakatDebtType.LOAN,
    val owedText: String = "",
    val dueText: String = "",
)

/** One debt as the screen draws it. Amounts are formatted only when they could be read. */
data class ZakatDebtEntryView(
    val id: Long,
    val type: ZakatDebtType,
    val owedText: String,
    val dueText: String,
    val status: ZakatDebtStatus,
    val owedInvalid: Boolean,
    val dueInvalid: Boolean,
    val dueExceedsOwed: Boolean,
    val owed: String?,
    val due: String?,
    val later: String?,
    /** Due as a fraction (0..1) of the total owed, for the split bar. Zero unless the entry is counted. */
    val dueShare: Float,
)

/** The whole step as the screen draws it. */
data class ZakatDebtsView(
    val entries: List<ZakatDebtEntryView> = emptyList(),
    val countedEntries: Int = 0,
    /** Entries that are neither counted nor merely empty: invalid or half-filled. */
    val skippedEntries: Int = 0,
    val assetsCounted: String? = null,
    val dueTotal: String? = null,
    val laterTotal: String? = null,
    val netIfDeducted: String? = null,
    /** The deducted part as a fraction (0..1) of the counted assets, for the summary bar. */
    val deductedShare: Float = 0f,
    /** True when the amount due is larger than the counted assets, so the net total is held at zero. */
    val deductionLimited: Boolean = false,
    val canAddMore: Boolean = true,
)

internal data class DebtEvaluation(
    val entry: ZakatDebtEntry,
    val owed: ZakatMoney?,
    val due: ZakatMoney?,
    val owedInvalid: Boolean,
    val dueInvalid: Boolean,
    val dueExceedsOwed: Boolean,
    val status: ZakatDebtStatus,
) {
    /** The part not due within the year. Only a counted entry has one. */
    val later: ZakatMoney?
        get() = if (status == ZakatDebtStatus.COMPLETE && owed != null && due != null) owed - due else null
}

/** Reads one entry. [ZakatMoney.parse] stays the single authority on what a valid amount is. */
internal fun ZakatDebtEntry.evaluate(): DebtEvaluation {
    val owedBlank = owedText.isBlank()
    val dueBlank = dueText.isBlank()
    val owed = if (owedBlank) null else ZakatMoney.parse(owedText)
    val due = if (dueBlank) null else ZakatMoney.parse(dueText)
    val owedInvalid = !owedBlank && owed == null
    val dueInvalid = !dueBlank && due == null
    val exceeds = owed != null && due != null && due > owed
    val status = when {
        owedInvalid || dueInvalid || exceeds -> ZakatDebtStatus.INVALID
        owedBlank && dueBlank -> ZakatDebtStatus.EMPTY
        owed == null || due == null -> ZakatDebtStatus.INCOMPLETE
        else -> ZakatDebtStatus.COMPLETE
    }
    return DebtEvaluation(this, owed, due, owedInvalid, dueInvalid, exceeds, status)
}

/**
 * This form with the two liability fields filled from the debt list.
 *
 * With no debts in the list the form is returned untouched, so the liability fields keep working exactly as
 * before. With any debts in the list the list owns both fields: only counted entries contribute, and a list
 * with nothing counted leaves them blank rather than zero, because nothing has been said yet.
 */
internal fun ZakatForm.withDebtTotals(): ZakatForm {
    if (debts.isEmpty()) return this
    val counted = debts.map { entry -> entry.evaluate() }.filter { it.status == ZakatDebtStatus.COMPLETE }
    val due = counted.mapNotNull { it.due }.fold(ZakatMoney.ZERO) { acc, money -> acc + money }
    val later = counted.mapNotNull { it.later }.fold(ZakatMoney.ZERO) { acc, money -> acc + money }
    val dueText = if (counted.isEmpty()) "" else due.amount.toPlainString()
    val laterText = if (counted.isEmpty() || later.isZero) "" else later.amount.toPlainString()
    return copy(
        values = values + mapOf(
            ZakatFormField.DEDUCTIBLE_LIABILITIES to dueText,
            ZakatFormField.LONG_TERM_LIABILITIES to laterText,
        ),
    )
}

internal fun buildDebtsView(
    form: ZakatForm,
    result: ZakatResult,
    format: (ZakatMoney) -> String,
): ZakatDebtsView {
    if (form.debts.isEmpty()) return ZakatDebtsView()

    val evaluations = form.debts.map { entry -> entry.evaluate() }
    val counted = evaluations.filter { it.status == ZakatDebtStatus.COMPLETE }
    val dueTotal = counted.mapNotNull { it.due }.fold(ZakatMoney.ZERO) { acc, money -> acc + money }
    val laterTotal = counted.mapNotNull { it.later }.fold(ZakatMoney.ZERO) { acc, money -> acc + money }
    val assets = result.totalEligibleAssets
    val net = (assets - dueTotal).coerceAtLeastZero()
    val deductedShare = if (assets.isZero) {
        0f
    } else {
        dueTotal.amount.divide(assets.amount, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    }

    val entries = evaluations.map { evaluation ->
        val owed = evaluation.owed
        val due = evaluation.due
        ZakatDebtEntryView(
            id = evaluation.entry.id,
            type = evaluation.entry.type,
            owedText = evaluation.entry.owedText,
            dueText = evaluation.entry.dueText,
            status = evaluation.status,
            owedInvalid = evaluation.owedInvalid,
            dueInvalid = evaluation.dueInvalid,
            dueExceedsOwed = evaluation.dueExceedsOwed,
            owed = owed?.let { money -> format(money) },
            due = due?.let { money -> format(money) },
            later = evaluation.later?.let { money -> format(money) },
            dueShare = if (evaluation.status == ZakatDebtStatus.COMPLETE && owed != null && due != null && !owed.isZero) {
                due.amount.divide(owed.amount, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            },
        )
    }

    return ZakatDebtsView(
        entries = entries,
        countedEntries = counted.size,
        skippedEntries = evaluations.count {
            it.status == ZakatDebtStatus.INVALID || it.status == ZakatDebtStatus.INCOMPLETE
        },
        assetsCounted = format(assets),
        dueTotal = if (counted.isEmpty()) null else format(dueTotal),
        laterTotal = if (counted.isEmpty()) null else format(laterTotal),
        netIfDeducted = if (counted.isEmpty()) null else format(net),
        deductedShare = deductedShare,
        deductionLimited = dueTotal > assets,
        canAddMore = form.debts.size < ZakatViewModel.MAX_DEBTS,
    )
}

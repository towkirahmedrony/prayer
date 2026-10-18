package io.github.towkirahmedrony.salah.feature.zakat

/**
 * Something the review step found that the user should look at before calculating.
 *
 * The review never repairs or substitutes anything: an entry that cannot be read is simply left out of the
 * calculation (that is already what the engine does), and is listed here so the user can say so or fix it.
 */
sealed interface ZakatReviewIssue {
    /** An asset group whose entry is not a number, or is waiting for another value (a price, a rate). */
    data class Asset(val category: ZakatAssetCategory, val status: ZakatCategoryStatus) : ZakatReviewIssue

    /** A debt that is invalid or half-filled, and so is not counted. */
    data class Debt(val entry: ZakatDebtEntryView) : ZakatReviewIssue

    /** The optional hawl date, typed but not a date. It leaves nothing out; it only gives no estimate. */
    object HawlDate : ZakatReviewIssue
}

/** Everything worth a second look, in the order the steps come. */
internal fun reviewIssues(state: ZakatUiState.Ready): List<ZakatReviewIssue> = buildList {
    state.assetCategories
        .filter { item ->
            item.status == ZakatCategoryStatus.INVALID || item.status == ZakatCategoryStatus.INCOMPLETE
        }
        .forEach { item -> add(ZakatReviewIssue.Asset(item.category, item.status)) }
    state.debts.entries
        .filter { entry ->
            entry.status == ZakatDebtStatus.INVALID || entry.status == ZakatDebtStatus.INCOMPLETE
        }
        .forEach { entry -> add(ZakatReviewIssue.Debt(entry)) }
    if (state.hawlDateInvalid) add(ZakatReviewIssue.HawlDate)
}

/** True when the issue means something the user entered is not in the calculation. */
internal fun ZakatReviewIssue.isLeftOut(): Boolean = this !is ZakatReviewIssue.HawlDate

/** True when nothing has been entered in any asset group, so there is nothing to calculate. */
internal fun reviewNothingToCalculate(state: ZakatUiState.Ready): Boolean =
    state.assetCategories.none { item -> item.status != ZakatCategoryStatus.EMPTY }

package io.github.towkirahmedrony.salah.feature.zakat

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatResult
import java.math.RoundingMode

/**
 * What the result screen shows beside the engine's own figures.
 *
 * Everything here is plain arithmetic on figures the engine already produced. It decides nothing: whether
 * zakat is due is [ZakatResult.zakatDue] and [ZakatResult.nisabMet], untouched.
 */
data class ZakatResultInsight(
    /** Net wealth as a fraction (0..1) of the nisab, for the meter. `null` when there is no nisab to compare. */
    val netShare: Float?,
    /** How far net wealth is below the nisab, formatted. Only set when it is below. */
    val gap: String?,
    /** The other debt setting's outcome, when the debt setting changes the net at all. */
    val other: ZakatOtherChoiceView?,
)

/**
 * The same figures under the *other* debt setting, shown beside the chosen one because the schools differ.
 * [reachesNisab] is `null` when there is no nisab to compare against.
 */
data class ZakatOtherChoiceView(
    @StringRes val labelRes: Int,
    val net: String,
    val reachesNisab: Boolean?,
)

internal fun buildResultInsight(
    result: ZakatResult,
    form: ZakatForm,
    format: (ZakatMoney) -> String,
): ZakatResultInsight {
    val net = result.netZakatableWealth
    val nisab = result.nisab
    val share = if (nisab == null || nisab.isZero) {
        null
    } else {
        net.amount.divide(nisab.amount, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    }
    val gap = if (nisab != null && result.nisabMet == false) format(nisab - net) else null

    val assets = result.totalEligibleAssets
    val deducting = result.debtTreatment == ZakatDebtTreatment.DEDUCT
    // When the chosen setting does not deduct, the engine reports zero deducted, so what the other setting
    // would take off is read from the same liability field the engine was given.
    val typed = form.values[ZakatFormField.DEDUCTIBLE_LIABILITIES]
        ?.takeIf { text -> text.isNotBlank() }
        ?.let { text -> ZakatMoney.parse(text) }
        ?: ZakatMoney.ZERO
    val deductible = if (deducting) result.totalDeductibleLiabilities else typed

    val other = if (deductible.isZero) {
        null
    } else {
        val otherNet = if (deducting) assets else (assets - deductible).coerceAtLeastZero()
        ZakatOtherChoiceView(
            labelRes = if (deducting) R.string.zakat_debts_keep else R.string.zakat_debts_deduct,
            net = format(otherNet),
            reachesNisab = nisab?.let { threshold -> otherNet >= threshold },
        )
    }
    return ZakatResultInsight(netShare = share, gap = gap, other = other)
}

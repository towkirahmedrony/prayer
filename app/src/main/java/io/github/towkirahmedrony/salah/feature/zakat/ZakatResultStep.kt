package io.github.towkirahmedrony.salah.feature.zakat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldMuted
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment

/**
 * Step 4 of the Zakat flow: the result, and how it was reached.
 *
 * The screen is arithmetic laid out so it can be checked, never a ruling:
 *  - the headline says only what the engine says (an amount due, nothing due, or not enough to tell);
 *  - a meter shows how close net wealth is to the nisab;
 *  - every figure carries the same ● ◆ = marks as the review step, and a price states its source and date;
 *  - anything left out is listed with a way to fix it, and the settings that shaped the answer are shown
 *    with what the other debt setting would have given, because the schools differ.
 */
@Composable
internal fun ZakatResultStep(
    state: ZakatUiState.Ready,
    issues: List<ZakatReviewIssue>,
    onOpenIssue: (ZakatReviewIssue) -> Unit,
    onChangeSettings: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VerdictCard(state = state)
        if (state.result.rows.isNotEmpty() || state.result.totalAssets != null) {
            WorkedOutCard(state = state, issues = issues.filter { issue -> issue.isLeftOut() }, onOpenIssue = onOpenIssue)
            SettingsUsedCard(state = state, onChange = onChangeSettings)
        }
        LimitsSection()
        ActionsRow(state = state, onStartOver = onStartOver)
    }
}

// ---------------------------------------------------------------------------------------------------------
// Verdict
// ---------------------------------------------------------------------------------------------------------

private val MeterFill = Color(0xFFE0B04A)

@Composable
private fun VerdictCard(state: ZakatUiState.Ready, modifier: Modifier = Modifier) {
    val accents = LocalSalahAccents.current
    val onBrand = accents.onBrandSurface
    val result = state.result
    val insight = state.resultInsight
    val due = result.due
    val below = result.statusRes == R.string.zakat_status_below_nisab
    val nothing = result.statusRes == R.string.zakat_status_nothing
    val incomplete = due == null && result.incompleteLabels.isNotEmpty()
    val pillRes = if (below) R.string.zakat_status_calculated else result.statusRes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(accents.brandSurface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.zakat_section_result),
                style = MaterialTheme.typography.labelLarge,
                color = onBrand.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
            )
            if (!nothing) {
                SalahStatusPill(
                    text = stringResource(pillRes),
                    containerColor = onBrand.copy(alpha = 0.16f),
                    contentColor = onBrand,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            due != null -> {
                Text(
                    text = stringResource(R.string.zakat_result_due),
                    style = MaterialTheme.typography.labelLarge,
                    color = onBrand.copy(alpha = 0.8f),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = due,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = onBrand,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = result.currency,
                        style = MaterialTheme.typography.titleSmall,
                        color = GoldMuted,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.zakat_result_rate, result.rateLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBrand.copy(alpha = 0.9f),
                )
                Text(
                    text = stringResource(R.string.zakat_result_over_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBrand.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            below -> {
                Text(
                    text = stringResource(R.string.zakat_status_below_nisab),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = onBrand,
                )
                Text(
                    text = stringResource(R.string.zakat_result_below_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBrand.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            incomplete -> {
                Text(
                    text = stringResource(R.string.zakat_result_incomplete_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = onBrand,
                )
                Text(
                    text = stringResource(R.string.zakat_result_needs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBrand.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                result.incompleteLabels.forEach { labelRes ->
                    Text(
                        text = "\u2022 " + stringResource(labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBrand,
                    )
                }
            }

            else -> Text(
                text = stringResource(result.statusRes),
                style = MaterialTheme.typography.titleMedium,
                color = onBrand,
            )
        }

        val netText = result.netWealth
        val nisabText = result.nisabAmount
        val share = insight?.netShare
        if (!nothing && share != null && netText != null && nisabText != null) {
            NisabMeter(
                share = share,
                netText = netText,
                nisabText = nisabText,
                gap = insight.gap,
                currency = result.currency,
                onBrand = onBrand,
            )
        }
    }
}

@Composable
private fun NisabMeter(
    share: Float,
    netText: String,
    nisabText: String,
    gap: String?,
    currency: String,
    onBrand: Color,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.zakat_result_meter_cd, netText, nisabText)
    Column(modifier = modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .semantics { contentDescription = description },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(5.dp))
                    .background(onBrand.copy(alpha = 0.18f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(MeterFill),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .requiredHeight(20.dp)
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(onBrand),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.zakat_result_meter_net),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBrand.copy(alpha = 0.8f),
                )
                Text(
                    text = netText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = onBrand,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.zakat_result_nisab),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBrand.copy(alpha = 0.8f),
                )
                Text(
                    text = nisabText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = onBrand,
                )
            }
        }
        if (gap != null) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp),
                color = onBrand.copy(alpha = 0.2f),
            )
            Text(
                text = stringResource(R.string.zakat_result_gap, gap, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = GoldMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// How it was worked out
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun WorkedOutCard(
    state: ZakatUiState.Ready,
    issues: List<ZakatReviewIssue>,
    onOpenIssue: (ZakatReviewIssue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val palette = attentionPalette()
    val result = state.result
    val currency = result.currency
    var showBreakdown by rememberSaveable { mutableStateOf(false) }
    val deducting = state.debtChoices.firstOrNull { choice -> choice.selected }?.value == ZakatDebtTreatment.DEDUCT
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.zakat_result_worked_out),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp),
            )
            ReviewRow(
                provenance = Provenance.CALCULATED,
                title = stringResource(R.string.zakat_result_total_assets),
                sub = stringResource(R.string.zakat_debt_sum_assets_sub),
                value = result.totalAssets,
                currency = currency,
            )
            ReviewRow(
                provenance = Provenance.CALCULATED,
                title = stringResource(R.string.zakat_result_deductions),
                sub = selectedLabel(state.debtChoices),
                value = result.deductions?.let { amount -> if (deducting) "\u2212 $amount" else amount },
                currency = currency,
            )
            ResultTotalRow(
                label = stringResource(R.string.zakat_result_net),
                value = result.netWealth,
                currency = currency,
            )
            NisabRow(state = state)

            if (issues.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.background)
                        .padding(12.dp),
                ) {
                    Text(
                        text = context.resources.getQuantityString(R.plurals.zakat_result_leftout, issues.size, issues.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                    )
                    issues.forEach { issue ->
                        Text(
                            text = issueText(issue),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = { onOpenIssue(issue) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(
                                text = stringResource(issueFixRes(issue)),
                                color = palette.text,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            if (result.rows.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = colors.outlineVariant.copy(alpha = 0.6f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBreakdown = !showBreakdown }
                        .padding(horizontal = 14.dp)
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (showBreakdown) R.string.zakat_breakdown_hide else R.string.zakat_breakdown_show,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Chevron(expanded = showBreakdown, tint = colors.primary)
                }
                if (showBreakdown) {
                    result.rows.forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(row.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                )
                                Text(
                                    text = stringResource(row.treatmentRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = row.amount.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultTotalRow(label: String, value: String?, currency: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProvenanceMark(Provenance.CALCULATED)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Amount(
            value = value ?: "\u2014",
            currency = if (value != null) currency else null,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The nisab row: the threshold, the weight and price it came from, and where that price came from.
 * A published price is marked as one (with its source and date); a price the user typed is marked as theirs.
 */
@Composable
private fun NisabRow(state: ZakatUiState.Ready) {
    val colors = MaterialTheme.colorScheme
    val result = state.result
    val forcedMetal = state.nisabMetalOverridden && state.forcedNisabMetalRes != null
    val metalIsGold = if (forcedMetal) {
        state.forcedNisabMetalRes == R.string.zakat_metal_gold
    } else {
        state.nisabMetalChoices.firstOrNull { choice -> choice.selected }?.value?.toString() == "GOLD"
    }
    val price = state.referencePrices.firstOrNull { entry ->
        entry.priceField == if (metalIsGold) ZakatFormField.GOLD_PRICE else ZakatFormField.SILVER_PRICE
    }
    val published = price != null && !price.manuallyEdited && price.pricePerGram != null
    val kind = when {
        published -> Provenance.REFERENCE
        price?.manuallyEdited == true -> Provenance.USER
        else -> Provenance.CALCULATED
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = colors.outlineVariant.copy(alpha = 0.6f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProvenanceMark(kind, modifier = Modifier.padding(top = 2.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.zakat_result_nisab),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = selectedLabel(state.nisabMetalChoices),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (result.nisabGramsLabel != null && result.nisabPriceLabel != null) {
                Text(
                    text = stringResource(
                        R.string.zakat_result_nisab_detail,
                        result.nisabGramsLabel,
                        result.nisabPriceLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else if (result.nisabAmount == null) {
                Text(
                    text = stringResource(R.string.zakat_result_nisab_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (forcedMetal) {
                Text(
                    text = stringResource(
                        R.string.zakat_nisab_metal_forced,
                        stringResource(state.forcedNisabMetalRes!!),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (price != null) {
                if (price.manuallyEdited) {
                    Text(
                        text = stringResource(R.string.zakat_price_manual),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else if (published) {
                    priceEvidence(price)?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Amount(
            value = result.nisabAmount ?: "\u2014",
            currency = if (result.nisabAmount != null) result.currency else null,
        )
    }
}

/** The source and the one date the record actually states, worded as what it is. */
@Composable
private fun priceEvidence(price: ZakatReferencePriceState): String? {
    val stamp: Pair<Int, String>? = when {
        price.effectiveLabel != null -> R.string.zakat_price_effective to price.effectiveLabel
        price.providerUpdatedLabel != null -> R.string.zakat_price_provider_update to price.providerUpdatedLabel
        price.fetchedLabel != null -> R.string.zakat_price_fetched to price.fetchedLabel
        price.updatedLabel != null -> R.string.zakat_price_last_updated to price.updatedLabel
        else -> null
    }
    val parts = listOfNotNull(
        price.sourceLabel?.let { source -> stringResource(R.string.zakat_price_source, source) },
        stamp?.let { (resId, value) -> stringResource(resId, value) },
    )
    return if (parts.isEmpty()) null else parts.joinToString(" \u00B7 ")
}

// ---------------------------------------------------------------------------------------------------------
// Settings used, and the other debt setting
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun SettingsUsedCard(state: ZakatUiState.Ready, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val other = state.resultInsight?.other
    val below = state.result.statusRes == R.string.zakat_status_below_nisab

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.zakat_result_settings_header),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = stringResource(R.string.zakat_result_change), fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingLine(stringResource(R.string.zakat_field_debts), selectedLabel(state.debtChoices))
                SettingLine(stringResource(R.string.zakat_field_jewellery), selectedLabel(state.jewelleryChoices))
                SettingLine(stringResource(R.string.zakat_field_nisab_weight), selectedLabel(state.nisabWeightChoices))
                SettingLine(stringResource(R.string.zakat_field_nisab_metal), selectedLabel(state.nisabMetalChoices))
            }
            if (other != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
                    color = colors.outlineVariant.copy(alpha = 0.6f),
                )
                Text(
                    text = stringResource(R.string.zakat_result_other_header),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(other.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(
                                when (other.reachesNisab) {
                                    true -> R.string.zakat_result_over
                                    false -> if (below) R.string.zakat_result_still_under else R.string.zakat_result_under
                                    null -> R.string.zakat_result_other_unknown
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.zakat_result_other_net, other.net),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
// Limits, sources, actions
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun LimitsSection(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var limitsOpen by rememberSaveable { mutableStateOf(false) }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column {
                DisclosureRow(
                    title = stringResource(R.string.zakat_limits_title),
                    open = limitsOpen,
                    onToggle = { limitsOpen = !limitsOpen },
                )
                if (limitsOpen) DisclosureBody(stringResource(R.string.zakat_limits_body))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = colors.outlineVariant.copy(alpha = 0.6f),
                )
                DisclosureRow(
                    title = stringResource(R.string.zakat_result_sources_title),
                    open = sourcesOpen,
                    onToggle = { sourcesOpen = !sourcesOpen },
                )
                if (sourcesOpen) DisclosureBody(stringResource(R.string.zakat_sources))
            }
        }
        Text(
            text = stringResource(R.string.zakat_not_certified),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
        )
    }
}

@Composable
private fun DisclosureRow(title: String, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val clickLabel = stringResource(
        if (open) R.string.zakat_flow_collapse else R.string.zakat_flow_expand,
        title,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, onClick = onToggle)
            .padding(horizontal = 14.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Chevron(expanded = open, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun DisclosureBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
    )
}

@Composable
private fun ActionsRow(state: ZakatUiState.Ready, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val summary = summaryText(state)
    val copiedLabel = stringResource(R.string.zakat_result_copied)
    val clipLabel = stringResource(R.string.zakat_result_summary_title)

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText(clipLabel, summary))
                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.zakat_result_copy), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onStartOver, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                text = stringResource(R.string.zakat_result_start_over),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The same figures as the screen, as plain text the user chooses to copy. Nothing is stored or sent. */
@Composable
private fun summaryText(state: ZakatUiState.Ready): String {
    val result = state.result
    val dash = "\u2014"
    fun line(label: String, value: String?) = "$label: ${value ?: dash}" +
        if (value != null) " ${result.currency}" else ""

    return buildString {
        appendLine(stringResource(R.string.zakat_result_summary_title))
        if (result.due != null) {
            appendLine(line(stringResource(R.string.zakat_result_due), result.due))
            appendLine(stringResource(R.string.zakat_result_rate, result.rateLabel))
        } else {
            appendLine(stringResource(result.statusRes))
        }
        appendLine(line(stringResource(R.string.zakat_result_total_assets), result.totalAssets))
        appendLine(line(stringResource(R.string.zakat_result_deductions), result.deductions))
        appendLine(line(stringResource(R.string.zakat_result_net), result.netWealth))
        appendLine(line(stringResource(R.string.zakat_result_nisab), result.nisabAmount))
        appendLine(stringResource(R.string.zakat_field_debts) + ": " + selectedLabel(state.debtChoices))
        appendLine(stringResource(R.string.zakat_field_jewellery) + ": " + selectedLabel(state.jewelleryChoices))
        appendLine(stringResource(R.string.zakat_field_nisab_weight) + ": " + selectedLabel(state.nisabWeightChoices))
        appendLine(stringResource(R.string.zakat_field_nisab_metal) + ": " + selectedLabel(state.nisabMetalChoices))
        append(stringResource(R.string.zakat_not_certified))
    }
}

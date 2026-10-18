package io.github.towkirahmedrony.salah.feature.zakat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldMuted
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment

/**
 * Step 3 of the Zakat flow: review what was entered, confirm the settings, then calculate.
 *
 * The idea is that every figure says where it came from:
 *  - a filled dot — the user typed it;
 *  - a gold diamond — a published reference price, shown with its source and date;
 *  - an equals sign — Salah worked it out.
 *
 * Nothing is decided here. The ledger shows the engine's own totals, an entry that cannot be read is listed
 * and left out rather than guessed at, and the only arithmetic of its own is the pair of "net" previews beside
 * the two debt rulings — plain subtraction, shown side by side because the schools differ.
 * This is not the result: whether zakat is due is the next step.
 */
@Composable
internal fun ZakatReviewStep(
    state: ZakatUiState.Ready,
    viewModel: ZakatViewModel,
    issues: List<ZakatReviewIssue>,
    onOpenIssue: (ZakatReviewIssue) -> Unit,
    onEditAssets: (ZakatAssetCategory?) -> Unit,
    onEditDebts: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openSetting by rememberSaveable { mutableStateOf<String?>(ReviewSetting.DEBTS.name) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Legend()
        ReadinessCard(
            state = state,
            issues = issues,
            nothingToCalculate = reviewNothingToCalculate(state),
            onFix = { issue ->
                if (issue == ZakatReviewIssue.HawlDate) {
                    openSetting = ReviewSetting.HAWL.name
                } else {
                    onOpenIssue(issue)
                }
            },
            onGoToAssets = { onEditAssets(null) },
        )
        LedgerCard(state = state, onEditAssets = onEditAssets, onEditDebts = onEditDebts)
        NetCard(state = state)
        SettingsCard(
            state = state,
            viewModel = viewModel,
            openSetting = openSetting,
            onOpenChange = { name -> openSetting = name },
        )
    }
}

private enum class ReviewSetting { DEBTS, JEWELLERY, NISAB_WEIGHT, NISAB_METAL, HAWL }

internal enum class Provenance { USER, REFERENCE, CALCULATED }

// ---------------------------------------------------------------------------------------------------------
// Provenance marks and legend
// ---------------------------------------------------------------------------------------------------------

/** Three shapes rather than three colours, so the meaning never depends on telling hues apart. */
@Composable
internal fun ProvenanceMark(kind: Provenance, modifier: Modifier = Modifier) {
    val description = stringResource(
        when (kind) {
            Provenance.USER -> R.string.zakat_review_legend_user
            Provenance.REFERENCE -> R.string.zakat_review_legend_ref
            Provenance.CALCULATED -> R.string.zakat_review_legend_calc
        },
    )
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(16.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            Provenance.USER -> Canvas(modifier = Modifier.size(8.dp)) { drawCircle(ink) }

            Provenance.REFERENCE -> Canvas(modifier = Modifier.size(10.dp)) {
                val diamond = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width / 2f, size.height)
                    lineTo(0f, size.height / 2f)
                    close()
                }
                drawPath(diamond, ReferenceMark)
            }

            Provenance.CALCULATED -> Text(
                text = "=",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val ReferenceMark = Color(0xFFC49A45)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            Provenance.USER to R.string.zakat_review_legend_user,
            Provenance.REFERENCE to R.string.zakat_review_legend_ref,
            Provenance.CALCULATED to R.string.zakat_review_legend_calc,
        ).forEach { (kind, labelRes) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProvenanceMark(kind)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Readiness: what needs a look before calculating
// ---------------------------------------------------------------------------------------------------------

internal data class AttentionPalette(val background: Color, val border: Color, val text: Color)

@Composable
internal fun attentionPalette(): AttentionPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        AttentionPalette(Color(0xFF3A3220), Color(0xFF7A6430), Color(0xFFF0D9A0))
    } else {
        AttentionPalette(Color(0xFFFFF1D6), Color(0xFFE3B15A), Color(0xFF5A3700))
    }
}

@Composable
private fun ReadinessCard(
    state: ZakatUiState.Ready,
    issues: List<ZakatReviewIssue>,
    nothingToCalculate: Boolean,
    onFix: (ZakatReviewIssue) -> Unit,
    onGoToAssets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val palette = attentionPalette()
    val hawlBlank = state.hawlDateText.isBlank()

    when {
        nothingToCalculate -> ReadinessFrame(
            background = colors.surfaceVariant,
            border = colors.outlineVariant,
            modifier = modifier,
        ) {
            Text(
                text = stringResource(R.string.zakat_review_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.zakat_review_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(onClick = onGoToAssets, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.zakat_review_go_step1), fontWeight = FontWeight.Bold)
            }
        }

        issues.isEmpty() -> ReadinessFrame(
            background = colors.primaryContainer.copy(alpha = 0.6f),
            border = colors.primary.copy(alpha = 0.4f),
            modifier = modifier,
        ) {
            Text(
                text = stringResource(R.string.zakat_review_ready_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.zakat_review_ready_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (hawlBlank) OptionalHawlLine(color = colors.onPrimaryContainer)
        }

        else -> ReadinessFrame(
            background = palette.background,
            border = palette.border,
            modifier = modifier,
        ) {
            val context = LocalContext.current
            Text(
                text = context.resources.getQuantityString(R.plurals.zakat_review_issue_count, issues.size, issues.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = palette.text,
            )
            issues.forEach { issue ->
                Text(
                    text = issueText(issue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = { onFix(issue) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = stringResource(issueFixRes(issue)),
                        color = palette.text,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = stringResource(R.string.zakat_review_leftout_note),
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
            )
            if (hawlBlank) OptionalHawlLine(color = palette.text)
        }
    }
}

@Composable
private fun ReadinessFrame(
    background: Color,
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .padding(14.dp)
            .then(Modifier),
        content = content,
    )
    // The frame's outline is drawn by the background colour pair; `border` is kept for a future stroke.
    @Suppress("UNUSED_EXPRESSION") border
}

@Composable
private fun OptionalHawlLine(color: Color) {
    Text(
        text = stringResource(R.string.zakat_review_optional_hawl),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun issueText(issue: ZakatReviewIssue): String = when (issue) {
    is ZakatReviewIssue.Asset -> stringResource(
        R.string.zakat_review_issue_line,
        stringResource(categoryNameRes(issue.category)),
        stringResource(
            if (issue.status == ZakatCategoryStatus.INVALID) {
                R.string.zakat_flow_status_invalid
            } else {
                R.string.zakat_flow_status_incomplete
            },
        ),
    )

    is ZakatReviewIssue.Debt -> stringResource(
        R.string.zakat_review_issue_line,
        stringResource(debtTypeRes(issue.entry.type)),
        debtIssueMessage(issue.entry),
    )

    ZakatReviewIssue.HawlDate -> stringResource(R.string.zakat_review_issue_hawl)
}

internal fun issueFixRes(issue: ZakatReviewIssue): Int = when (issue) {
    is ZakatReviewIssue.Asset -> R.string.zakat_review_fix_step1
    is ZakatReviewIssue.Debt -> R.string.zakat_review_fix_step2
    ZakatReviewIssue.HawlDate -> R.string.zakat_review_fix_below
}

@Composable
private fun debtIssueMessage(entry: ZakatDebtEntryView): String = when {
    entry.dueExceedsOwed ->
        stringResource(R.string.zakat_debt_error_exceeds, entry.due.orEmpty(), entry.owed.orEmpty())

    entry.owedInvalid -> stringResource(R.string.zakat_debt_error_owed)
    entry.dueInvalid -> stringResource(R.string.zakat_debt_error_due)
    else -> stringResource(R.string.zakat_debt_status_incomplete)
}

// ---------------------------------------------------------------------------------------------------------
// Ledger: assets and debts
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun LedgerCard(
    state: ZakatUiState.Ready,
    onEditAssets: (ZakatAssetCategory?) -> Unit,
    onEditDebts: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val currency = state.result.currency
    val categories = state.assetCategories
    val entered = categories.filter { item -> item.status != ZakatCategoryStatus.EMPTY }
    val notEntered = categories
        .filter { item -> item.status == ZakatCategoryStatus.EMPTY }
        .map { item -> stringResource(categoryNameRes(item.category)) }
    val debts = state.debts

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(bottom = 4.dp)) {
            SectionHeader(
                titleRes = R.string.zakat_review_assets_header,
                actionRes = R.string.zakat_review_edit_step1,
                onAction = { onEditAssets(null) },
            )
            entered.forEach { item ->
                val statusRes = when (item.status) {
                    ZakatCategoryStatus.INCOMPLETE -> R.string.zakat_flow_status_incomplete
                    ZakatCategoryStatus.NOT_COUNTED -> R.string.zakat_flow_status_not_counted
                    ZakatCategoryStatus.INVALID -> R.string.zakat_flow_status_invalid
                    else -> null
                }
                ReviewRow(
                    provenance = if (item.category == ZakatAssetCategory.METALS &&
                        item.status == ZakatCategoryStatus.COMPLETE
                    ) {
                        Provenance.CALCULATED
                    } else {
                        Provenance.USER
                    },
                    title = stringResource(categoryNameRes(item.category)),
                    sub = item.notCountedAmount?.let { amount ->
                        stringResource(R.string.zakat_flow_not_counted_amount, amount)
                    },
                    value = item.amount,
                    currency = currency,
                    pill = statusRes?.let { res -> stringResource(res) },
                    onClick = { onEditAssets(item.category) },
                )
                if (item.category == ZakatAssetCategory.METALS) MetalsEvidence(state = state)
            }
            if (notEntered.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.zakat_review_not_entered, notEntered.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                )
            }
            TotalRow(
                label = stringResource(R.string.zakat_review_total_assets),
                value = state.result.totalAssets,
                currency = currency,
            )

            SectionHeader(
                titleRes = R.string.zakat_review_debts_header,
                actionRes = R.string.zakat_review_edit_step2,
                onAction = { onEditDebts(null) },
                modifier = Modifier.padding(top = 10.dp),
            )
            if (debts.entries.isEmpty()) {
                ReviewRow(
                    provenance = Provenance.USER,
                    title = stringResource(R.string.zakat_review_no_debts),
                    onClick = { onEditDebts(null) },
                )
            } else {
                ReviewRow(
                    provenance = Provenance.USER,
                    title = stringResource(R.string.zakat_debt_sum_due),
                    sub = stringResource(R.string.zakat_review_debts_counted, debts.countedEntries),
                    value = debts.dueTotal,
                    currency = currency,
                    onClick = { onEditDebts(null) },
                )
                ReviewRow(
                    provenance = Provenance.USER,
                    title = stringResource(R.string.zakat_debt_legend_later),
                    sub = stringResource(R.string.zakat_review_never_deducted),
                    value = debts.laterTotal,
                    currency = currency,
                    onClick = { onEditDebts(null) },
                )
                debts.entries
                    .filter { entry ->
                        entry.status == ZakatDebtStatus.INVALID || entry.status == ZakatDebtStatus.INCOMPLETE
                    }
                    .forEach { entry ->
                        ReviewRow(
                            provenance = Provenance.USER,
                            title = stringResource(debtTypeRes(entry.type)),
                            sub = debtIssueMessage(entry),
                            pill = stringResource(R.string.zakat_review_left_out),
                            onClick = { onEditDebts(entry.id) },
                        )
                    }
            }
            TotalRow(
                label = stringResource(R.string.zakat_review_deducted_total),
                value = state.result.deductions,
                currency = currency,
                prefix = if (state.result.deductions != null) "− " else "",
            )
        }
    }
}

/** Where the gold and silver value comes from: what the user typed, and the price that was applied to it. */
@Composable
private fun MetalsEvidence(state: ZakatUiState.Ready) {
    val unitRes = state.unitChoices.firstOrNull { choice -> choice.selected }?.labelRes ?: return

    listOf(
        Triple(ZakatFormField.GOLD_WEIGHT, ZakatFormField.GOLD_PRICE, R.string.zakat_metal_gold),
        Triple(ZakatFormField.SILVER_WEIGHT, ZakatFormField.SILVER_PRICE, R.string.zakat_metal_silver),
    ).forEach { (weightField, priceField, metalRes) ->
        val weight = state.fieldOf(weightField).value
        if (weight.isNotBlank()) {
            EvidenceLine(
                kind = Provenance.USER,
                text = stringResource(
                    R.string.zakat_review_entered_weight,
                    weight,
                    stringResource(unitRes),
                    stringResource(metalRes),
                ),
            )
            val price = state.referencePrices.firstOrNull { entry -> entry.priceField == priceField }
            if (price != null) {
                val perGram = price.pricePerGram
                if (price.manuallyEdited) {
                    EvidenceLine(
                        kind = Provenance.USER,
                        text = stringResource(
                            R.string.zakat_review_price_own,
                            state.fieldOf(priceField).value + " " + stringResource(price.unitLabelRes, price.currencyCode),
                        ),
                    )
                } else if (perGram != null) {
                    val stamp: Pair<Int, String>? = when {
                        price.effectiveLabel != null -> R.string.zakat_price_effective to price.effectiveLabel
                        price.providerUpdatedLabel != null -> R.string.zakat_price_provider_update to price.providerUpdatedLabel
                        price.fetchedLabel != null -> R.string.zakat_price_fetched to price.fetchedLabel
                        price.updatedLabel != null -> R.string.zakat_price_last_updated to price.updatedLabel
                        else -> null
                    }
                    val parts = listOfNotNull(
                        perGram + " " + stringResource(price.unitLabelRes, price.currencyCode),
                        price.purityLabel?.let { purity -> stringResource(R.string.zakat_price_purity, purity) },
                        price.sourceLabel?.let { source -> stringResource(R.string.zakat_price_source, source) },
                        stamp?.let { (resId, value) -> stringResource(resId, value) },
                        // When the figure came from the saved copy, the review says so too — the user is about
                        // to rely on it, so its age belongs beside the number here as well as on the form.
                        price.lastRefreshLabel?.let { savedAt ->
                            stringResource(R.string.zakat_price_saved_at, savedAt)
                        },
                    )
                    EvidenceLine(kind = Provenance.REFERENCE, text = parts.joinToString(" \u00B7 "))
                }
            }
        }
    }
}

@Composable
private fun EvidenceLine(kind: Provenance, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProvenanceMark(kind)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionHeader(
    titleRes: Int,
    actionRes: Int,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = stringResource(actionRes), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ReviewRow(
    provenance: Provenance,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    value: String? = null,
    currency: String? = null,
    pill: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val palette = attentionPalette()
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = colors.outlineVariant.copy(alpha = 0.6f),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProvenanceMark(provenance)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            sub?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (pill != null) {
            SalahStatusPill(text = pill, containerColor = palette.background, contentColor = palette.text)
        }
        if (value != null) Amount(value = value, currency = currency)
        if (onClick != null) {
            Text(text = "\u203A", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    value: String?,
    currency: String,
    modifier: Modifier = Modifier,
    prefix: String = "",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
        Amount(value = value?.let { amount -> prefix + amount } ?: "\u2014", currency = if (value != null) currency else null)
    }
}

@Composable
internal fun Amount(
    value: String,
    currency: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = style, fontWeight = FontWeight.ExtraBold, color = color)
        if (currency != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = currency,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Net wealth
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun NetCard(state: ZakatUiState.Ready, modifier: Modifier = Modifier) {
    val accents = LocalSalahAccents.current
    val onBrand = accents.onBrandSurface
    val result = state.result
    val assets = result.totalAssets
    val deductions = result.deductions

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(accents.brandSurface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "=",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = GoldMuted,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.zakat_review_net_label),
                style = MaterialTheme.typography.labelLarge,
                color = onBrand.copy(alpha = 0.8f),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = result.netWealth ?: "\u2014",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = onBrand,
            )
            if (result.netWealth != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = result.currency,
                    style = MaterialTheme.typography.titleSmall,
                    color = GoldMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        // The subtraction is shown only when it is exact: a total held at zero is not a plain difference.
        if (assets != null && deductions != null && !state.debts.deductionLimited) {
            Text(
                text = stringResource(R.string.zakat_review_net_formula, assets, deductions),
                style = MaterialTheme.typography.bodySmall,
                color = onBrand.copy(alpha = 0.9f),
            )
        } else if (state.debts.deductionLimited) {
            Text(
                text = stringResource(R.string.zakat_debt_sum_floor),
                style = MaterialTheme.typography.bodySmall,
                color = onBrand.copy(alpha = 0.9f),
            )
        }
        Text(
            text = stringResource(R.string.zakat_review_net_next),
            style = MaterialTheme.typography.bodySmall,
            color = onBrand.copy(alpha = 0.9f),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun SettingsCard(
    state: ZakatUiState.Ready,
    viewModel: ZakatViewModel,
    openSetting: String?,
    onOpenChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    fun toggle(setting: ReviewSetting) = onOpenChange(if (openSetting == setting.name) null else setting.name)

    // The two nets side by side. Plain subtraction on the engine's own totals; it declares no ruling right.
    val netKeep = state.debts.assetsCounted ?: state.result.totalAssets
    val netDeduct = state.debts.netIfDeducted ?: netKeep

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.zakat_review_settings_header),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )
        Text(
            text = stringResource(R.string.zakat_review_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 10.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column {
                SettingRow(
                    label = stringResource(R.string.zakat_field_debts),
                    value = selectedLabel(state.debtChoices),
                    open = openSetting == ReviewSetting.DEBTS.name,
                    onToggle = { toggle(ReviewSetting.DEBTS) },
                    first = true,
                ) {
                    ChoiceOptions(
                        choices = state.debtChoices,
                        onSelect = viewModel::onDebtTreatmentChange,
                        effect = { treatment ->
                            if (treatment == ZakatDebtTreatment.DEDUCT) netDeduct else netKeep
                        },
                    )
                    HelpText(R.string.zakat_help_debts)
                }
                SettingRow(
                    label = stringResource(R.string.zakat_field_jewellery),
                    value = selectedLabel(state.jewelleryChoices),
                    open = openSetting == ReviewSetting.JEWELLERY.name,
                    onToggle = { toggle(ReviewSetting.JEWELLERY) },
                ) {
                    ChoiceOptions(choices = state.jewelleryChoices, onSelect = viewModel::onJewelleryChange)
                    HelpText(R.string.zakat_help_jewellery)
                }
                SettingRow(
                    label = stringResource(R.string.zakat_field_nisab_weight),
                    value = selectedLabel(state.nisabWeightChoices),
                    open = openSetting == ReviewSetting.NISAB_WEIGHT.name,
                    onToggle = { toggle(ReviewSetting.NISAB_WEIGHT) },
                ) {
                    ChoiceOptions(choices = state.nisabWeightChoices, onSelect = viewModel::onNisabWeightChange)
                    HelpText(R.string.zakat_help_nisab_weight)
                }
                SettingRow(
                    label = stringResource(R.string.zakat_field_nisab_metal),
                    value = selectedLabel(state.nisabMetalChoices),
                    extra = if (state.nisabMetalOverridden && state.forcedNisabMetalRes != null) {
                        stringResource(R.string.zakat_nisab_metal_forced, stringResource(state.forcedNisabMetalRes))
                    } else {
                        null
                    },
                    open = openSetting == ReviewSetting.NISAB_METAL.name,
                    onToggle = { toggle(ReviewSetting.NISAB_METAL) },
                ) {
                    ChoiceOptions(choices = state.nisabMetalChoices, onSelect = viewModel::onNisabMetalChange)
                    HelpText(R.string.zakat_help_nisab_metal)
                }
                SettingRow(
                    label = stringResource(R.string.zakat_field_hawl),
                    value = if (state.hawlDateText.isBlank()) {
                        stringResource(R.string.zakat_review_hawl_not_set)
                    } else {
                        state.hawlDateText
                    },
                    open = openSetting == ReviewSetting.HAWL.name,
                    onToggle = { toggle(ReviewSetting.HAWL) },
                ) {
                    OutlinedTextField(
                        value = state.hawlDateText,
                        onValueChange = viewModel::onHawlDateChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        isError = state.hawlDateInvalid,
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(text = stringResource(R.string.zakat_field_hawl)) },
                    )
                    HelpText(R.string.zakat_help_hawl)
                    val estimateDate = state.result.hawlEstimateDate
                    val estimateDays = state.result.hawlEstimateDays
                    if (estimateDate != null && estimateDays != null) {
                        Text(
                            text = stringResource(R.string.zakat_hawl_estimate, estimateDate, estimateDays),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    extra: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val clickLabel = stringResource(
        if (open) R.string.zakat_flow_collapse else R.string.zakat_flow_expand,
        label,
    )
    if (!first) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = colors.outlineVariant.copy(alpha = 0.6f),
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProvenanceMark(Provenance.USER)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            extra?.let { text ->
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Chevron(expanded = open, tint = colors.onSurfaceVariant)
    }
    if (open) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), content = content)
    }
}

@Composable
private fun <T> ChoiceOptions(
    choices: List<ZakatChoice<T>>,
    onSelect: (T) -> Unit,
    effect: ((T) -> String?)? = null,
) {
    choices.forEach { choice ->
        OptionRow(
            label = if (choice.labelIsValue) choice.value.toString() else stringResource(choice.labelRes),
            selected = choice.selected,
            onClick = { onSelect(choice.value) },
            effect = effect?.invoke(choice.value),
        )
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, effect: String?) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceVariant)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        effect?.let { amount ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.zakat_review_net_option, amount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelpText(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun <T> selectedLabel(choices: List<ZakatChoice<T>>): String {
    val choice = choices.firstOrNull { it.selected } ?: return ""
    return if (choice.labelIsValue) choice.value.toString() else stringResource(choice.labelRes)
}

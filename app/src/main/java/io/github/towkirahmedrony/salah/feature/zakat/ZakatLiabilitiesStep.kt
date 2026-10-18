package io.github.towkirahmedrony.salah.feature.zakat

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R

/**
 * Step 2 of the Zakat flow: what the user owes.
 *
 * Each debt is one card with two amounts — the total still owed, and the part due within the coming year —
 * and a bar that shows the split as it is typed. The summary at the bottom shows how the counted entries move
 * the total from step 1. It states arithmetic only; whether any of it is deducted is the engine's rule and the
 * user's choice in the method step, and the screen says so rather than implying a ruling.
 */
@Composable
internal fun ZakatLiabilitiesStep(
    state: ZakatUiState.Ready,
    viewModel: ZakatViewModel,
    expandedId: Long?,
    onExpandedChange: (Long?) -> Unit,
    onEditAssets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val debts = state.debts
    val currency = state.result.currency

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AssetsStrip(total = state.result.totalAssets, currency = currency, onEdit = onEditAssets)

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.zakat_debt_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (debts.entries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.zakat_debt_count, debts.entries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        debts.entries.forEach { entry ->
            val expanded = expandedId == entry.id
            DebtCard(
                entry = entry,
                currency = currency,
                expanded = expanded,
                onToggle = { onExpandedChange(if (expanded) null else entry.id) },
                onRemove = {
                    viewModel.onRemoveDebt(entry.id)
                    if (expanded) onExpandedChange(null)
                },
                viewModel = viewModel,
            )
        }

        AddDebtButton(
            enabled = debts.canAddMore,
            onClick = { viewModel.onAddDebt()?.let { id -> onExpandedChange(id) } },
        )
        Text(
            text = stringResource(
                if (debts.canAddMore) R.string.zakat_debt_empty_note else R.string.zakat_debt_add_limit,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        if (debts.countedEntries > 0) {
            SummaryCard(debts = debts, currency = currency)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Continuity with step 1
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun AssetsStrip(total: String?, currency: String, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.zakat_debt_assets_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = total ?: "\u2014",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currency,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = stringResource(R.string.zakat_debt_edit))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// One debt
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun DebtCard(
    entry: ZakatDebtEntryView,
    currency: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    viewModel: ZakatViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val palette = debtPalette()
    val invalid = entry.status == ZakatDebtStatus.INVALID
    val name = stringResource(debtTypeRes(entry.type))
    val clickLabel = stringResource(
        if (expanded) R.string.zakat_flow_collapse else R.string.zakat_flow_expand,
        name,
    )
    val borderColor = when {
        invalid -> colors.error
        expanded -> colors.primary
        else -> colors.outlineVariant
    }
    val subtitle = when (entry.status) {
        ZakatDebtStatus.EMPTY -> R.string.zakat_debt_status_empty
        ZakatDebtStatus.INCOMPLETE -> R.string.zakat_debt_status_incomplete
        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (invalid && !expanded) colors.errorContainer.copy(alpha = 0.35f) else colors.surface,
        ),
        border = BorderStroke(if (invalid || expanded) 1.5.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = clickLabel, onClick = onToggle)
                    .padding(14.dp)
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(palette.key),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    subtitle?.let { res ->
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                entry.owed?.let { owed ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = owed,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.zakat_debt_total_owed),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (invalid && !expanded) {
                    Text(
                        text = stringResource(R.string.zakat_debt_fix),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.error,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Chevron(expanded = expanded, tint = colors.onSurfaceVariant)
            }

            if (!expanded) {
                if (entry.status == ZakatDebtStatus.COMPLETE) {
                    Column(modifier = Modifier.padding(start = 32.dp, end = 14.dp, bottom = 14.dp)) {
                        SplitBar(share = entry.dueShare, palette = palette, description = splitDescription(entry))
                        Spacer(modifier = Modifier.height(6.dp))
                        SplitLegend(due = entry.due.orEmpty(), later = entry.later.orEmpty(), palette = palette)
                    }
                }
                if (invalid) {
                    Text(
                        text = invalidMessage(entry),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.error,
                        modifier = Modifier.padding(start = 32.dp, end = 14.dp, bottom = 12.dp),
                    )
                }
            } else {
                DebtEditor(
                    entry = entry,
                    currency = currency,
                    palette = palette,
                    onDone = onToggle,
                    onRemove = onRemove,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun DebtEditor(
    entry: ZakatDebtEntryView,
    currency: String,
    palette: DebtPalette,
    onDone: () -> Unit,
    onRemove: () -> Unit,
    viewModel: ZakatViewModel,
) {
    val colors = MaterialTheme.colorScheme
    val owedError = when {
        entry.owedInvalid -> stringResource(R.string.zakat_debt_error_owed)
        else -> null
    }
    val dueError = when {
        entry.dueInvalid -> stringResource(R.string.zakat_debt_error_due)
        entry.dueExceedsOwed -> invalidMessage(entry)
        else -> null
    }

    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.zakat_debt_type_label),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZakatDebtType.entries.forEach { type ->
                ChoiceChip(
                    label = stringResource(debtTypeRes(type)),
                    selected = entry.type == type,
                    onClick = { viewModel.onDebtTypeChange(entry.id, type) },
                    modifier = Modifier.widthIn(min = 88.dp),
                )
            }
        }

        DebtAmountField(
            label = stringResource(R.string.zakat_debt_owed_label),
            help = stringResource(R.string.zakat_debt_owed_help),
            value = entry.owedText,
            currency = currency,
            error = owedError,
            onChange = { value -> viewModel.onDebtOwedChange(entry.id, value) },
        )
        DebtAmountField(
            label = stringResource(R.string.zakat_debt_due_label),
            help = stringResource(R.string.zakat_debt_due_help),
            value = entry.dueText,
            currency = currency,
            error = dueError,
            onChange = { value -> viewModel.onDebtDueChange(entry.id, value) },
        )

        if (entry.status == ZakatDebtStatus.COMPLETE) {
            Spacer(modifier = Modifier.height(14.dp))
            SplitBar(share = entry.dueShare, palette = palette, description = splitDescription(entry))
            Spacer(modifier = Modifier.height(6.dp))
            SplitLegend(due = entry.due.orEmpty(), later = entry.later.orEmpty(), palette = palette)
            Text(
                text = stringResource(
                    R.string.zakat_debt_sum_line,
                    entry.due.orEmpty(),
                    entry.later.orEmpty(),
                    entry.owed.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Text(
            text = stringResource(R.string.zakat_debt_note),
            style = MaterialTheme.typography.bodySmall,
            color = palette.noteText,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.noteBackground)
                .padding(12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = stringResource(R.string.zakat_debt_remove),
                    color = colors.error,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onDone,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(text = stringResource(R.string.zakat_flow_done))
            }
        }
    }
}

@Composable
private fun DebtAmountField(
    label: String,
    help: String,
    value: String,
    currency: String,
    error: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 14.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            shape = RoundedCornerShape(14.dp),
            label = { Text(text = label) },
            prefix = { Text(text = currency) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(
            text = error ?: help,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AddDebtButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .border1dp(color)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+  " + stringResource(R.string.zakat_debt_add),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

private fun Modifier.border1dp(color: Color): Modifier =
    this
        .background(color.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
        .border(1.5.dp, color, RoundedCornerShape(18.dp))

// ---------------------------------------------------------------------------------------------------------
// Split bar and summary
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun SplitBar(
    share: Float,
    palette: DebtPalette,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (share > 0f) {
            Box(
                modifier = Modifier
                    .weight(share.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(palette.due),
            )
        }
        if (share < 1f) {
            Box(
                modifier = Modifier
                    .weight((1f - share).coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(palette.later),
            )
        }
    }
}

@Composable
private fun SplitLegend(due: String, later: String, palette: DebtPalette, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem(
            color = palette.due,
            text = stringResource(R.string.zakat_debt_legend_due) + " " + due,
            modifier = Modifier.weight(1f),
        )
        LegendItem(
            color = palette.later,
            text = stringResource(R.string.zakat_debt_legend_later) + " " + later,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LegendItem(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryCard(debts: ZakatDebtsView, currency: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = debtPalette()
    val net = debts.netIfDeducted.orEmpty()
    val deducted = debts.dueTotal.orEmpty()
    val netShare = (1f - debts.deductedShare).coerceIn(0f, 1f)

    Card(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.zakat_debt_sum_title),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SummaryRow(
                label = stringResource(R.string.zakat_debt_sum_assets),
                sub = stringResource(R.string.zakat_debt_sum_assets_sub),
                value = debts.assetsCounted.orEmpty(),
            )
            SummaryRow(
                label = stringResource(R.string.zakat_debt_sum_due),
                sub = stringResource(R.string.zakat_debt_sum_due_sub, debts.countedEntries),
                value = "\u2212 $deducted",
                valueColor = palette.due,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = colors.outlineVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.zakat_debt_sum_net),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = net,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = currency,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (netShare > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(netShare.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.primary),
                    )
                }
                if (debts.deductedShare > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(debts.deductedShare.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(palette.due),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendItem(
                    color = colors.primary,
                    text = stringResource(R.string.zakat_debt_sum_net_legend) + " " + net,
                    modifier = Modifier.weight(1f),
                )
                LegendItem(
                    color = palette.due,
                    text = stringResource(R.string.zakat_debt_sum_deducted_legend) + " " + deducted,
                    modifier = Modifier.weight(1f),
                )
            }

            debts.laterTotal?.let { later ->
                Text(
                    text = stringResource(R.string.zakat_debt_sum_later, later) + " " +
                        stringResource(R.string.zakat_debt_sum_method),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (debts.deductionLimited) {
                Text(
                    text = stringResource(R.string.zakat_debt_sum_floor),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (debts.skippedEntries > 0) {
                Text(
                    text = stringResource(R.string.zakat_debt_sum_skipped, debts.skippedEntries),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    sub: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------------------

/** Debts get their own hue family so they never read as an asset category; both themes stay legible. */
private data class DebtPalette(
    val key: Color,
    val due: Color,
    val later: Color,
    val noteBackground: Color,
    val noteText: Color,
)

@Composable
private fun debtPalette(): DebtPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        DebtPalette(
            key = Color(0xFF8FA3DB),
            due = Color(0xFF9DB0E8),
            later = Color(0xFF3B4668),
            noteBackground = Color(0xFF3A3220),
            noteText = Color(0xFFE8D9A8),
        )
    } else {
        DebtPalette(
            key = Color(0xFF5A6FA8),
            due = Color(0xFF3F5288),
            later = Color(0xFFC5CEE6),
            noteBackground = Color(0xFFF6EFDC),
            noteText = Color(0xFF5A430E),
        )
    }
}

@StringRes
internal fun debtTypeRes(type: ZakatDebtType): Int = when (type) {
    ZakatDebtType.LOAN -> R.string.zakat_debt_type_loan
    ZakatDebtType.CARD -> R.string.zakat_debt_type_card
    ZakatDebtType.BORROWED -> R.string.zakat_debt_type_borrowed
    ZakatDebtType.BILL -> R.string.zakat_debt_type_bill
    ZakatDebtType.OTHER -> R.string.zakat_debt_type_other
}

@Composable
private fun splitDescription(entry: ZakatDebtEntryView): String =
    stringResource(R.string.zakat_debt_split_cd, entry.due.orEmpty(), entry.owed.orEmpty())

/** The one sentence that says why an entry is not counted. */
@Composable
private fun invalidMessage(entry: ZakatDebtEntryView): String = when {
    entry.dueExceedsOwed ->
        stringResource(R.string.zakat_debt_error_exceeds, entry.due.orEmpty(), entry.owed.orEmpty())

    entry.owedInvalid -> stringResource(R.string.zakat_debt_error_owed)
    else -> stringResource(R.string.zakat_debt_error_due)
}

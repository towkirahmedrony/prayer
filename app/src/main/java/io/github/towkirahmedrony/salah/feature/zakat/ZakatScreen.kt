package io.github.towkirahmedrony.salah.feature.zakat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ZakatScaleGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import org.koin.androidx.compose.koinViewModel

private const val COMPACT_PER_ROW = 4

/**
 * The Zakat calculator screen.
 *
 * Layout, top to bottom: the result, the gold/silver prices, assets, liabilities, method, limits.
 *
 * The gold and silver **price boxes are hidden by default**: the published reference price is fetched
 * automatically and shown as a one-line summary per metal. The boxes appear only when the user asks to adjust
 * the prices, or when no usable published price exists (so the calculator can never be left without a way to
 * get a price), or when the user has already typed their own price.
 */
@Composable
fun ZakatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ZakatViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SalahSubpageHeader(
            title = stringResource(R.string.zakat_title),
            backContentDescription = stringResource(R.string.zakat_back),
            onBack = onBack,
        )
        Text(
            text = stringResource(R.string.zakat_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val state = uiState) {
            ZakatUiState.Loading -> ZakatLoading()
            is ZakatUiState.Ready -> ZakatContent(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun ZakatLoading(modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        SalahSkeletonBlock(height = 18.dp, modifier = Modifier.fillMaxWidth(0.4f))
        Spacer(modifier = Modifier.height(14.dp))
        SalahSkeletonBlock(height = 44.dp, modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(modifier = Modifier.height(14.dp))
        SalahSkeletonBlock(height = 18.dp, modifier = Modifier.fillMaxWidth(0.6f))
    }
}

@Composable
private fun ZakatContent(
    state: ZakatUiState.Ready,
    viewModel: ZakatViewModel,
    modifier: Modifier = Modifier,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ResultCard(state = state)
        PricesCard(state = state, viewModel = viewModel)
        AssetsCard(state = state, viewModel = viewModel)
        LiabilitiesCard(state = state, viewModel = viewModel)
        MethodCard(state = state, viewModel = viewModel)
        LimitsCard()

        if (state.canClear) {
            TextButton(
                onClick = { confirmingClear = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.zakat_clear))
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(text = stringResource(R.string.zakat_clear_title)) },
            text = { Text(text = stringResource(R.string.zakat_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        viewModel.clearAll()
                    },
                ) {
                    Text(text = stringResource(R.string.zakat_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(text = stringResource(R.string.zakat_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun ResultCard(state: ZakatUiState.Ready, modifier: Modifier = Modifier) {
    val result = state.result
    var showBreakdown by remember { mutableStateOf(false) }

    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(resId = R.string.zakat_section_result, modifier = Modifier.weight(1f))
            SalahStatusPill(
                text = stringResource(result.statusRes),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (result.due != null) {
            Text(
                text = stringResource(R.string.zakat_result_due),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = result.due,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = result.currency,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.zakat_result_net),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = result.netWealth ?: "\u2014",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (result.netWealth != null) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = result.currency,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.zakat_result_rate, result.rateLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.metRes?.let { metRes ->
            Spacer(modifier = Modifier.height(8.dp))
            SalahStatusPill(
                text = stringResource(metRes),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(10.dp))

        if (result.due != null) {
            TotalRow(labelRes = R.string.zakat_result_net, value = result.netWealth, currency = result.currency)
        }
        TotalRow(labelRes = R.string.zakat_result_total_assets, value = result.totalAssets, currency = result.currency)
        TotalRow(labelRes = R.string.zakat_result_deductions, value = result.deductions, currency = result.currency)
        TotalRow(labelRes = R.string.zakat_result_nisab, value = result.nisabAmount, currency = result.currency)
        if (result.nisabGramsLabel != null && result.nisabPriceLabel != null) {
            Text(
                text = stringResource(
                    R.string.zakat_result_nisab_detail,
                    result.nisabGramsLabel,
                    result.nisabPriceLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (result.incompleteLabels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.zakat_result_needs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            result.incompleteLabels.forEach { labelRes ->
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (result.showsPriceNote) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.zakat_result_price_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (result.hawlEstimateDate != null && result.hawlEstimateDays != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.zakat_hawl_estimate,
                    result.hawlEstimateDate,
                    result.hawlEstimateDays,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (result.rows.isNotEmpty()) {
            TextButton(
                onClick = { showBreakdown = !showBreakdown },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (showBreakdown) R.string.zakat_breakdown_hide else R.string.zakat_breakdown_show,
                    ),
                )
            }
            if (showBreakdown) {
                result.rows.forEach { row -> BreakdownRow(row = row) }
            }
        }
    }
}

@Composable
private fun TotalRow(
    @androidx.annotation.StringRes labelRes: Int,
    value: String?,
    modifier: Modifier = Modifier,
    currency: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (value != null && currency != null) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = currency,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BreakdownRow(row: ZakatResultRowView, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(row.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.amount.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SalahStatusPill(
            text = stringResource(row.treatmentRes),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
// Gold and silver prices
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun PricesCard(
    state: ZakatUiState.Ready,
    viewModel: ZakatViewModel,
    modifier: Modifier = Modifier,
) {
    var adjusting by remember { mutableStateOf(false) }

    val noUsableRate = state.referencePrices.any { price ->
        price.status == ZakatReferencePriceStatus.UNAVAILABLE || price.status == ZakatReferencePriceStatus.FAILED
    }
    val typedOwnPrice = state.referencePrices.any { price -> price.manuallyEdited }
    // The boxes are hidden unless the user asked for them, or there is no published price to fall back on, or
    // the user has already typed a price of their own.
    val showFields = adjusting || noUsableRate || typedOwnPrice
    val priceFields = state.assetFields.filter { field -> field.field in ZakatViewModel.PRICE_FIELDS }

    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(resId = R.string.zakat_price_title, modifier = Modifier.weight(1f))
            TextButton(
                onClick = viewModel::refreshReferencePrices,
                enabled = !state.refreshingReferencePrices,
            ) {
                Text(
                    text = stringResource(
                        if (state.refreshingReferencePrices) {
                            R.string.zakat_prices_updating
                        } else {
                            R.string.zakat_prices_refresh
                        },
                    ),
                )
            }
        }

        state.referencePrices.forEach { price ->
            PriceSummaryRow(price = price, onRestore = viewModel::onRestoreReferencePrice)
        }

        if (noUsableRate) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.zakat_prices_manual_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!noUsableRate && !typedOwnPrice) {
            TextButton(
                onClick = { adjusting = !adjusting },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (adjusting) R.string.zakat_prices_hide else R.string.zakat_prices_adjust,
                    ),
                )
            }
        }

        if (showFields) {
            priceFields.forEach { field -> NumberField(state = field, viewModel = viewModel) }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.zakat_price_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PriceSummaryRow(
    price: ZakatReferencePriceState,
    onRestore: (ZakatFormField) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(price.metalRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            val priceText = price.pricePerGram
            if (priceText != null) {
                Text(
                    text = priceText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = stringResource(price.unitLabelRes, price.currencyCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SalahStatusPill(
                    text = stringResource(price.statusRes),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // One provenance line rather than five: the first statement the record actually makes, worded as what it
        // is (a reviewed date, the provider's own update, a fetch time, or a last-changed time) and never as
        // proof the market price was live at that moment.
        price.sourceLabel?.let { source ->
            Text(
                text = stringResource(R.string.zakat_price_source, source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val stamp: Pair<Int, String>? = when {
            price.effectiveLabel != null -> R.string.zakat_price_effective to price.effectiveLabel
            price.providerUpdatedLabel != null -> R.string.zakat_price_provider_update to price.providerUpdatedLabel
            price.fetchedLabel != null -> R.string.zakat_price_fetched to price.fetchedLabel
            price.updatedLabel != null -> R.string.zakat_price_last_updated to price.updatedLabel
            else -> null
        }
        stamp?.let { (resId, value) ->
            Text(
                text = stringResource(resId, value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        price.messageRes?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (price.manuallyEdited) {
            Text(
                text = stringResource(R.string.zakat_price_manual),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (price.canRestoreReference) {
            TextButton(
                onClick = { onRestore(price.priceField) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.zakat_price_restore, stringResource(price.metalRes)))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun AssetsCard(state: ZakatUiState.Ready, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        SectionHeading(resId = R.string.zakat_section_assets)

        ChoiceGroup(
            titleRes = R.string.zakat_field_currency,
            choices = state.currencyChoices,
            onSelect = viewModel::onCurrencyChange,
            compact = true,
        )
        ChoiceGroup(
            titleRes = R.string.zakat_field_unit,
            choices = state.unitChoices,
            onSelect = viewModel::onUnitChange,
            compact = true,
        )

        // The two price fields live in the prices card above and are hidden there until needed.
        state.assetFields
            .filter { field -> field.field !in ZakatViewModel.PRICE_FIELDS }
            .forEach { field -> NumberField(state = field, viewModel = viewModel) }

        ChoiceGroup(
            titleRes = R.string.zakat_field_inventory_basis,
            choices = state.inventoryBasisChoices,
            onSelect = viewModel::onInventoryBasisChange,
        )
    }
}

@Composable
private fun LiabilitiesCard(state: ZakatUiState.Ready, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        SectionHeading(resId = R.string.zakat_section_liabilities)
        state.liabilityFields.forEach { field -> NumberField(state = field, viewModel = viewModel) }
    }
}

@Composable
private fun MethodCard(state: ZakatUiState.Ready, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        SectionHeading(resId = R.string.zakat_section_method)

        ChoiceGroup(
            titleRes = R.string.zakat_field_nisab_weight,
            helpRes = R.string.zakat_help_nisab_weight,
            choices = state.nisabWeightChoices,
            onSelect = viewModel::onNisabWeightChange,
        )
        ChoiceGroup(
            titleRes = R.string.zakat_field_nisab_metal,
            helpRes = R.string.zakat_help_nisab_metal,
            choices = state.nisabMetalChoices,
            onSelect = viewModel::onNisabMetalChange,
            compact = true,
        )
        if (state.nisabMetalOverridden && state.forcedNisabMetalRes != null) {
            Text(
                text = stringResource(
                    R.string.zakat_nisab_metal_forced,
                    stringResource(state.forcedNisabMetalRes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChoiceGroup(
            titleRes = R.string.zakat_field_jewellery,
            helpRes = R.string.zakat_help_jewellery,
            choices = state.jewelleryChoices,
            onSelect = viewModel::onJewelleryChange,
        )
        ChoiceGroup(
            titleRes = R.string.zakat_field_debts,
            helpRes = R.string.zakat_help_debts,
            choices = state.debtChoices,
            onSelect = viewModel::onDebtTreatmentChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = state.hawlDateText,
            onValueChange = viewModel::onHawlDateChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.hawlDateInvalid,
            shape = RoundedCornerShape(14.dp),
            label = { Text(text = stringResource(R.string.zakat_field_hawl)) },
        )
        Text(
            text = stringResource(R.string.zakat_help_hawl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LimitsCard(modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZakatScaleGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            SectionHeading(resId = R.string.zakat_limits_title)
        }
        Text(
            text = stringResource(R.string.zakat_limits_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.zakat_not_certified),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.zakat_sources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeading(@androidx.annotation.StringRes resId: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
private fun NumberField(
    state: ZakatFieldState,
    viewModel: ZakatViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        OutlinedTextField(
            value = state.value,
            onValueChange = { value -> viewModel.onValueChange(state.field, value) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.isInvalid,
            shape = RoundedCornerShape(14.dp),
            label = { Text(text = stringResource(state.labelRes)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        state.helpRes?.let { helpRes ->
            Text(
                text = stringResource(helpRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.isInvalid) {
            Text(
                text = stringResource(R.string.zakat_field_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * A titled group of mutually exclusive options.
 *
 * [compact] lays short options out as equal-width chips in rows of [COMPACT_PER_ROW] (currencies, units, the
 * nisab metal); long sentence-style options stay as stacked rows so their wording is never truncated.
 */
@Composable
private fun <T> ChoiceGroup(
    @androidx.annotation.StringRes titleRes: Int,
    choices: List<ZakatChoice<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.StringRes helpRes: Int? = null,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        helpRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (compact) {
            choices.chunked(COMPACT_PER_ROW).forEach { rowChoices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowChoices.forEach { choice ->
                        ChoiceChip(
                            label = if (choice.labelIsValue) {
                                choice.value.toString()
                            } else {
                                stringResource(choice.labelRes)
                            },
                            selected = choice.selected,
                            onClick = { onSelect(choice.value) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(COMPACT_PER_ROW - rowChoices.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            choices.forEach { choice ->
                ChoiceRow(
                    label = if (choice.labelIsValue) {
                        choice.value.toString()
                    } else {
                        stringResource(choice.labelRes)
                    },
                    selected = choice.selected,
                    onClick = { onSelect(choice.value) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

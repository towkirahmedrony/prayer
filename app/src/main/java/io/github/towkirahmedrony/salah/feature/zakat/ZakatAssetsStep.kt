package io.github.towkirahmedrony.salah.feature.zakat

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldMuted
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents

/**
 * Step 1 of the Zakat flow: what the user owns.
 *
 * Three ideas hold the screen together:
 *  - one running total at the top whose bar shows each group's share, so every entry visibly moves it;
 *  - cash is typed right on its row (a single amount), every other group opens in place;
 *  - one group is open at a time, so the page stays short.
 *
 * Nothing here is a rule: what is counted, and what is not, comes from [ZakatUiState.Ready.assetCategories],
 * which is built from the engine's own result lines.
 */
@Composable
internal fun ZakatAssetsStep(
    state: ZakatUiState.Ready,
    viewModel: ZakatViewModel,
    expanded: ZakatAssetCategory?,
    onExpandedChange: (ZakatAssetCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TotalCard(state = state)
        CurrencyRow(state = state, viewModel = viewModel)

        Text(
            text = stringResource(R.string.zakat_flow_categories_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )

        state.assetCategories.forEach { item ->
            val category = item.category
            val isExpanded = expanded == category
            val toggle = { onExpandedChange(if (isExpanded) null else category) }

            when (category) {
                ZakatAssetCategory.CASH -> CategoryCard(
                    item = item,
                    expanded = false,
                    onToggle = toggle,
                    inline = { InlineAmount(state.fieldOf(ZakatFormField.CASH), viewModel) },
                )

                ZakatAssetCategory.FOREIGN_CURRENCY -> CategoryCard(
                    item = item,
                    expanded = isExpanded,
                    onToggle = toggle,
                    detail = {
                        AmountField(state.fieldOf(ZakatFormField.FOREIGN_CURRENCY), viewModel)
                        AmountField(state.fieldOf(ZakatFormField.FOREIGN_RATE), viewModel)
                    },
                )

                ZakatAssetCategory.METALS -> CategoryCard(
                    item = item,
                    expanded = isExpanded,
                    onToggle = toggle,
                    detail = { MetalsDetail(state = state, viewModel = viewModel) },
                )

                ZakatAssetCategory.STOCK -> CategoryCard(
                    item = item,
                    expanded = isExpanded,
                    onToggle = toggle,
                    detail = {
                        AmountField(state.fieldOf(ZakatFormField.INVENTORY), viewModel)
                        ChoiceGroup(
                            titleRes = R.string.zakat_field_inventory_basis,
                            choices = state.inventoryBasisChoices,
                            onSelect = viewModel::onInventoryBasisChange,
                        )
                    },
                )

                ZakatAssetCategory.OWED -> CategoryCard(
                    item = item,
                    expanded = isExpanded,
                    onToggle = toggle,
                    detail = {
                        AmountField(state.fieldOf(ZakatFormField.COLLECTIBLE_RECEIVABLES), viewModel)
                        AmountField(state.fieldOf(ZakatFormField.DOUBTFUL_RECEIVABLES), viewModel)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Running total and composition bar
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun TotalCard(state: ZakatUiState.Ready, modifier: Modifier = Modifier) {
    val accents = LocalSalahAccents.current
    val onBrand = accents.onBrandSurface
    val categories = state.assetCategories
    val filled = categories.count { item -> item.status != ZakatCategoryStatus.EMPTY }
    val shown = categories.filter { item -> item.share > 0f }
    val compositionDescription = stringResource(R.string.zakat_flow_composition_cd)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(accents.brandSurface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.zakat_flow_total_label),
                style = MaterialTheme.typography.labelLarge,
                color = onBrand.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
            )
            SalahStatusPill(
                text = stringResource(R.string.zakat_flow_categories_filled, filled, categories.size),
                containerColor = onBrand.copy(alpha = 0.16f),
                contentColor = onBrand,
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = state.result.totalAssets ?: "\u2014",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = onBrand,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = state.result.currency,
                style = MaterialTheme.typography.titleSmall,
                color = GoldMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (shown.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(onBrand.copy(alpha = 0.18f)),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .semantics { contentDescription = compositionDescription },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                shown.forEach { item ->
                    Box(
                        modifier = Modifier
                            .weight(item.share.coerceAtLeast(0.03f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(categoryColor(item.category)),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            shown.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowItems.forEach { item ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(categoryColor(item.category)),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(categoryNameRes(item.category)) + " " +
                                    stringResource(R.string.zakat_flow_percent, item.sharePercent ?: 0),
                                style = MaterialTheme.typography.labelSmall,
                                color = onBrand.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CurrencyRow(state: ZakatUiState.Ready, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.zakat_field_currency),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.currencyChoices.forEach { choice ->
                ChoiceChip(
                    label = choice.value,
                    selected = choice.selected,
                    onClick = { viewModel.onCurrencyChange(choice.value) },
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Category card
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun CategoryCard(
    item: ZakatAssetCategoryState,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    inline: (@Composable () -> Unit)? = null,
    detail: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val invalid = item.status == ZakatCategoryStatus.INVALID
    val expandable = detail != null
    val name = stringResource(categoryNameRes(item.category))
    val clickLabel = stringResource(
        if (expanded) R.string.zakat_flow_collapse else R.string.zakat_flow_expand,
        name,
    )
    val borderColor = when {
        invalid -> colors.error
        expanded -> colors.primary
        else -> colors.outlineVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(if (invalid || expanded) 1.5.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (expandable) {
                            Modifier.clickable(onClickLabel = clickLabel, onClick = onToggle)
                        } else {
                            Modifier
                        },
                    )
                    .padding(14.dp)
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            categoryColor(item.category).copy(
                                alpha = if (item.status == ZakatCategoryStatus.EMPTY) 0.4f else 1f,
                            ),
                        ),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        text = stringResource(categoryDescRes(item.category)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (inline != null) {
                        inline()
                    } else {
                        item.amount?.let { amount ->
                            Text(
                                text = amount,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                        }
                    }
                    item.sharePercent?.let { percent ->
                        Text(
                            text = stringResource(R.string.zakat_flow_share, percent),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (expandable) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Chevron(expanded = expanded, tint = colors.onSurfaceVariant)
                }
            }

            val statusText = when (item.status) {
                ZakatCategoryStatus.INCOMPLETE -> R.string.zakat_flow_status_incomplete
                ZakatCategoryStatus.NOT_COUNTED -> R.string.zakat_flow_status_not_counted
                ZakatCategoryStatus.INVALID -> R.string.zakat_flow_status_invalid
                else -> null
            }
            if (statusText != null || item.notCountedAmount != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (statusText != null) {
                        SalahStatusPill(
                            text = stringResource(statusText),
                            containerColor = when (item.status) {
                                ZakatCategoryStatus.INVALID -> colors.errorContainer
                                ZakatCategoryStatus.INCOMPLETE -> colors.secondaryContainer
                                else -> colors.surfaceVariant
                            },
                            contentColor = when (item.status) {
                                ZakatCategoryStatus.INVALID -> colors.onErrorContainer
                                ZakatCategoryStatus.INCOMPLETE -> colors.onSecondaryContainer
                                else -> colors.onSurfaceVariant
                            },
                        )
                    }
                    item.notCountedAmount?.let { amount ->
                        Text(
                            text = stringResource(R.string.zakat_flow_not_counted_amount, amount),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            if (expanded && detail != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                ) {
                    detail()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = onToggle,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(text = stringResource(R.string.zakat_flow_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Chevron(expanded: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val outer = if (expanded) h * 0.62f else h * 0.38f
        val middle = if (expanded) h * 0.38f else h * 0.62f
        drawLine(tint, Offset(w * 0.25f, outer), Offset(w * 0.5f, middle), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, middle), Offset(w * 0.75f, outer), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** A single amount typed on the card's own row. */
@Composable
private fun InlineAmount(field: ZakatFieldState, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    val label = stringResource(field.labelRes)
    OutlinedTextField(
        value = field.value,
        onValueChange = { value -> viewModel.onValueChange(field.field, value) },
        modifier = modifier
            .width(140.dp)
            .semantics { contentDescription = label },
        singleLine = true,
        isError = field.isInvalid,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(text = "0") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

// ---------------------------------------------------------------------------------------------------------
// Gold and silver
// ---------------------------------------------------------------------------------------------------------

/**
 * Weights, and the published price.
 *
 * The price boxes stay hidden: the published reference price is shown as a one-line summary per metal. They
 * appear only when the user asks, when there is no usable published price, or when the user has already
 * typed a price of their own.
 */
@Composable
private fun MetalsDetail(state: ZakatUiState.Ready, viewModel: ZakatViewModel) {
    var adjusting by rememberSaveable { mutableStateOf(false) }
    val noUsableRate = state.referencePrices.any { price ->
        price.status == ZakatReferencePriceStatus.UNAVAILABLE || price.status == ZakatReferencePriceStatus.FAILED
    }
    val typedOwnPrice = state.referencePrices.any { price -> price.manuallyEdited }
    val showPriceFields = adjusting || noUsableRate || typedOwnPrice

    ChoiceGroup(
        titleRes = R.string.zakat_field_unit,
        choices = state.unitChoices,
        onSelect = viewModel::onUnitChange,
        compact = true,
    )
    AmountField(state.fieldOf(ZakatFormField.GOLD_WEIGHT), viewModel)
    AmountField(state.fieldOf(ZakatFormField.SILVER_WEIGHT), viewModel)

    state.referencePrices.forEach { price ->
        ReferencePriceSummary(price = price, onRestore = viewModel::onRestoreReferencePrice)
    }

    if (showPriceFields) {
        if (noUsableRate) {
            Text(
                text = stringResource(R.string.zakat_prices_manual_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        AmountField(state.fieldOf(ZakatFormField.GOLD_PRICE), viewModel)
        AmountField(state.fieldOf(ZakatFormField.SILVER_PRICE), viewModel)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!noUsableRate && !typedOwnPrice) {
            TextButton(
                onClick = { adjusting = !adjusting },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (adjusting) R.string.zakat_flow_hide_own_price else R.string.zakat_flow_own_price,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = viewModel::refreshReferencePrices,
            enabled = !state.refreshingReferencePrices,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.refreshingReferencePrices) R.string.zakat_prices_updating else R.string.zakat_prices_refresh,
                ),
            )
        }
    }

    Text(
        text = stringResource(R.string.zakat_flow_metals_jewellery_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReferencePriceSummary(
    price: ZakatReferencePriceState,
    onRestore: (ZakatFormField) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(price.metalRes).replaceFirstChar { char -> char.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val priceText = price.pricePerGram
            if (priceText != null) {
                Text(
                    text = priceText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(4.dp))
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

        // A saved or ageing rate is called out beside the figure it stands in for. The price itself is shown
        // exactly as before; what changes is that the screen no longer presents it as today's check.
        if (
            price.status == ZakatReferencePriceStatus.CACHED ||
            price.status == ZakatReferencePriceStatus.STALE
        ) {
            SalahStatusPill(
                text = stringResource(price.statusRes),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Which gold the per-gram figure prices. "BDT per gram" alone does not say whether it is 22K.
        price.purityLabel?.let { purity ->
            Text(
                text = stringResource(R.string.zakat_price_purity, purity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        price.sourceLabel?.let { source ->
            Text(
                text = stringResource(R.string.zakat_price_source, source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The device's own successful check, distinct from every timestamp the published record carries.
        price.lastRefreshLabel?.let { savedAt ->
            Text(
                text = stringResource(R.string.zakat_price_saved_at, savedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // One provenance line: the first statement the record actually makes, worded as what it is.
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
// Per-category look
// ---------------------------------------------------------------------------------------------------------

@StringRes
internal fun categoryNameRes(category: ZakatAssetCategory): Int = when (category) {
    ZakatAssetCategory.CASH -> R.string.zakat_flow_cat_cash
    ZakatAssetCategory.FOREIGN_CURRENCY -> R.string.zakat_flow_cat_foreign
    ZakatAssetCategory.METALS -> R.string.zakat_flow_cat_metals
    ZakatAssetCategory.STOCK -> R.string.zakat_flow_cat_stock
    ZakatAssetCategory.OWED -> R.string.zakat_flow_cat_owed
}

@StringRes
private fun categoryDescRes(category: ZakatAssetCategory): Int = when (category) {
    ZakatAssetCategory.CASH -> R.string.zakat_flow_cat_cash_desc
    ZakatAssetCategory.FOREIGN_CURRENCY -> R.string.zakat_flow_cat_foreign_desc
    ZakatAssetCategory.METALS -> R.string.zakat_flow_cat_metals_desc
    ZakatAssetCategory.STOCK -> R.string.zakat_flow_cat_stock_desc
    ZakatAssetCategory.OWED -> R.string.zakat_flow_cat_owed_desc
}

/** One key colour per group, readable on both the green total card and the surface of either theme. */
private fun categoryColor(category: ZakatAssetCategory): Color = when (category) {
    ZakatAssetCategory.CASH -> Color(0xFF4FA88B)
    ZakatAssetCategory.FOREIGN_CURRENCY -> Color(0xFF5B8DEF)
    ZakatAssetCategory.METALS -> Color(0xFFE0B04A)
    ZakatAssetCategory.STOCK -> Color(0xFF7FB8C9)
    ZakatAssetCategory.OWED -> Color(0xFFD9774F)
}

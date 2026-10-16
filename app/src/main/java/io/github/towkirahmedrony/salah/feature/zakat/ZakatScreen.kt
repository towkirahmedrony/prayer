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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ZakatScaleGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import org.koin.androidx.compose.koinViewModel

/**
 * The Zakat calculator.
 *
 * ## What this screen is careful not to say
 *
 * It reports arithmetic and names the position behind every figure. It never states that zakat is due, never
 * calls one recognised opinion correct, and never presents a result as a religious ruling. The wording of every
 * string was chosen for that: the amount is "zakat due" *as an arithmetic output of the rules shown on the same
 * screen*, the threshold is "at or above the nisab", and the methodology card names the school each choice comes
 * from. Salah is not religiously certified and the screen says so rather than implying otherwise through silence.
 *
 * ## Where the disagreements are surfaced
 *
 * The three choices that change the answer — the nisab weight pair, the jewellery ruling, and whether debts are
 * deducted — are on the screen as choices, with the positions named in their labels. When the all-one-metal rule
 * overrides the metal choice, the override is stated beside the choice rather than applied silently. A user can
 * therefore see which methodology produced their number, which is the difference between a calculator and a
 * black box. `docs/ZAKAT_METHODOLOGY.md` §3 and §4 are the sources for each.
 *
 * ## Result first
 *
 * The calculation sits above the form, because a user adjusting a figure wants to see the effect of the
 * adjustment without scrolling past the field they just typed into.
 *
 * ## Accessibility and layout
 *
 * Every interactive row is at least 48dp tall, the numeric fields carry their label and help text as real text
 * rather than as a placeholder that disappears on focus, a choice row is `selectable` with the radio-button role
 * so its state is announced, and the screen is a single scrolling column so nothing is unreachable at a large
 * font scale. Insets come from the shell's `Scaffold` — see `SalahApp` — and this screen adds none.
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
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

/** The shape of the screen while the interface-language preference is still on its way. */
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResultCard(state = state)
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

// --- Result -------------------------------------------------------------------------------------

@Composable
private fun ResultCard(state: ZakatUiState.Ready, modifier: Modifier = Modifier) {
    val result = state.result

    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.zakat_section_result),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            SalahStatusPill(
                text = stringResource(result.statusRes),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // The answer, before the working. Nothing is shown as an amount when nothing could be worked out, so a
        // user never reads a figure that was not computed.
        TotalRow(
            labelRes = R.string.zakat_result_net,
            value = result.netWealth,
            emphasised = true,
        )
        TotalRow(labelRes = R.string.zakat_result_total_assets, value = result.totalAssets)
        TotalRow(labelRes = R.string.zakat_result_deductions, value = result.deductions)

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))

        TotalRow(labelRes = R.string.zakat_result_nisab, value = result.nisabAmount, emphasised = true)
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
        // Whether the threshold is met is stated in words *and* carried by the pill, so it does not depend on a
        // colour the reader may not distinguish.
        result.metRes?.let { metRes ->
            Spacer(modifier = Modifier.height(6.dp))
            SalahStatusPill(
                text = stringResource(metRes),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (result.due != null) {
            Text(
                text = stringResource(R.string.zakat_result_due),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = result.due,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.zakat_result_rate, result.rateLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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

        // The working. Every row says whether it was counted, and a row that was not still shows its amount, so a
        // user can see what a ruling they chose removed from their total.
        if (result.rows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            result.rows.forEach { row ->
                BreakdownRow(row = row)
            }
        }
    }
}

@Composable
private fun TotalRow(
    @androidx.annotation.StringRes labelRes: Int,
    value: String?,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
            style = if (emphasised) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BreakdownRow(row: ZakatResultRowView, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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

// --- Form sections ------------------------------------------------------------------------------

@Composable
private fun AssetsCard(state: ZakatUiState.Ready, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        SectionHeading(resId = R.string.zakat_section_assets)

        ChoiceGroup(
            titleRes = R.string.zakat_field_currency,
            choices = state.currencyChoices,
            onSelect = viewModel::onCurrencyChange,
        )
        ChoiceGroup(
            titleRes = R.string.zakat_field_unit,
            choices = state.unitChoices,
            onSelect = viewModel::onUnitChange,
        )

        state.assetFields.forEach { field -> NumberField(state = field, viewModel = viewModel) }

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
        )
        // The one place a user's own choice is not final, so the override is stated rather than applied quietly.
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

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.zakat_field_hawl),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = state.hawlDateText,
            onValueChange = viewModel::onHawlDateChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.hawlDateInvalid,
        )
        Text(
            text = stringResource(R.string.zakat_help_hawl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// --- Small pieces -------------------------------------------------------------------------------

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

/**
 * One numeric row.
 *
 * The label and the help line are drawn as text beside the field rather than passed as a placeholder, so they do
 * not vanish the moment the user types and are readable by a screen reader before the field is focused. An
 * invalid entry is reported against the field, in the error colour, with its own message — never by silently
 * substituting a zero.
 */
@Composable
private fun NumberField(
    state: ZakatFieldState,
    viewModel: ZakatViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(state.labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.helpRes?.let { helpRes ->
            Text(
                text = stringResource(helpRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = state.value,
            onValueChange = { value -> viewModel.onValueChange(state.field, value) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.isInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (state.isInvalid) {
            Text(
                text = stringResource(R.string.zakat_field_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A titled group of mutually exclusive rows. */
@Composable
private fun <T> ChoiceGroup(
    @androidx.annotation.StringRes titleRes: Int,
    choices: List<ZakatChoice<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.StringRes helpRes: Int? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        helpRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/**
 * One selectable option.
 *
 * A `selectable` row with the radio-button role rather than a chip, because these labels are sentences — the
 * counter's chips hold two digits and its target labels are numbers, while these name a position a user is
 * choosing between. The control follows the content rather than the other way round.
 *
 * The row is at least 48dp tall and its selected state is carried by the platform's semantics, so a screen reader
 * announces both the option and whether it is the one in force.
 */
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
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            // Decoration, and deliberately silent: `selectable` already tells a screen reader that this row is the
            // one in force, so announcing the dot as well would say it twice.
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

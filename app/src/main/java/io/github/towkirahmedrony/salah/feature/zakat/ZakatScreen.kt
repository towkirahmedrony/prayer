package io.github.towkirahmedrony.salah.feature.zakat

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private const val STEP_ASSETS = 0
private const val STEP_LIABILITIES = 1
private const val STEP_REVIEW = 2
private const val STEP_RESULT = 3
private const val STEP_COUNT = 4

/**
 * The Zakat calculator: four steps, one idea per page.
 *
 * 1. Assets — what the user owns (see [ZakatAssetsStep]).
 * 2. Liabilities — what the user owes.
 * 3. Method — the rulings the user follows.
 * 4. Result — the calculation and its breakdown.
 *
 * The step number is screen state only (it survives rotation, nothing else is stored anywhere). The progress
 * track and the action bar stay put while the content of a step scrolls, so the way forward is always under
 * the thumb. System back goes one step back before it leaves the screen.
 */
@Composable
fun ZakatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ZakatViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(STEP_ASSETS) }
    var expandedName by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedDebtId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(step) { scroll.scrollTo(0) }
    BackHandler(enabled = step > STEP_ASSETS) { step -= 1 }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            SalahSubpageHeader(
                title = stringResource(R.string.zakat_title),
                backContentDescription = stringResource(R.string.zakat_back),
                onBack = { if (step > STEP_ASSETS) step -= 1 else onBack() },
            )
            StepTrack(step = step)
        }

        when (val state = uiState) {
            ZakatUiState.Loading -> Column(modifier = Modifier.weight(1f).padding(20.dp)) { ZakatLoading() }

            is ZakatUiState.Ready -> {
                val expanded = expandedName?.let { name ->
                    ZakatAssetCategory.entries.firstOrNull { category -> category.name == name }
                }
                val attention = state.assetCategories.firstOrNull { item ->
                    item.status == ZakatCategoryStatus.INVALID || item.status == ZakatCategoryStatus.INCOMPLETE
                }
                val issues = reviewIssues(state)
                val leftOut = issues.count { issue -> issue.isLeftOut() }
                val openIssue: (ZakatReviewIssue) -> Unit = { issue ->
                    when (issue) {
                        is ZakatReviewIssue.Asset -> {
                            expandedName = issue.category.name
                            step = STEP_ASSETS
                        }

                        is ZakatReviewIssue.Debt -> {
                            expandedDebtId = issue.entry.id
                            step = STEP_LIABILITIES
                        }

                        ZakatReviewIssue.HawlDate -> Unit
                    }
                }
                val attentionText = if (step == STEP_REVIEW) {
                    LocalContext.current.resources.getQuantityString(R.plurals.zakat_review_leftout, leftOut, leftOut)
                } else {
                    stringResource(R.string.zakat_flow_attention)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (step) {
                        STEP_ASSETS -> {
                            StepHeading(R.string.zakat_flow_assets_title, R.string.zakat_flow_assets_subtitle)
                            ZakatAssetsStep(
                                state = state,
                                viewModel = viewModel,
                                expanded = expanded,
                                onExpandedChange = { category -> expandedName = category?.name },
                            )
                        }

                        STEP_LIABILITIES -> {
                            StepHeading(R.string.zakat_flow_liabilities_title, R.string.zakat_flow_liabilities_subtitle)
                            ZakatLiabilitiesStep(
                                state = state,
                                viewModel = viewModel,
                                expandedId = expandedDebtId,
                                onExpandedChange = { id -> expandedDebtId = id },
                                onEditAssets = { step = STEP_ASSETS },
                            )
                        }

                        STEP_REVIEW -> {
                            StepHeading(R.string.zakat_review_title, R.string.zakat_review_subtitle)
                            ZakatReviewStep(
                                state = state,
                                viewModel = viewModel,
                                issues = issues,
                                onOpenIssue = openIssue,
                                onEditAssets = { category ->
                                    expandedName = category?.name
                                    step = STEP_ASSETS
                                },
                                onEditDebts = { id ->
                                    expandedDebtId = id
                                    step = STEP_LIABILITIES
                                },
                            )
                        }

                        else -> {
                            StepHeading(R.string.zakat_flow_result_title, null)
                            ResultCard(state = state)
                            LimitsCard()
                        }
                    }
                }

                ActionBar(
                    step = step,
                    needsAttention = (step == STEP_ASSETS && attention != null) ||
                        (step == STEP_REVIEW && leftOut > 0),
                    attentionText = attentionText,
                    continueEnabled = !(step == STEP_REVIEW && reviewNothingToCalculate(state)),
                    canClear = state.canClear,
                    onReview = {
                        if (step == STEP_REVIEW) {
                            issues.firstOrNull { issue -> issue.isLeftOut() }?.let(openIssue)
                        } else {
                            attention?.let { item -> expandedName = item.category.name }
                        }
                    },
                    onContinue = { step += 1 },
                    onEdit = { step = STEP_ASSETS },
                    onClear = { confirmingClear = true },
                    onBack = { step -= 1 },
                )
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
                        step = STEP_ASSETS
                        expandedName = null
                        expandedDebtId = null
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

// ---------------------------------------------------------------------------------------------------------
// Flow chrome: progress track, step heading, action bar
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun StepTrack(step: Int, modifier: Modifier = Modifier) {
    val names = listOf(
        R.string.zakat_flow_step_assets,
        R.string.zakat_flow_step_liabilities,
        R.string.zakat_flow_step_method,
        R.string.zakat_flow_step_result,
    )
    val label = stringResource(
        R.string.zakat_flow_step_progress_named,
        step + 1,
        STEP_COUNT,
        stringResource(names[step]),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(STEP_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index <= step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StepHeading(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        subtitleRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The bar pinned under every step: the way forward is always within thumb reach. */
@Composable
private fun ActionBar(
    step: Int,
    needsAttention: Boolean,
    canClear: Boolean,
    onReview: () -> Unit,
    onContinue: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    attentionText: String,
    continueEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            if (needsAttention) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = attentionText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.zakat_flow_review))
                    }
                }
            }
            if (step < STEP_RESULT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > STEP_ASSETS) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.zakat_flow_back_btn),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Button(
                        onClick = onContinue,
                        enabled = continueEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (step == STEP_REVIEW) R.string.zakat_review_calculate else R.string.zakat_flow_continue,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            } else {
                Button(
                    onClick = onEdit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.zakat_flow_edit_answers),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (canClear) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.zakat_clear))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Step 3: method
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun MethodCard(state: ZakatUiState.Ready, viewModel: ZakatViewModel, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
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

// ---------------------------------------------------------------------------------------------------------
// Step 4: result
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
    @StringRes labelRes: Int,
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

// ---------------------------------------------------------------------------------------------------------
// Shared inputs (also used by ZakatAssetsStep)
// ---------------------------------------------------------------------------------------------------------

/** The field state for [field], from whichever list holds it. */
internal fun ZakatUiState.Ready.fieldOf(field: ZakatFormField): ZakatFieldState =
    (assetFields + liabilityFields).first { state -> state.field == field }

@Composable
private fun SectionHeading(@StringRes resId: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
internal fun AmountField(
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
 * [compact] lays short options out as equal-width chips in rows of [COMPACT_PER_ROW]; long sentence-style
 * options stay as stacked rows so their wording is never truncated.
 */
@Composable
internal fun <T> ChoiceGroup(
    @StringRes titleRes: Int,
    choices: List<ZakatChoice<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes helpRes: Int? = null,
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
internal fun ChoiceChip(
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

package io.github.towkirahmedrony.salah.feature.tasbih

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.TasbihBeadsGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahProgressBar
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSwitch
import org.koin.androidx.compose.koinViewModel

/**
 * The Tasbih counter.
 *
 * ## What this screen is
 *
 * One number, one target, and the controls that move them. It counts and it remembers; it contains no
 * dhikr text, no wording to recite and no guidance about what to count, and it deliberately says nothing
 * about either. Salah does not carry religious content for this feature — see `docs/TASBIH_COUNTER.md` §1 —
 * so the screen's job is to be an excellent counter and to leave that question to the user.
 *
 * ## The hierarchy, top to bottom
 *
 * The reading comes first and is the largest thing on the screen: the count in the display typeface, the
 * target beneath it, and a progress track under both. Then the one action the screen exists for — a
 * full-width count control, tall enough to hit without looking — and beside it the small corrections: take
 * back a tap, reset the count, start a new session. The target picker, the haptics switch and the history
 * follow, in that order, because that is the order they are reached in: count, correct, adjust, review.
 *
 * ## The three ways to lose a count, and what stops each
 *
 *  - **A tap that lands twice.** The count surface is a single Material `Button` with one `onClick`; nothing
 *    counts from an animation callback, a ripple, or a lifecycle event. One tap is one call, and one call
 *    is one increment.
 *  - **A write that overwrites a newer one.** No action writes a session it read: each hands the repository
 *    a function, applied inside the store's own transaction, against the value stored there. Ten taps in a
 *    second are ten increments, not ten writes of the same number. See [TasbihViewModel].
 *  - **An accidental destructive action.** Resetting the count and starting a new session each pass through
 *    a confirmation dialog that states exactly what happens; clearing the history passes through a third.
 *    Nothing in this screen destroys anything on a single tap.
 *
 * ## Haptics
 *
 * A count gives a short vibration when the switch below is on and the platform provides one. It is a
 * courtesy, never a requirement: the increment happens whether or not a haptic is played, a device without
 * a vibrator behaves exactly like one with the switch off, and the platform's own touch-feedback setting is
 * honoured underneath by `performHapticFeedback` itself. Nothing here requests a permission — Salah already
 * declares `VIBRATE` for its prayer reminders, and a haptic performed through the view requires nothing
 * further.
 *
 * ## Accessibility
 *
 * The reading — the label, the number and the target — is **one** merged node whose description is the
 * sentence "Current count 33, target 33", so a screen reader announces the whole state in one focus rather
 * than three. It is additionally marked as a live region: a counter whose new value is never announced is a
 * counter a screen-reader user cannot use, because the number is the *result* of the action and there is
 * nothing else to read afterwards. The progress track carries its own description; the target options are
 * `selectable` with the radio-button role, so the selected one is announced as selected; and every control
 * is at least 48dp tall.
 *
 * ## Font scale and insets
 *
 * The count is drawn at one of three type sizes chosen by the device's font scale, so six digits still fit
 * on one line at the largest setting rather than being clipped. The whole screen is a single scrolling
 * column, so a long history or a tall reading never pushes a control off the bottom. Insets are not handled
 * here: the screen is drawn inside the shell's `Scaffold`, which already applies them — see `SalahApp`.
 */
@Composable
fun TasbihScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasbihViewModel = koinViewModel(),
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
            title = stringResource(R.string.tasbih_title),
            backContentDescription = stringResource(R.string.tasbih_back),
            onBack = onBack,
        )
        Text(
            text = stringResource(R.string.tasbih_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val state = uiState) {
            TasbihUiState.Loading -> TasbihLoadingCard()
            is TasbihUiState.Ready -> TasbihContent(
                state = state,
                onCount = viewModel::count,
                onUndo = viewModel::undo,
                onReset = viewModel::reset,
                onStartNewSession = viewModel::startNewSession,
                onSelectTarget = viewModel::setTarget,
                onSetHapticsEnabled = viewModel::setHapticsEnabled,
                onClearHistory = viewModel::clearHistory,
            )
        }
    }
}

/**
 * Everything below the heading, once the store has been read.
 *
 * The three confirmation dialogs live here rather than inside the cards that open them, because each is
 * about the counter as a whole — clearing the history changes the list below the button that opens it, and
 * starting a new session changes every reading on the screen.
 */
@Composable
private fun TasbihContent(
    state: TasbihUiState.Ready,
    onCount: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onStartNewSession: () -> Unit,
    onSelectTarget: (Int) -> Unit,
    onSetHapticsEnabled: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Once a dialog is open it stays open across the recompositions the store's emissions cause, which is
    // why the flags are remembered here and not derived from the state.
    var confirmingReset by remember { mutableStateOf(false) }
    var confirmingNewSession by remember { mutableStateOf(false) }
    var confirmingClearHistory by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { message -> SaveFailedNotice(message = message) }

        CountCard(state = state)

        CountControl(
            enabled = state.canCount,
            hapticsEnabled = state.hapticsEnabled,
            onCount = onCount,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onUndo,
                    enabled = state.canUndo,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.tasbih_action_undo))
                }
                TextButton(
                    onClick = { confirmingReset = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.tasbih_action_reset))
                }
            }
            TextButton(
                onClick = { confirmingNewSession = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.tasbih_action_new_session))
            }
        }

        TargetCard(state = state, onSelectTarget = onSelectTarget)
        HapticsCard(
            enabled = state.hapticsEnabled,
            onEnabledChange = onSetHapticsEnabled,
        )
        HistoryCard(
            state = state,
            onRequestClearHistory = { confirmingClearHistory = true },
        )
    }

    if (confirmingReset) {
        ConfirmationDialog(
            title = stringResource(R.string.tasbih_reset_dialog_title),
            body = stringResource(R.string.tasbih_reset_dialog_body),
            confirmLabel = stringResource(R.string.tasbih_reset_dialog_confirm),
            onConfirm = {
                confirmingReset = false
                onReset()
            },
            onDismiss = { confirmingReset = false },
        )
    }

    if (confirmingNewSession) {
        ConfirmationDialog(
            title = stringResource(R.string.tasbih_new_session_dialog_title),
            body = stringResource(R.string.tasbih_new_session_dialog_body),
            confirmLabel = stringResource(R.string.tasbih_new_session_dialog_confirm),
            onConfirm = {
                confirmingNewSession = false
                onStartNewSession()
            },
            onDismiss = { confirmingNewSession = false },
        )
    }

    if (confirmingClearHistory) {
        ConfirmationDialog(
            title = stringResource(R.string.tasbih_history_clear_dialog_title),
            body = stringResource(R.string.tasbih_history_clear_dialog_body),
            confirmLabel = stringResource(R.string.tasbih_history_clear_dialog_confirm),
            onConfirm = {
                confirmingClearHistory = false
                onClearHistory()
            },
            onDismiss = { confirmingClearHistory = false },
        )
    }
}

/**
 * The reading: the count, the target and the progress towards it.
 *
 * The three lines are one accessibility node — see the screen's own documentation for why — so the
 * description is resolved once here and attached to the column rather than to each line.
 */
@Composable
private fun CountCard(
    state: TasbihUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val readingDescription = stringResource(
        R.string.tasbih_count_accessibility,
        state.countDigits,
        state.targetDigits,
    )

    SalahSectionCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = readingDescription
                    // Polite rather than assertive: the count is announced once the user's gesture has been
                    // handled, not in place of it.
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.tasbih_count_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.countDigits,
                style = countTextStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.tasbih_target_value, state.targetDigits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        SalahProgressBar(
            fraction = state.progress,
            modifier = Modifier.fillMaxWidth(),
            contentDescription = stringResource(R.string.tasbih_progress_accessibility),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // The completion state is stated in words as well as drawn, so it is legible at a glance and in a
        // screenshot, and so it does not depend on a colour a user may not distinguish. The line about
        // counting on appears only once the count has actually passed the target: before that it would be
        // advice about an action the user has not taken.
        if (state.isComplete) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SalahStatusPill(
                    text = stringResource(R.string.tasbih_status_complete),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (state.hasCountedPastTarget) {
                    Text(
                        text = stringResource(R.string.tasbih_status_complete_continue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(
                    R.string.tasbih_status_remaining,
                    state.remainingDigits ?: state.targetDigits,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The count itself: the one control the feature exists for.
 *
 * Full width, at least 96dp tall, and labelled in the title typeface so it can be read without aiming. It
 * is disabled only at the counter's ceiling, which is the single case where a tap cannot be recorded — a
 * disabled control that says nothing is better than a control that appears to work and does not.
 */
@Composable
private fun CountControl(
    enabled: Boolean,
    hapticsEnabled: Boolean,
    onCount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    Button(
        onClick = {
            // Played before the write so the feedback is immediate: the store's own emission is what moves
            // the number, and waiting for it would make every count feel late.
            if (hapticsEnabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            onCount()
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
    ) {
        TasbihBeadsGlyph(tint = LocalContentColor.current, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.tasbih_action_count),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** One row of target options, laid out so every chip is the same width and at least 48dp tall. */
@Composable
private fun TargetCard(
    state: TasbihUiState.Ready,
    onSelectTarget: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.tasbih_target_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.tasbih_target_section_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        state.targetChoices.chunked(TARGET_CHIPS_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { choice ->
                    TargetChip(
                        choice = choice,
                        onSelect = onSelectTarget,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a short final row's chips the width of the rows above it, so the selector reads as
                // a grid rather than as a row that ran out.
                repeat(TARGET_CHIPS_PER_ROW - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * One target.
 *
 * `selectable` with the radio-button role rather than a `Chip`: the semantics are the ones a set of mutually
 * exclusive options should have, and a screen reader announces the choice and its state together. The
 * description spells the number out — "target 33" — because the visible label is the number alone.
 */
@Composable
private fun TargetChip(
    choice: TasbihTargetChoice,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choiceDescription = stringResource(R.string.tasbih_target_value, choice.label)

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (choice.selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            // `selectable` carries no `onClickLabel` of its own, so the option's meaning is stated once, by
            // the semantics below — which is also the node a screen reader reads when the chip is focused.
            .selectable(
                selected = choice.selected,
                role = Role.RadioButton,
                onClick = { onSelect(choice.target) },
            )
            .semantics { contentDescription = choiceDescription }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = choice.label,
            style = MaterialTheme.typography.titleMedium,
            color = if (choice.selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** The haptics preference, as a switch beside its own explanation. */
@Composable
private fun HapticsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tasbih_haptics_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.tasbih_haptics_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            SalahSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

/**
 * The finished sessions, newest first, with the retention rule stated.
 *
 * The rule is stated rather than left implicit because the list is capped: a user with thirty-one sessions
 * should be able to find out why the thirty-first is not there without reading a document. Nothing is
 * hidden — the sentence and the list are the same fact, and clearing is a confirmed action that says it
 * leaves the count in progress alone.
 */
@Composable
private fun HistoryCard(
    state: TasbihUiState.Ready,
    onRequestClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.tasbih_history_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        if (state.history.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tasbih_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SalahSectionCard
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.tasbih_history_retention, state.historyLimitDigits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))

        state.history.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            key(row.id) {
                HistoryRow(row = row)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
            onClick = onRequestClearHistory,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.tasbih_history_clear))
        }
    }
}

/**
 * One finished session.
 *
 * The whole row is one accessibility node: the date, the count, the target and whether it was completed are
 * four fragments of one fact, and reading them as four separate stops is worse than reading the sentence.
 */
@Composable
private fun HistoryRow(
    row: TasbihHistoryRow,
    modifier: Modifier = Modifier,
) {
    val status = stringResource(
        if (row.isComplete) R.string.tasbih_history_completed else R.string.tasbih_history_incomplete,
    )
    val description = stringResource(
        R.string.tasbih_history_entry_accessibility,
        row.timestampLabel,
        row.countDigits,
        row.targetDigits,
        status,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tasbih_history_values, row.countDigits, row.targetDigits),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            SalahStatusPill(
                text = status,
                containerColor = if (row.isComplete) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (row.isComplete) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = row.timestampLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A store that could not be written.
 *
 * Stated in the error roles rather than as a quiet note: the reading on screen is then one the device does
 * not have, and that is a fact about the number itself rather than about a section that has no data.
 */
@Composable
private fun SaveFailedNotice(
    message: TasbihMessage,
    modifier: Modifier = Modifier,
) {
    val text = when (message) {
        TasbihMessage.SAVE_FAILED -> stringResource(R.string.tasbih_save_failed)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * The confirmation every destructive action passes through.
 *
 * One component rather than three dialogs that agree today: the three actions differ in their wording and in
 * nothing else, and a cancel that behaved differently in one of them would be a trap. Confirm first, dismiss
 * second, both real buttons with the platform's own touch targets.
 */
@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.tasbih_cancel))
            }
        },
    )
}

/** The shape of the counter, drawn while the store's first reading is on its way. */
@Composable
private fun TasbihLoadingCard(modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        SalahSkeletonBlock(height = 18.dp, modifier = Modifier.fillMaxWidth(0.4f))
        Spacer(modifier = Modifier.height(14.dp))
        SalahSkeletonBlock(height = 52.dp, modifier = Modifier.fillMaxWidth(0.55f))
        Spacer(modifier = Modifier.height(14.dp))
        SalahSkeletonBlock(height = 10.dp, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(14.dp))
        SalahSkeletonBlock(height = 18.dp, modifier = Modifier.fillMaxWidth(0.3f))
    }
}

/**
 * The count's type size, chosen by the device's font scale.
 *
 * The number is the one thing on this screen that can grow without the layout growing with it: a six-digit
 * reading at the largest display size would be clipped by a single fixed style, and a reading that is cut off
 * is worse than a smaller one. Three steps are enough to keep it on one line from the default scale up to the
 * largest setting the platform offers, and they are chosen from the scale rather than from the digit count so
 * that the number does not change size as it is counted — a reading that shrank at 10, 100 or 1000 would look
 * like a glitch.
 */
@Composable
private fun countTextStyle(): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    val typography = MaterialTheme.typography

    return when {
        fontScale >= LARGE_FONT_SCALE -> typography.headlineLarge
        fontScale >= MEDIUM_FONT_SCALE -> typography.displaySmall
        else -> typography.displayMedium
    }
}

/** How many target options share a row. Five offered targets therefore fill a row of three and a row of two. */
private const val TARGET_CHIPS_PER_ROW = 3

/** Font scale at which the count drops a step, so its largest reading still fits one line. */
private const val MEDIUM_FONT_SCALE = 1.3f

/** Font scale at which it drops again. */
private const val LARGE_FONT_SCALE = 1.6f

package io.github.towkirahmedrony.salah.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The row vocabulary the notification and calendar settings pages are built from.
 *
 * Both pages hold a lot of small controls, and giving each control its own card was what made them
 * read as documentation rather than as settings. These rows let one card hold a whole group: a title
 * at the top, one line per setting, and a hairline rule between them. They exist once, here, so the
 * two pages cannot drift into two slightly different looks.
 *
 * Every row keeps the platform's own control (Material's `Switch`, `RadioButton`, `TextButton`), which
 * is what preserves the 48dp touch targets, the switch/radio roles and the on/off announcements a
 * screen reader makes. The rows only decide layout, spacing and type.
 *
 * Titles are `bodyLarge` and group titles are `titleMedium`: hierarchy comes from weight and position
 * rather than from making the type large, so a settings page stays scannable without shouting.
 */

/** The heading of one group inside a card, or of a group of cards. */
@Composable
fun SettingsGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** The one-line explanation under a group title or a row, when a line is genuinely useful. */
@Composable
fun SettingsSupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The rule between two rows of the same group. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A setting with an on/off switch.
 *
 * [supporting] is optional on purpose: a switch whose label already says what it does does not need
 * a second line repeating it, and a list of two-line rows is what this page is being cleaned of.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SalahSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * One option of a radio group.
 *
 * The row is the selectable target and the `RadioButton` is non-interactive, so TalkBack announces a
 * single radio option per row instead of a row and a button that both claim the same choice. The hint
 * is drawn only when the caller passes it, which is how the sound list stays short: the explanation
 * of what will be heard is worth one row, not three.
 */
@Composable
fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A minus/value/plus control.
 *
 * Deliberately a wrapping row rather than a full-width one, so a caller can put a label beside it or
 * centre it under a group title. [emphasized] is what marks a value that has moved away from its
 * default; the colour is the only change, so the control does not grow when the value changes.
 */
@Composable
fun SettingsStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    valueMinWidth: Dp = 104.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onDecrease,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            enabled = enabled,
        ) {
            Text(text = "−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = valueMinWidth),
        )
        TextButton(
            onClick = onIncrease,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            enabled = enabled,
        ) {
            Text(text = "+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A row whose right-hand side is a [SettingsStepper]. */
@Composable
fun SettingsStepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 8.dp),
        )
        SettingsStepper(
            value = value,
            onDecrease = onDecrease,
            onIncrease = onIncrease,
            emphasized = emphasized,
            enabled = enabled,
            valueMinWidth = 88.dp,
        )
    }
}

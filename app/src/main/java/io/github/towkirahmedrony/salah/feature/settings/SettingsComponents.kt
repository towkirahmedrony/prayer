package io.github.towkirahmedrony.salah.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.BellGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ChevronRightGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ClockGlyph
import io.github.towkirahmedrony.salah.core.designsystem.DomeGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinGlyph
import io.github.towkirahmedrony.salah.core.designsystem.MoonGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SlidersGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader

/**
 * The frame every settings subpage is built in.
 *
 * One column, one scroll, one header and one gap between sections, so seven screens written by the
 * same hand cannot disagree about margins. The scroll belongs here rather than in each screen because
 * every one of them can outgrow a small display — the calculation methods alone are three groups of
 * options — and a settings page that cannot be scrolled hides whichever control happens to be last.
 *
 * The vertical padding starts at 8dp rather than the 16dp an untitled screen would use: the header's
 * back action is an `IconButton` with its own 12dp of internal padding, and adding the usual 16dp on
 * top of it would push the title further down than the screen's own title bar would sit.
 */
@Composable
internal fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SalahSubpageHeader(
            title = title,
            backContentDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )
        content()
    }
}

/**
 * One category on the Settings overview: an icon, a name, what it contains, and a chevron.
 *
 * The whole card is the touch target rather than the title alone, because the row is the navigation
 * control; its height is set by the icon and the two lines of text, which together exceed the 48dp
 * minimum. The description is two lines at most: a category whose description wraps further would turn
 * the overview into the wall of text this structure replaces.
 */
@Composable
internal fun SettingsCategoryRow(
    category: SettingsCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                SettingsCategoryGlyph(
                    category = category,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(category.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChevronRightGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The category's mark.
 *
 * A `when` over the enum rather than a composable lambda held in the enum, so the icon is resolved by
 * the compiler and adding a category without giving it an icon is a compile error rather than a blank
 * square. The marks are Salah's own canvas drawings, so the overview needs no icon dependency and stays
 * in the same visual language as the bottom bar.
 */
@Composable
private fun SettingsCategoryGlyph(category: SettingsCategory, tint: Color) {
    when (category) {
        SettingsCategory.GENERAL -> SlidersGlyph(tint = tint)
        SettingsCategory.APPEARANCE -> MoonGlyph(tint = tint)
        SettingsCategory.PRAYER_TIMES -> ClockGlyph(tint = tint)
        SettingsCategory.NOTIFICATIONS -> BellGlyph(tint = tint)
        SettingsCategory.LOCATION -> LocationPinGlyph(tint = tint)
        SettingsCategory.CALENDAR -> CalendarGlyph(tint = tint)
        SettingsCategory.ABOUT -> DomeGlyph(tint = tint)
    }
}

/**
 * A group of mutually exclusive options, rendered as an accessible radio list.
 *
 * Radio rows rather than a dropdown: every option is visible at once, which is what makes a list of
 * calculation methods reviewable instead of something to hunt through. The row owns the selection and
 * the `RadioButton` is non-interactive, so TalkBack announces one selectable option per row instead of
 * a row and a button that both claim the same choice.
 */
@Composable
internal fun <T> SettingsOptionGroup(
    title: String,
    body: String?,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> Int,
    hint: @Composable (T) -> Int?,
    onSelect: (T) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    body?.let {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            SettingsChoiceRow(
                label = stringResource(label(option)),
                hint = hint(option)?.let { stringResource(it) },
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun SettingsChoiceRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
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
 * One prayer's manual correction: a minute count between two steppers.
 *
 * Buttons rather than a slider, because the sensible range is ±60 minutes and each tap is one minute —
 * a drag would land on values the user did not mean, and the signed number is the only thing worth
 * reading here.
 */
@Composable
internal fun SettingsAdjustmentRow(
    label: String,
    minutes: Int,
    @StringRes decreaseLabelRes: Int,
    @StringRes increaseLabelRes: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    // Resolved here rather than inside the `semantics` blocks below: those lambdas are not composable,
    // so a `stringResource` call cannot live in them.
    val decreaseLabel = stringResource(decreaseLabelRes)
    val increaseLabel = stringResource(increaseLabelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onDecrease,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                // The glyph is the only thing on the button, so the button itself carries the label:
                // "−" is announced as "minus" otherwise, which says nothing about what it changes.
                modifier = Modifier.semantics { contentDescription = decreaseLabel },
            ) {
                Text(text = "−", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = stringResource(R.string.settings_adjustment_minutes, signed(minutes)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (minutes == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            TextButton(
                onClick = onIncrease,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.semantics { contentDescription = increaseLabel },
            ) {
                Text(text = "+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Renders a signed minute count, so an adjustment never reads as an unsigned offset. */
private fun signed(minutes: Int): String = if (minutes > 0) "+$minutes" else minutes.toString()

package io.github.towkirahmedrony.salah.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CheckGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.theme.salahStyle
import io.github.towkirahmedrony.salah.core.designsystem.theme.themeColorScheme
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.ThemePreset
import io.github.towkirahmedrony.salah.core.model.UiStyle
import org.koin.androidx.compose.koinViewModel

/**
 * Appearance settings: the three independent choices that decide how Salah looks.
 *
 * The order is deliberate — colour first, then light/dark, then layout — because that is the order the
 * choices are noticed in: a palette is recognised at a glance, the mode decides whether it is day or
 * night, and the layout is felt rather than seen. Each group writes its own DataStore key through the
 * view model, so choosing a style cannot reset the palette or the mode, and every change reaches the
 * shell on the next emission without a restart.
 *
 * **The theme grid shows the theme, not a label.** Each tile draws its own palette's background, primary
 * and secondary, so a preview is the actual colours rather than a description of them. The selected tile
 * is marked by a heavier primary border and a check, and the tile itself carries the radio-button role,
 * so the selection is stated to a screen reader as well as drawn.
 *
 * **The style grid shows the geometry.** Each tile renders three bars inside a rounded container using
 * that style's own corner radius, card padding and section spacing, so Minimal's airiness and Compact's
 * density are visible before the choice is made. The preview uses the tokens the rest of the app reads,
 * not a second set of numbers written for the tile.
 *
 * Nothing else lives here: no font-size slider, no accent picker and no long paragraphs. Three real
 * preferences are offered once each, with no decorative control to fill the page.
 */
@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsSubpage(
        title = stringResource(R.string.settings_theme_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_theme_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppearanceSectionTitle(R.string.settings_color_theme_title)
        ThemePresetGrid(
            selected = uiState.themePreset,
            onSelect = viewModel::setThemePreset,
        )

        SalahSectionCard {
            SettingsOptionGroup(
                title = stringResource(R.string.settings_appearance_mode_title),
                body = null,
                options = ThemeMode.entries,
                selected = uiState.themeMode,
                label = { mode -> themeModeLabelRes(mode) },
                hint = { null },
                onSelect = viewModel::setThemeMode,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        AppearanceSectionTitle(R.string.settings_ui_style_title)
        UiStyleGrid(
            selected = uiState.uiStyle,
            onSelect = viewModel::setUiStyle,
        )
    }
}

@Composable
private fun AppearanceSectionTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The preset tiles, two per row so each preview stays wide enough to read the palette. */
@Composable
private fun ThemePresetGrid(
    selected: ThemePreset,
    onSelect: (ThemePreset) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemePreset.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { preset ->
                    ThemePresetTile(
                        preset = preset,
                        selected = preset == selected,
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemePresetTile(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The preview is the preset's own light palette: a palette is a colour family, and showing its
    // light expression keeps all eight tiles comparable however the app is currently set.
    val preview = themeColorScheme(preset, dark = false)
    val shape = MaterialTheme.shapes.medium
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(preview.background),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaletteDot(color = preview.primary)
                PaletteDot(color = preview.secondary)
                PaletteDot(color = preview.surface)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(themePresetLabelRes(preset)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                CheckGlyph(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PaletteDot(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** The four layout styles, each previewed with the geometry its own tokens define. */
@Composable
private fun UiStyleGrid(
    selected: UiStyle,
    onSelect: (UiStyle) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UiStyle.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { uiStyle ->
                    UiStyleTile(
                        uiStyle = uiStyle,
                        selected = uiStyle == selected,
                        onClick = { onSelect(uiStyle) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UiStyleTile(
    uiStyle: UiStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        StylePreview(
            uiStyle = uiStyle,
            modifier = Modifier.padding(10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(uiStyleLabelRes(uiStyle)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                CheckGlyph(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * A miniature of the layout a style produces: three rows inside a rounded container whose padding,
 * spacing and corner radius are the style's own tokens. It is deliberately abstract — it shows rhythm
 * and density, not content.
 */
@Composable
private fun StylePreview(
    uiStyle: UiStyle,
    modifier: Modifier = Modifier,
) {
    val tokens = salahStyle(uiStyle)
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(tokens.shapes.small)
            .background(scheme.surfaceVariant)
            .padding(tokens.cardPadding * 0.55f),
        verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing * 0.25f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(4.dp)
                .clip(tokens.shapes.extraSmall)
                .background(scheme.primary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(4.dp)
                .clip(tokens.shapes.extraSmall)
                .background(scheme.outlineVariant),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.48f)
                .height(4.dp)
                .clip(tokens.shapes.extraSmall)
                .background(scheme.outlineVariant),
        )
    }
}

private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@StringRes
internal fun themePresetLabelRes(preset: ThemePreset): Int = when (preset) {
    ThemePreset.DEFAULT -> R.string.theme_default
    ThemePreset.EMERALD -> R.string.theme_emerald
    ThemePreset.OCEAN -> R.string.theme_ocean
    ThemePreset.MIDNIGHT -> R.string.theme_midnight
    ThemePreset.ROSE -> R.string.theme_rose
    ThemePreset.LAVENDER -> R.string.theme_lavender
    ThemePreset.AMOLED -> R.string.theme_amoled
    ThemePreset.SAND -> R.string.theme_sand
}

@StringRes
internal fun uiStyleLabelRes(uiStyle: UiStyle): Int = when (uiStyle) {
    UiStyle.CLASSIC -> R.string.ui_style_classic
    UiStyle.MINIMAL -> R.string.ui_style_minimal
    UiStyle.MODERN -> R.string.ui_style_modern
    UiStyle.COMPACT -> R.string.ui_style_compact
}

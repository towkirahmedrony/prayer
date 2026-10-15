package io.github.towkirahmedrony.salah.feature.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CloseGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SearchGlyph
import io.github.towkirahmedrony.salah.core.domain.location.LocationNameLanguage
import io.github.towkirahmedrony.salah.core.domain.location.LocationSearchLevel
import io.github.towkirahmedrony.salah.core.domain.location.LocationSearchResult

/**
 * The language the location *names* on this screen are rendered in, derived from the resources the
 * composition is actually drawing with.
 *
 * **Why the resources and not `AppLanguage`.** The shell pins `LocalResources` to the selected
 * interface language (or leaves the device's in place for "follow the device"), which is exactly what
 * decides how every `stringResource` on this screen resolves. Reading the same source means a place
 * name and the label beside it can never disagree: `AppLanguage.BANGLA`, `AppLanguage.SYSTEM` on a
 * Bangla device, and a moment where the device locale itself changed all answer correctly here,
 * without this layer having to re-derive the preference resolution.
 */
@Composable
internal fun currentNameLanguage(): LocationNameLanguage {
    val locale = LocalResources.current.configuration.locales[0]
    return if (locale.language.equals("bn", ignoreCase = true)) {
        LocationNameLanguage.BANGLA
    } else {
        LocationNameLanguage.ENGLISH
    }
}

/**
 * The searchable half of manual location selection: a field, its live status, and the matches —
 * shared by the first-launch setup card and the location screen's picker so there is one search, not
 * two.
 *
 * Results appear as the user types (debounced and cancellable in the view model); there is no search
 * button to press. Each row shows the matched name in the active language with its level and
 * administrative context underneath, so two places that share a name are told apart by their parents
 * — `Gazipur` the district from `Gazipur Sadar` the upazila from either one's division. Tapping a row
 * fills the hierarchy below it through the same `AreaSelection` rules a dropdown tap uses; nothing is
 * saved until the user confirms.
 *
 * Everything the field says — label, placeholder, clear action, the "searching", empty and failed
 * states — comes from string resources, so it follows the interface language. The *results'* names
 * come from the dataset in [LocationUiState.nameLanguage], which is what makes Bangla search return
 * Bangla names and English search return English ones.
 */
@Composable
internal fun LocationSearchField(
    state: LocationUiState,
    onQueryChange: (String) -> Unit,
    onSelectResult: (LocationSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clearLabel = stringResource(R.string.location_search_clear)

    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = stringResource(R.string.location_search_label)) },
        placeholder = { Text(text = stringResource(R.string.location_search_hint)) },
        singleLine = true,
        leadingIcon = {
            SearchGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (state.hasSearchQuery) {
                IconButton(onClick = { onQueryChange("") }) {
                    CloseGlyph(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = clearLabel },
                    )
                }
            }
        },
        // Results update while typing, so the action key only re-runs the current query rather than
        // navigating anywhere — there is no separate results page for it to submit to.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onQueryChange(state.searchQuery) }),
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )

    if (!state.hasSearchQuery) return

    if (state.isSearching) {
        Spacer(modifier = Modifier.height(8.dp))
        val searchingLabel = stringResource(R.string.location_search_in_progress)
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = searchingLabel },
        )
    }

    when {
        state.searchFailed -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.location_search_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Empty is only reported when nothing is in flight: "no matches yet" and "still looking" are
        // different answers, and only the first is one the user should act on.
        state.searchResults.isEmpty() && !state.isSearching -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.location_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> SearchResultList(
            results = state.searchResults,
            onSelectResult = onSelectResult,
        )
    }
}

/**
 * The matches, as one row each.
 *
 * A plain column rather than a lazy list: the result set is capped at
 * `LocationSearch.MAX_RESULTS`, so every row exists at once and the enclosing scroll view already
 * handles the rest — a nested scrollable here would take the swipes the screen needs.
 */
@Composable
private fun SearchResultList(
    results: List<LocationSearchResult>,
    onSelectResult: (LocationSearchResult) -> Unit,
) {
    Spacer(modifier = Modifier.height(4.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        results.forEachIndexed { index, result ->
            val levelLabel = stringResource(result.level.labelRes())
            val context = result.contextNames.joinToString(separator = " → ")
            // The row owns its semantics, so TalkBack reads the name, the level and the ancestors as
            // one choice — "Gazipur Sadar, Upazila / Thana, Dhaka → Gazipur" — and announces it as a
            // button rather than as three separate pieces of text.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onSelectResult(result) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = result.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (context.isEmpty()) levelLabel else "$levelLabel · $context",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index < results.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** The level's name, in the interface language — the same words the dropdowns use for each level. */
private fun LocationSearchLevel.labelRes(): Int = when (this) {
    LocationSearchLevel.DIVISION -> R.string.location_division_label
    LocationSearchLevel.DISTRICT -> R.string.location_district_label
    LocationSearchLevel.UPAZILA -> R.string.location_upazila_label
    LocationSearchLevel.LOCALITY -> R.string.location_locality_label
}

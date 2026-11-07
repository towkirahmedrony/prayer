package io.github.towkirahmedrony.salah.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.core.designsystem.CheckGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ChevronDownGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CloseGlyph

/**
 * One choice in a dropdown's list.
 *
 * A plain id and the label to draw, deliberately not the area model: the caller owns what an id means
 * and which language its name is in, and the field only has to list what it is given and report which
 * row was tapped. That is what keeps this component reusable for any single choice, not only for the
 * location hierarchy.
 */
data class DropdownItem(
    val id: String,
    val label: String,
)

/**
 * The words a long list's menu needs in order to be searchable.
 *
 * Passed in rather than read here, because this component is part of the design system and the words
 * belong to the screen: the same field is used on a screen in Bangla and on one in English, and both
 * take their labels from the string resources of the composition around them.
 *
 * @param label the search field's own label, shown inside the menu.
 * @param emptyMessage what to say when a typed query matches nothing.
 * @param clearContentDescription the accessible name of the action that empties the query. The glyph
 *   is a drawing with no text of its own, so this is the only thing a screen reader has to go on.
 */
data class DropdownSearch(
    val label: String,
    val emptyMessage: String,
    val clearContentDescription: String,
)

/**
 * How many options a list must exceed before its menu offers a search box.
 *
 * A division list is eight rows and a district list is at most about two dozen; both are faster to
 * scan than to search. An upazila/thana list can be longer than a screen, which is the case this
 * threshold exists for.
 */
internal const val DROPDOWN_SEARCH_THRESHOLD: Int = 12

/** True when [count] options are enough that a search box earns its place in the menu. */
internal fun dropdownSearchApplies(count: Int): Boolean = count > DROPDOWN_SEARCH_THRESHOLD

/**
 * The options [query] keeps, or every option when the query is blank.
 *
 * A **substring** match, not a prefix one: someone typing `sadar` is looking for `Bandarban Sadar`,
 * and a prefix-only filter would report that there is nothing there. Case is ignored for the Latin
 * names and the Bangla names are matched on their own characters, because the dataset carries both and
 * the list shows whichever language the screen is rendering in.
 */
internal fun dropdownItemsFor(items: List<DropdownItem>, query: String): List<DropdownItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return items
    return items.filter { item -> item.label.contains(needle, ignoreCase = true) }
}

/** The corner radius of the menu surface: softer than the field, so the two read as a pair. */
private val MENU_CORNER_RADIUS = 16.dp

/** The elevation that lifts the menu off the card, enough to read as a layer above it. */
private val MENU_ELEVATION = 8.dp

/** The gap drawn between the field's bottom edge and the menu's surface. */
private val MENU_GAP = 6.dp

/** The tallest the menu may grow: past this the list scrolls inside it instead. */
private val MENU_MAX_HEIGHT = 280.dp

/**
 * The tallest the scrolling list may grow inside a menu that also carries a search field.
 *
 * [MENU_MAX_HEIGHT] minus the search field and the menu's own edge padding, so a searchable menu and a
 * plain one are the same overall height rather than the searchable one standing taller.
 */
private val MENU_LIST_MAX_HEIGHT = 200.dp

/** The minimum height of one option, matching the platform's own menu row. */
private val MENU_ITEM_MIN_HEIGHT = 48.dp

/** How long the chevron takes to turn over when the menu opens or closes. */
private const val CHEVRON_ROTATION_MILLIS: Int = 200

/** The chevron's drawn size: the field's 56dp height leaves room for a mark this size. */
private val CHEVRON_SIZE = 18.dp

/** The selected row's check mark. */
private val CHECK_SIZE = 18.dp

/**
 * How much of its colour a disabled field keeps.
 *
 * Dimmed rather than hidden: the field is still a control the user will be able to use, so it stays
 * legible as one — its label, border, value and chevron all step back together.
 */
private const val DISABLED_CONTENT_ALPHA: Float = 0.5f

/**
 * A selection field that opens its options as a menu attached to the field itself.
 *
 * **Why it is built on [ExposedDropdownMenuBox].** A plain `DropdownMenu` is positioned by whatever
 * layout node happens to contain it, has no relationship with the control that opened it, and sizes
 * itself to its longest row — which is how a menu ends up floating at the top of a scrolling screen,
 * narrower than its field. The exposed-dropdown APIs exist to avoid exactly that: the field carries
 * `Modifier.menuAnchor`, so the field *is* the anchor, the menu is placed against the anchor's real
 * bounds in window coordinates (below it, or above it when there is no room below), and
 * `ExposedDropdownMenu` constrains its width to the anchor's width. Nothing here calculates an offset
 * by hand.
 *
 * **What it owns.** The field's appearance in every state (empty, selected, open, disabled), the
 * chevron's rotation, the menu's surface, the selected row's tint and check mark, and the search box
 * a long list gets. It does **not** own which menu is open or what a selection means: `expanded` comes
 * in and every change goes back out, so one caller can keep a single open menu across several fields
 * (the location picker does) and the cascading rules stay in the screen and view model that already
 * implement them.
 *
 * **Search.** A menu with more than [DROPDOWN_SEARCH_THRESHOLD] options grows a fixed search field
 * above its list: the query filters the rows as it is typed, the field stays put while the list
 * scrolls, and a query that matches nothing says so instead of showing an empty surface. A closed menu
 * forgets its query, so reopening a list always starts from the whole list.
 *
 * @param label the field's own label, floating above the value once there is one.
 * @param selectedId the id of the current selection, used to mark its row.
 * @param selectedLabel the current selection as it should be read in the field, or `null` when nothing
 *   is selected yet.
 * @param items every option the list may show, in the order it should show them.
 * @param expanded whether this field's menu is open. The caller owns it so that only one field at a
 *   time can be open; a disabled field is never expanded, whatever the caller says.
 * @param onExpandedChange called with the requested state when the field is tapped or the menu is
 *   dismissed. Ignored entirely while the field is disabled.
 * @param onSelect called with the tapped option's id. The menu closes first, so the caller sees a
 *   selection and a closed menu together.
 * @param enabled whether the field can be used at all. A disabled field cannot open a menu, and
 *   reports itself as disabled to accessibility services.
 * @param emptyMessage what to say inside the menu when there is nothing to list, for a field whose
 *   options depend on a parent that has not been chosen.
 * @param search the words a searchable menu needs, or `null` to never offer search. Even when given,
 *   it only applies to a list longer than [DROPDOWN_SEARCH_THRESHOLD].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDropdownField(
    label: String,
    selectedId: String?,
    selectedLabel: String?,
    items: List<DropdownItem>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    search: DropdownSearch? = null,
) {
    val colors = MaterialTheme.colorScheme
    val menuShape = RoundedCornerShape(MENU_CORNER_RADIUS)

    // A disabled field is closed by definition: the caller may still hold the last open state while it
    // disables the field (a parent change does exactly that), and that must not leave a menu attached
    // to a control the user can no longer use.
    val isOpen = expanded && enabled
    val searchable = search != null && dropdownSearchApplies(items.size)

    var query by remember { mutableStateOf("") }
    // The query belongs to one opening of the menu, not to the field: closing forgets it, so the list
    // never reappears already filtered by something the user typed minutes ago.
    LaunchedEffect(isOpen) { if (!isOpen) query = "" }

    val visibleItems = remember(items, query, searchable) {
        if (searchable) dropdownItemsFor(items, query) else items
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isOpen) 180f else 0f,
        animationSpec = tween(durationMillis = CHEVRON_ROTATION_MILLIS),
        label = "dropdownChevron",
    )

    val borderColor = when {
        !enabled -> colors.outline.copy(alpha = DISABLED_CONTENT_ALPHA)
        // The field is the anchor, so "open" is this control's active state: its border takes the
        // primary green while its list is on screen, in step with the chevron.
        isOpen -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val labelColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
        isOpen -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val valueColor =
        if (enabled) colors.onSurface else colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    val chevronTint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
        isOpen -> colors.primary
        else -> colors.onSurfaceVariant
    }

    ExposedDropdownMenuBox(
        expanded = isOpen,
        onExpandedChange = { open -> if (enabled) onExpandedChange(open) },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel.orEmpty(),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            // The value lives in the field's text slot, so a label floats out of the way as soon as
            // there is a selection and the chosen area is readable where the control is, not only in
            // the menu that is no longer open.
            label = { Text(text = label) },
            textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = {
                ChevronDownGlyph(
                    tint = chevronTint,
                    modifier = Modifier
                        .size(CHEVRON_SIZE)
                        .rotate(chevronRotation),
                )
            },
            shape = MaterialTheme.shapes.small,
            // Focused and unfocused resolve to the same tone because the open state is what this
            // control's border reports: the anchor takes focus when the menu opens, and the popup can
            // take it away again while the menu is still on screen, so neither native state on its own
            // would follow the menu.
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor,
                unfocusedBorderColor = borderColor,
                disabledBorderColor = borderColor,
                focusedLabelColor = labelColor,
                unfocusedLabelColor = labelColor,
                disabledLabelColor = labelColor,
                focusedTextColor = valueColor,
                unfocusedTextColor = valueColor,
                disabledTextColor = valueColor,
            ),
        )

        ExposedDropdownMenu(
            expanded = isOpen,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier
                // Drawn outside the menu's own surface, so it is a gap between the field and the menu
                // rather than padding inside the menu.
                .padding(top = MENU_GAP)
                .heightIn(max = MENU_MAX_HEIGHT),
            shape = menuShape,
            // The card's own surface, so the menu reads as part of the card rather than as a panel of
            // a different material on top of it.
            containerColor = colors.surface,
            tonalElevation = 0.dp,
            shadowElevation = MENU_ELEVATION,
        ) {
            if (searchable && search != null) {
                DropdownSearchField(
                    value = query,
                    onValueChange = { typed -> query = typed },
                    search = search,
                )
            }

            val message = when {
                visibleItems.isNotEmpty() -> null
                // Nothing to offer at all is a different answer from "your query matched nothing", and
                // the two are worth different words.
                items.isEmpty() -> emptyMessage
                else -> search?.emptyMessage
            }

            if (message != null) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else {
                // The list scrolls inside a menu that does not: on a small screen with the keyboard up,
                // the search field has to stay where the user's eyes and fingers left it.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MENU_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    visibleItems.forEach { item ->
                        DropdownOptionRow(
                            item = item,
                            selected = item.id == selectedId,
                            onClick = {
                                onExpandedChange(false)
                                onSelect(item.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One option in the menu.
 *
 * The selected row is tinted with the scheme's `primaryContainer` — the same mint the app uses for a
 * chosen tab — and carries a check mark at its end, so "this is the one" survives a colour-blind eye,
 * a dark canvas and a screen reader alike (the state is announced by [DropdownMenuItem]'s own
 * selectable semantics as well as drawn).
 */
@Composable
private fun DropdownOptionRow(
    item: DropdownItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val trailing: (@Composable () -> Unit)? = if (selected) {
        {
            CheckGlyph(
                tint = colors.onPrimaryContainer,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(CHECK_SIZE),
            )
        }
    } else {
        null
    }

    DropdownMenuItem(
        text = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                // Two lines for an administrative name that needs them, and an ellipsis only after
                // that: truncating a place name is worse than a taller row.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MENU_ITEM_MIN_HEIGHT)
            .background(if (selected) colors.primaryContainer else Color.Transparent),
        trailingIcon = trailing,
        // The menu's own item padding, which is the inset the anchor field's text uses.
        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
    )
}

/**
 * The search box a long list puts at the top of its menu.
 *
 * An outlined field rather than a bare text row so it reads as a control rather than as the first row
 * of the list, at the same 8dp radius the app's smaller controls use. Its clear action appears only
 * once there is something to clear, and carries an accessible name because the glyph has no text.
 */
@Composable
private fun DropdownSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    search: DropdownSearch,
) {
    val colors = MaterialTheme.colorScheme

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text(text = search.label) },
        textStyle = MaterialTheme.typography.bodyMedium,
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    CloseGlyph(
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = search.clearContentDescription },
                    )
                }
            }
        },
        // The list filters as the user types, so the action key only asks the keyboard to step aside
        // rather than submitting anything.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.extraSmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline,
            focusedLabelColor = colors.primary,
            unfocusedLabelColor = colors.onSurfaceVariant,
        ),
    )
}

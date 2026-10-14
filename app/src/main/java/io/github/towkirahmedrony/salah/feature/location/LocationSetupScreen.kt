package io.github.towkirahmedrony.salah.feature.location

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ChevronDownGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinFilledGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SalahBrandLogo
import io.github.towkirahmedrony.salah.core.designsystem.SignalGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The first-launch location screen, as designed: the app's mark, what Salah needs a location for,
 * and the two ways to give it one.
 *
 * **Where it sits.** The shell ([io.github.towkirahmedrony.salah.navigation.SalahApp]) shows this
 * screen *instead of* the navigation graph whenever
 * [io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState.requiresSetup] — no
 * bottom bar, and therefore no way to reach a screen that would have to invent a time. Completing
 * either option here saves a location, which is the only thing the gate looks at; the screen
 * disappears and the graph appears on the same recomposition, so nothing navigates anywhere.
 *
 * **One view model, two frames.** Everything below is read from and written to the existing
 * [LocationViewModel] — the same state and the same policy the location management screen uses.
 * Nothing about validating a position, resolving an area or reconciling the reminders lives here.
 *
 * **Both options always work.** Neither the manual card nor its button depends on location
 * permission, so a user who declines GPS is not trapped: the card below the divider is the whole
 * screen's second answer, not a fallback.
 *
 * **A device fix is not confirmed here.** Granting the permission and a fix that passes the
 * Bangladesh-only policy is the whole interaction: the location is stored where it lands and this
 * screen is replaced by the app on the next recomposition. Nothing is skipped in the process — the
 * position is still validated against the boundary, and a position outside Bangladesh is still
 * refused with its own message — but there is no "is this the right area?" card standing between the
 * user and the app on a fresh install, for a decision that is as reversible in Settings as any other
 * location change. `LocationScreen`, reached later from Settings, keeps that confirmation: there the
 * user is replacing a location they already have, and the nearest-match area name is worth a look
 * before it replaces something they chose.
 */
@Composable
fun LocationSetupScreen(
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.start()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshPermissionState()
    }

    // The permission decision lives in one place (LocationPermissionPolicy); this supplies the two
    // platform facts it needs. It is only ever invoked by the screen's own button, so displaying
    // this screen never prompts for anything.
    //
    // `saveOnArrival` is what makes this screen different from the location screen: granting the
    // permission and a verified fix is the whole interaction, and the app opens on the next
    // recomposition. There is no confirmation card here — see the class documentation.
    val onUseDeviceLocation = rememberDeviceLocationAction(
        viewModel = viewModel,
        permissionState = state.permissionState,
        saveOnArrival = true,
    )

    SetupContent(
        state = state,
        modifier = modifier,
        onUseDeviceLocation = onUseDeviceLocation,
        onOpenLocationSettings = {
            settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onSelectDivision = viewModel::selectDivision,
        onSelectDistrict = viewModel::selectDistrict,
        onSelectUpazila = viewModel::selectUpazila,
        onSaveSelection = viewModel::saveSelectedArea,
        onDismissMessage = viewModel::dismissMessage,
    )
}

/**
 * The screen body, as a function of the location state alone.
 *
 * Kept separate from [LocationSetupScreen] so the layout can be read against the design in one
 * pass: everything here is arrangement and wording, and every value in it comes from [state].
 */
@Composable
private fun SetupContent(
    state: LocationUiState,
    modifier: Modifier = Modifier,
    onUseDeviceLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onSelectDivision: (String) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onSelectUpazila: (String) -> Unit,
    onSaveSelection: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Branding()
        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = stringResource(R.string.location_setup_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.location_setup_subheading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))

        BangladeshMapArt(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))

        UseCurrentLocationCard(
            state = state,
            onUseDeviceLocation = onUseDeviceLocation,
        )

        state.message?.let { message ->
            Spacer(modifier = Modifier.height(14.dp))
            LocationMessageRow(message = message, onDismiss = onDismissMessage)
            // The two failures the user can fix from here, in the place the failure was reported.
            if (message.isAnswerableWithSettings) {
                TextButton(onClick = onOpenLocationSettings) {
                    Text(text = stringResource(R.string.location_action_open_location_settings))
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        OrSelectManuallyDivider()
        Spacer(modifier = Modifier.height(18.dp))

        ChooseLocationManuallyCard(
            state = state,
            onSelectDivision = onSelectDivision,
            onSelectDistrict = onSelectDistrict,
            onSelectUpazila = onSelectUpazila,
            onSaveSelection = onSaveSelection,
        )

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.location_setup_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.location_setup_made_for) + " " + FLAG_BANGLADESH,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The app's mark and name, centred, as on the design.
 *
 * The gold of the wordmark is the theme's accent rather than the brand's mid gold, because this screen's
 * canvas is the theme's: the mid gold measures about **2.4:1** on the cream canvas — under the 3:1 floor
 * even for type at this size — while the accent carries the value that is legible on whichever canvas is
 * active. In light mode that is a deeper gold; on the dark canvas, the muted one.
 */
@Composable
private fun Branding(modifier: Modifier = Modifier) {
    val accents = LocalSalahAccents.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The shared Salah logo — the same artwork as the launcher and splash icon.
        SalahBrandLogo(size = 56.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
            fontWeight = FontWeight.Bold,
            color = accents.goldText,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = accents.goldText,
        )
    }
}

/** The two options offered, separated by a rule with its own label, as on the design. */
@Composable
private fun OrSelectManuallyDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = stringResource(R.string.location_setup_or_divider),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** The device-fix option: what it does, the button that starts it, and what it is used for. */
@Composable
private fun UseCurrentLocationCard(
    state: LocationUiState,
    onUseDeviceLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalGlyph(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.location_setup_gps_title),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.location_setup_gps_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // One acquisition at a time: while a fix is in flight the button that would start another one
        // is not offered. There is no "waiting to be confirmed" state on this screen — a fix that
        // passes the policy is stored where it lands.
        Spacer(modifier = Modifier.height(14.dp))
        if (state.isAcquiring) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.location_gps_acquiring),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = onUseDeviceLocation,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) {
                Text(text = stringResource(R.string.location_setup_gps_action))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.location_setup_gps_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The manual option: the division → district → upazila/thana selection, and its confirmation.
 *
 * Every option is read from the bundled dataset through the view model — nothing is listed here —
 * and each level is offered only once its parent is chosen, because that is exactly the rule the
 * dataset's own hierarchy enforces.
 */
@Composable
private fun ChooseLocationManuallyCard(
    state: LocationUiState,
    onSelectDivision: (String) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onSelectUpazila: (String) -> Unit,
    onSaveSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocationPinFilledGlyph(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.location_setup_manual_title),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.location_setup_manual_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.isDatasetUsable && !state.isLoading) {
            // The dataset is the only thing that can list an area, so this is what there is to say
            // when it is missing. The device option above still works: it needs coordinates, not the
            // area list.
            Spacer(modifier = Modifier.height(12.dp))
            SalahUnavailableNote(text = stringResource(R.string.location_dataset_unavailable_body))
        }

        Spacer(modifier = Modifier.height(14.dp))
        if (state.isLoading) {
            // The read is a local file parse that finishes in milliseconds, and both levels below
            // wait on it, so a placeholder of the first field's shape is enough.
            SalahSkeletonBlock(height = 56.dp, modifier = Modifier.fillMaxWidth())
        } else {
            AreaDropdownField(
                label = stringResource(R.string.location_division_label),
                selected = state.divisionOptions.firstOrNull { area -> area.id == state.selection.divisionId },
                options = state.divisionOptions,
                enabled = state.isDatasetUsable,
                emptyMessage = null,
                onSelect = onSelectDivision,
            )
            Spacer(modifier = Modifier.height(12.dp))
            AreaDropdownField(
                label = stringResource(R.string.location_district_label),
                selected = state.districtOptions.firstOrNull { area -> area.id == state.selection.districtId },
                options = state.districtOptions,
                enabled = state.isDatasetUsable && state.selection.divisionId != null,
                emptyMessage = stringResource(R.string.location_picker_needs_division),
                onSelect = onSelectDistrict,
            )
            Spacer(modifier = Modifier.height(12.dp))
            AreaDropdownField(
                label = stringResource(R.string.location_upazila_label),
                selected = state.upazilaOptions.firstOrNull { area -> area.id == state.selection.upazilaId },
                options = state.upazilaOptions,
                enabled = state.isDatasetUsable && state.selection.districtId != null,
                emptyMessage = stringResource(R.string.location_picker_needs_district),
                onSelect = onSelectUpazila,
            )
        }

        // The chosen area stores its published reference coordinate, not the user's own position.
        // Said here, before the confirmation, rather than after it.
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.location_setup_area_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onSaveSelection,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSaveSelection,
            colors = ButtonDefaults.buttonColors(
                // The design's resting state for the not-yet-complete selection: the same button,
                // out of reach rather than absent, so the card does not change height.
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            ),
        ) {
            Text(text = stringResource(R.string.location_setup_manual_confirm))
        }
    }
}

/**
 * One level of the manual hierarchy, as an outlined field that opens a list of the dataset's areas.
 *
 * The field is read-only: it holds the chosen area and nothing else, and the list it opens is the
 * only way to change it. A read-only `OutlinedTextField` does not open anything by itself, so the
 * click is handled by a transparent layer over it — which is also what keeps the whole field, not
 * just its text, the touch target.
 */
@Composable
private fun AreaDropdownField(
    label: String,
    selected: BangladeshArea?,
    options: List<BangladeshArea>,
    enabled: Boolean,
    emptyMessage: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            label = { Text(text = label) },
            trailingIcon = {
                ChevronDownGlyph(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            },
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = enabled) { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                if (emptyMessage != null) {
                    DropdownMenuItem(
                        text = { Text(text = emptyMessage) },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                }
            } else {
                options.forEach { area ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = area.labelledName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(area.id)
                        },
                    )
                }
            }
        }
    }
}

/** True when the message names a failure the user can act on from this screen's own settings link. */
private val LocationMessage.isAnswerableWithSettings: Boolean
    get() = this == LocationMessage.PermissionDenied || this == LocationMessage.ServicesDisabled

/** The flag that closes the screen, next to "Made for Muslims in Bangladesh". */
private const val FLAG_BANGLADESH: String = "\uD83C\uDDED\uD83C\uDDE7"

// The illustration ------------------------------------------------------------
//
// Bangladesh, from the boundary the app already ships: `app/src/main/assets/location/bd_boundary.tsv`
// (COD-AB BGD v03, OCHA/HDX, CC BY 3.0 IGO — the same file the app validates a GPS fix against).
// The rings below are that file's coordinates, projected equirectangularly at 23.6°N with the
// longitude scaled by cos(latitude), then simplified with Ramer-Douglas-Peucker so the outline can be
// drawn at a couple of hundred points instead of fifteen hundred. Nothing here is a second source of
// geography: the shape is the country the app already knows, drawn once.

/** The illustration's coordinate space: the mainland fits 100 units wide, about 136 tall. */
private const val MAP_WIDTH: Float = 100f

/** Height of the projected mainland in the same units, so the drawing keeps its true proportions. */
private const val MAP_HEIGHT: Float = 135.86f

/** How much of the illustration's width the country may take, leaving room for the ornaments. */
private const val MAP_FRACTION_OF_WIDTH: Float = 0.62f

/**
 * The mainland, simplified to 95 points in [MAP_WIDTH] × [MAP_HEIGHT] units.
 *
 * Flat pairs of `x, y`, because a `floatArrayOf` of a hundred points is a tenth of the size of the
 * same points as `Path` calls and is read by one loop.
 */
private val MAP_MAINLAND = floatArrayOf(
    11.19f, 3.00f, 9.52f, 6.29f, 13.36f, 8.97f, 6.41f, 13.62f, 4.86f, 20.63f, 8.26f, 21.10f,
    13.69f, 27.67f, 19.14f, 27.34f, 19.75f, 30.83f, 23.11f, 33.03f, 21.41f, 35.22f, 11.67f, 34.23f,
    10.84f, 40.06f, 9.45f, 41.68f, 5.61f, 40.23f, 3.00f, 46.13f, 9.60f, 52.45f, 17.63f, 54.69f,
    18.30f, 61.18f, 14.42f, 63.82f, 14.06f, 68.48f, 18.94f, 71.81f, 17.29f, 77.16f, 22.86f, 78.06f,
    19.86f, 82.52f, 22.30f, 86.18f, 21.33f, 92.25f, 24.92f, 101.31f, 23.54f, 109.25f, 25.68f, 112.45f,
    35.78f, 112.09f, 38.12f, 108.22f, 41.23f, 109.53f, 40.98f, 105.56f, 43.61f, 105.00f, 43.44f,
    107.77f, 46.04f, 109.16f, 52.64f, 109.12f, 55.07f, 107.01f, 60.33f, 108.25f, 60.29f, 103.63f,
    66.58f, 100.72f, 66.90f, 93.56f, 70.30f, 93.16f, 68.67f, 90.69f, 70.87f, 88.97f, 78.19f, 95.94f,
    81.56f, 107.35f, 80.24f, 115.27f, 82.83f, 116.11f, 84.41f, 122.99f, 90.74f, 132.86f, 87.29f,
    119.45f, 90.96f, 116.09f, 95.30f, 121.29f, 97.00f, 120.31f, 89.14f, 67.35f, 87.60f, 68.42f,
    86.55f, 66.52f, 84.58f, 68.58f, 82.44f, 66.63f, 82.63f, 72.52f, 78.15f, 76.00f, 79.97f, 80.68f,
    75.70f, 84.02f, 72.33f, 77.03f, 71.56f, 81.35f, 70.18f, 80.52f, 66.18f, 67.12f, 67.89f, 62.40f,
    70.84f, 61.33f, 70.73f, 58.43f, 75.12f, 59.17f, 76.60f, 55.68f, 78.47f, 57.68f, 78.21f, 55.36f,
    81.42f, 57.72f, 81.70f, 53.38f, 87.93f, 49.79f, 88.01f, 41.00f, 93.32f, 40.99f, 84.57f, 34.73f,
    51.96f, 35.67f, 39.72f, 32.36f, 40.80f, 18.07f, 36.62f, 11.66f, 34.58f, 17.56f, 29.98f, 16.64f,
    26.07f, 13.83f, 24.71f, 8.15f, 22.11f, 6.82f, 21.18f, 8.84f, 24.22f, 11.30f, 19.78f, 11.82f,
    11.31f, 4.37f,
)

/** The coastal islands large enough to see at this size, in the same units. */
private val MAP_ISLANDS = listOf(
    floatArrayOf(
        80.70f, 106.39f, 80.23f, 106.91f, 80.12f, 107.81f, 80.32f, 108.56f, 80.14f, 108.98f,
        80.19f, 109.64f, 79.89f, 110.35f, 79.97f, 110.83f, 80.21f, 110.76f, 80.20f, 110.40f,
        81.20f, 107.76f, 81.16f, 106.99f, 80.90f, 106.44f,
    ),
    floatArrayOf(
        63.88f, 102.86f, 63.26f, 102.50f, 62.80f, 102.91f, 62.69f, 103.57f, 62.86f, 104.09f,
        63.05f, 104.20f, 63.69f, 103.74f, 63.60f, 103.95f, 63.88f, 103.86f, 64.20f, 103.08f,
        64.08f, 102.87f,
    ),
    floatArrayOf(
        74.44f, 94.18f, 73.30f, 92.26f, 72.37f, 91.24f, 72.04f, 91.13f, 71.81f, 91.30f, 71.45f,
        91.92f, 71.52f, 92.29f, 71.05f, 92.29f, 70.94f, 92.43f, 70.90f, 94.02f, 71.22f, 94.97f,
        70.90f, 95.05f, 71.38f, 95.30f, 72.07f, 95.98f, 72.47f, 96.06f, 72.79f, 95.91f, 73.57f,
        96.23f, 74.57f, 95.92f, 74.83f, 95.35f,
    ),
    floatArrayOf(
        21.49f, 9.59f, 21.34f, 9.52f, 21.32f, 9.71f, 21.51f, 10.13f, 21.47f, 10.46f, 22.00f, 10.83f,
        22.57f, 10.66f, 22.61f, 10.27f, 22.14f, 10.40f, 22.21f, 10.21f, 22.01f, 10.19f, 21.99f, 9.91f,
        21.67f, 10.00f, 21.75f, 9.57f,
    ),
)

/** The country's area centroid in the same units: where the illustration's location mark sits. */
private const val MAP_MARK_X: Float = 48.49f
private const val MAP_MARK_Y: Float = 65.77f

// The projected country is taller than it is wide; the design sets it on a slight tilt, which also
// matches the room the illustration is given on the screen.
private const val MAP_TILT_DEGREES: Float = -18f

/**
 * The illustration: the country's outline, a location mark at its centre, and the geometric
 * ornaments the design places around it.
 *
 * The ornaments are drawn here rather than assembled from the shared glyphs because they are
 * scattered across the illustration's own space — a cluster of small diamonds, a star, and the
 * waves a device fix would travel along — and positioning them against the map is the whole job.
 */
@Composable
private fun BangladeshMapArt(modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.primary
    val markFill = MaterialTheme.colorScheme.surface
    // The ornaments are gold on the canvas, so they take the theme's gold rather than the fixed mid
    // gold the light design was drawn with: on the dark canvas the fixed gold is the one tone that stays
    // readable, and on the cream canvas the accent's deeper gold is the readable one.
    val gold = LocalSalahAccents.current.goldText

    Canvas(modifier = modifier) {
        // Scale against the *tilted* extent, not the upright one: rotating a shape taller than it is
        // wide widens its bounds, and a size that fits before the tilt would be clipped after it.
        val radians = Math.toRadians(MAP_TILT_DEGREES.toDouble()).toFloat()
        val tiltedWidth = MAP_WIDTH * abs(cos(radians)) + MAP_HEIGHT * abs(sin(radians))
        val tiltedHeight = MAP_WIDTH * abs(sin(radians)) + MAP_HEIGHT * abs(cos(radians))
        val unit = min(
            size.width * MAP_FRACTION_OF_WIDTH / tiltedWidth,
            size.height / tiltedHeight,
        )
        val originX = size.width / 2f - (MAP_WIDTH * unit) / 2f
        val originY = size.height / 2f - (MAP_HEIGHT * unit) / 2f
        val pivot = Offset(size.width / 2f, size.height / 2f)

        rotate(degrees = MAP_TILT_DEGREES, pivot = pivot) {
            val stroke = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            (MAP_ISLANDS + MAP_MAINLAND).forEach { ring ->
                drawPath(ringPath(ring, originX, originY, unit), outline, style = stroke)
            }
        }

        // The mark is drawn upright, at the position the tilt moves the country's centre to, because
        // a pin tilting with the map would read as part of the drawing rather than as the place.
        val anchor = rotateAround(
            point = Offset(originX + MAP_MARK_X * unit, originY + MAP_MARK_Y * unit),
            pivot = pivot,
            degrees = MAP_TILT_DEGREES,
        )
        drawLocationMark(anchor = anchor, height = 34.dp.toPx(), fill = markFill, stroke = outline)

        // Ornaments, in fractions of the illustration so they sit beside the map at any width.
        drawOctagram(
            center = Offset(size.width * 0.19f, size.height * 0.43f),
            radius = 7.dp.toPx(),
            color = gold,
        )
        drawOctagram(
            center = Offset(size.width * 0.2f, size.height * 0.62f),
            radius = 5.dp.toPx(),
            color = outline,
        )
        drawSparkle(center = Offset(size.width * 0.3f, size.height * 0.18f), radius = 5.dp.toPx(), color = gold)
        drawSparkle(center = Offset(size.width * 0.84f, size.height * 0.72f), radius = 4.dp.toPx(), color = gold)
        drawSparkle(center = Offset(size.width * 0.78f, size.height * 0.24f), radius = 3.5.dp.toPx(), color = gold)
        drawDiamondCluster(
            center = Offset(size.width * 0.82f, size.height * 0.46f),
            unit = 4.6.dp.toPx(),
            first = outline,
            second = gold,
        )
        // The waves travelling to the mark, as the design draws them beside it.
        drawSignalWaves(
            center = Offset(size.width * 0.62f, size.height * 0.18f),
            radius = 7.dp.toPx(),
            color = gold,
        )
    }
}

/** One ring of the boundary as a closed [Path] in the drawing's coordinates. */
private fun ringPath(ring: FloatArray, originX: Float, originY: Float, unit: Float): Path {
    val path = Path()
    var index = 0
    while (index + 1 < ring.size) {
        val x = originX + ring[index] * unit
        val y = originY + ring[index + 1] * unit
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        index += 2
    }
    path.close()
    return path
}

/**
 * [point] rotated about [pivot] by [degrees].
 *
 * The same sign convention as `DrawScope.rotate`, so the mark below lands where the tilt actually put
 * the country's centre and not mirrored across it.
 */
private fun rotateAround(point: Offset, pivot: Offset, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val dx = point.x - pivot.x
    val dy = point.y - pivot.y
    return Offset(
        x = pivot.x + dx * cosine - dy * sine,
        y = pivot.y + dx * sine + dy * cosine,
    )
}

/**
 * A teardrop location mark whose tip is at [anchor].
 *
 * One closed path — the two sides, and the head as a half circle between them — so it takes a single
 * fill and a single outline and shows no seam where the head meets the body.
 */
private fun DrawScope.drawLocationMark(anchor: Offset, height: Float, fill: Color, stroke: Color) {
    val unit = height / 24f
    val radius = 6.3f * unit
    val head = Offset(anchor.x, anchor.y - HEAD_ABOVE_TIP * unit)
    val mark = Path().apply {
        moveTo(anchor.x, anchor.y)
        cubicTo(
            anchor.x - 1.4f * unit, anchor.y - 2.2f * unit,
            anchor.x - radius, anchor.y - 3.4f * unit,
            anchor.x - radius, head.y,
        )
        arcTo(Rect(center = head, radius = radius), 180f, 180f, forceMoveTo = false)
        cubicTo(
            anchor.x + radius, anchor.y - 3.4f * unit,
            anchor.x + 1.4f * unit, anchor.y - 2.2f * unit,
            anchor.x, anchor.y,
        )
        close()
    }
    drawPath(mark, fill)
    drawPath(mark, stroke, style = Stroke(width = 1.5f * unit, join = StrokeJoin.Round))
    drawCircle(color = stroke, radius = radius * 0.4f, center = head)
}

/** How far the mark's circular head sits above its tip, in the mark's own 24-unit space. */
private const val HEAD_ABOVE_TIP: Float = 11f

/** A two-square star, the app's geometric motif, as an ornament. */
private fun DrawScope.drawOctagram(center: Offset, radius: Float, color: Color) {
    // `Rect(center, radius)` reaches `radius` from the centre to each *edge*, so the half-width of a
    // square whose corners sit at `radius` is radius / sqrt(2).
    val square = Path().apply {
        addRect(Rect(center = center, radius = radius * 0.7071f))
    }
    val diamond = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    val stroke = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round)
    drawPath(square, color, style = stroke)
    drawPath(diamond, color, style = stroke)
}

/**
 * A four-pointed sparkle.
 *
 * Four quadratic curves, written as cubics with both control points on the quadratic's own control
 * point — the exact equivalent — so the shape uses only the path verbs every Compose version has.
 */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val thin = radius * 0.28f
    val sparkle = Path().apply {
        moveTo(center.x, center.y - radius)
        cubicTo(center.x + thin, center.y - thin, center.x + thin, center.y - thin, center.x + radius, center.y)
        cubicTo(center.x + thin, center.y + thin, center.x + thin, center.y + thin, center.x, center.y + radius)
        cubicTo(center.x - thin, center.y + thin, center.x - thin, center.y + thin, center.x - radius, center.y)
        cubicTo(center.x - thin, center.y - thin, center.x - thin, center.y - thin, center.x, center.y - radius)
        close()
    }
    drawPath(sparkle, color)
}

/** Three small diamonds, the design's cluster of tiles, alternating two colours. */
private fun DrawScope.drawDiamondCluster(center: Offset, unit: Float, first: Color, second: Color) {
    drawDiamond(Offset(center.x - unit, center.y), unit, first)
    drawDiamond(Offset(center.x + unit * 0.6f, center.y - unit * 1.5f), unit, second)
    drawDiamond(Offset(center.x + unit * 0.6f, center.y + unit * 1.5f), unit, first)
}

/** One outlined diamond, a square on its corner. */
private fun DrawScope.drawDiamond(at: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(at.x, at.y - radius)
        lineTo(at.x + radius, at.y)
        lineTo(at.x, at.y + radius)
        lineTo(at.x - radius, at.y)
        close()
    }
    drawPath(path, color, style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round))
}

/**
 * The waves a device fix travels along: a dot with two arcs curving away from it to the upper right.
 *
 * Both arcs share the dot's centre, so the mark reads as one signal rather than as two shapes that
 * happen to be near each other.
 */
private fun DrawScope.drawSignalWaves(center: Offset, radius: Float, color: Color) {
    listOf(radius, radius * 1.9f).forEach { r ->
        drawArc(
            color = color,
            startAngle = -78f,
            sweepAngle = 66f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
    drawCircle(color = color, radius = radius * 0.3f, center = center)
}

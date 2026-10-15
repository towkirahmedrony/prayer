package io.github.towkirahmedrony.salah.feature.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.location.AreaResolution
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.location.LocationNameLanguage
import io.github.towkirahmedrony.salah.core.domain.location.LocationSearchResult
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionAction
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionPolicy
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import io.github.towkirahmedrony.salah.feature.common.sourceLabelRes
import org.koin.androidx.compose.koinViewModel

/**
 * Where the canonical location selection screen is being opened from.
 *
 * The distinction is about **context**, not about behaviour: every rule behind this screen — which
 * dataset is listed, how a fix is validated against the country boundary, what a selection stores,
 * which reminders are reconciled — is identical in both values. What differs is what the user arrived
 * from, and therefore what a device fix does the moment it arrives.
 */
enum class LocationSelectionContext {

    /**
     * First launch: Salah has no usable location, and this screen *is* the app.
     *
     * The shell shows it instead of the navigation graph, so there is nothing behind it and nothing to
     * dismiss it to. A fix that passes the country policy is stored as soon as it arrives: on a fresh
     * install there is no location for it to replace, and a confirmation step would stand between the
     * user and the app for a decision that is as reversible in Settings as any other location change.
     * The manual card is shown outright, under its divider, with no way to close it.
     */
    FIRST_LAUNCH,

    /**
     * A later change: a location already exists, and the screen is a destination reached from Settings
     * or from any other feature that offers one.
     *
     * The stored location stays exactly as it is until a new one is confirmed, and a device fix is held
     * for confirmation rather than stored on sight — there the user is replacing something they chose,
     * and the nearest-match area name is worth a look first. The manual card is revealed by the
     * screen's own action and can be dismissed.
     */
    CHANGE,
    ;

    /** True for [FIRST_LAUNCH], the one context whose fix is stored on arrival. */
    val isFirstLaunch: Boolean get() = this == FIRST_LAUNCH
}

/**
 * The app's one location selection screen.
 *
 * **Why one screen.** Salah asks for a location in two situations — the first launch, when no usable
 * location exists at all, and a later change, from Settings or from any other feature that offers it.
 * The two differ in framing, never in rules: the same bundled dataset, the same country policy, the
 * same validation, the same write path and the same reminder reconciliation. This composable is that
 * one implementation, and [LocationSelectionContext] carries the only genuine difference between the
 * entry points.
 *
 * **What it offers.** Every way of giving Salah a location the app has: a device fix from the
 * platform's provider, with the permission decision taken by the shared `LocationPermissionPolicy`,
 * and a manual choice of a Bangladeshi administrative area through the one `ManualAreaSection` picker
 * — the division → district → upazila/thana levels plus a search over the same bundled dataset, in the
 * interface's language and matching both the English and the Bangla names. On a change it also shows
 * the location being replaced, and offers typed coordinates and removal. There is no map picker: it was
 * removed, and nothing here reintroduces it.
 *
 * **How it is reached.** The first-launch gate in
 * [io.github.towkirahmedrony.salah.navigation.SalahApp] renders it with
 * [LocationSelectionContext.FIRST_LAUNCH] *instead of* the navigation graph; the
 * `SalahDestination.LOCATION` route renders the same composable with
 * [LocationSelectionContext.CHANGE], and that is the destination Settings → Location and every other
 * entry point navigate to. There is no second location-selection destination, no per-feature copy of
 * this screen, and no wrapper screen whose only difference is a title.
 *
 * **State and lifecycle.** One [LocationViewModel] per open screen, supplied by Koin and scoped to it,
 * and no selection state outside it: the stored location lives in the repository, so both contexts read
 * one truth and a change is visible everywhere the moment it is saved. Saving is the only thing that
 * writes — a back press, a dismissed picker or a discarded fix leaves the stored location exactly as it
 * was.
 */
@Composable
fun LocationSelectionScreen(
    context: LocationSelectionContext,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.start()
        viewModel.refreshPermissionState()
    }

    // The language the dataset's place names render in and the search matches against — taken from the
    // same resources every label here resolves through (`currentNameLanguage`), and handed to the view
    // model because the dataset's names are data rather than strings. A change — including one made
    // while this screen is open — re-runs any query already typed.
    val nameLanguage = currentNameLanguage()
    LaunchedEffect(nameLanguage) { viewModel.setLanguage(nameLanguage) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshPermissionState()
    }

    // The permission decision lives in one place (LocationPermissionPolicy); this supplies the two
    // platform facts it needs, so a permanently denied permission opens app settings instead of
    // re-launching a dialog the system would silently ignore. Nothing requests anything until the user
    // taps the screen's own button.
    //
    // `saveOnArrival` is the one behavioural difference between the two contexts: a fix is stored where
    // it lands on a first launch, and held for confirmation on a later change.
    val onUseDeviceLocation = rememberDeviceLocationAction(
        viewModel = viewModel,
        permissionState = state.permissionState,
        saveOnArrival = context.isFirstLaunch,
    )

    LocationSelectionContent(
        context = context,
        state = state,
        modifier = modifier,
        onUseDeviceLocation = onUseDeviceLocation,
        onOpenLocationSettings = {
            settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onOpenAreaPicker = viewModel::openAreaPicker,
        onCloseAreaPicker = viewModel::closeAreaPicker,
        onSelectDivision = viewModel::selectDivision,
        onSelectDistrict = viewModel::selectDistrict,
        onSelectUpazila = viewModel::selectUpazila,
        onSaveSelection = viewModel::saveSelectedArea,
        onConfirmFix = viewModel::confirmPendingFix,
        onDiscardFix = viewModel::discardPendingFix,
        onClearLocation = viewModel::clearLocation,
        onDismissMessage = viewModel::dismissMessage,
        onToggleManualCoordinates = viewModel::toggleManualCoordinates,
        onManualFormChange = viewModel::onManualFormChange,
        onSaveManualLocation = viewModel::saveManualLocation,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSelectSearchResult = viewModel::selectSearchResult,
        nameLanguage = nameLanguage,
        onBack = onBack,
    )
}

/**
 * The screen's body, as a function of the location state and the entry context alone.
 *
 * Order is deliberate, and the same in both contexts: what the dataset is, the location being replaced
 * (when there is one), a fix awaiting confirmation, the device option, the message about the last
 * action with its settings affordance, then the manual picker — and, on a change, the remaining actions
 * on the location that already exists. Nothing is offered before the dataset has been read, because a
 * picker with no divisions in it is worse than a skeleton.
 *
 * The header and the footer are the only parts that are not the same call in both: a first launch is
 * the app's own front page, while a change is a subpage of Settings that names the location it is
 * about to replace. Everything else below is shared, so the two can never accept different locations.
 *
 * The manual-coordinate form is the last of the three ways in on a change, and only rendered once the
 * user has opened it: it is the escape hatch for a position the bundled area dataset cannot express,
 * not a second primary path, and the two primary ones stay where they were.
 */
@Composable
private fun LocationSelectionContent(
    context: LocationSelectionContext,
    state: LocationUiState,
    modifier: Modifier = Modifier,
    onUseDeviceLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAreaPicker: () -> Unit,
    onCloseAreaPicker: () -> Unit,
    onSelectDivision: (String) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onSelectUpazila: (String) -> Unit,
    onSaveSelection: () -> Unit,
    onConfirmFix: () -> Unit,
    onDiscardFix: () -> Unit,
    onClearLocation: () -> Unit,
    onDismissMessage: () -> Unit,
    onToggleManualCoordinates: () -> Unit,
    onManualFormChange: ((LocationFormState) -> LocationFormState) -> Unit,
    onSaveManualLocation: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSelectSearchResult: (LocationSearchResult) -> Unit,
    nameLanguage: LocationNameLanguage,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // A first launch is a full page — room above and below, everything centred — while a change
            // is a subpage, tighter at the top, with its rows left-aligned like every other Settings page.
            .padding(
                horizontal = 20.dp,
                vertical = if (context.isFirstLaunch) 24.dp else 8.dp,
            ),
        horizontalAlignment = if (context.isFirstLaunch) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = if (context.isFirstLaunch) Arrangement.Top else Arrangement.spacedBy(16.dp),
    ) {
        if (context.isFirstLaunch) {
            FirstLaunchHeader()
        } else {
            SalahSubpageHeader(
                title = stringResource(R.string.location_title),
                backContentDescription = stringResource(R.string.action_back),
                onBack = onBack,
            )
        }

        // What the dataset is, stated once for both contexts: the version when there is one worth
        // showing, and a broken dataset's own explanation when the areas cannot be listed at all.
        DatasetStatusSection(state = state, showVersion = !context.isFirstLaunch)

        // Stored location ----------------------------------------------------
        state.stored?.let { stored -> StoredLocationCard(stored = stored) }

        // A device fix awaiting confirmation --------------------------------
        state.pendingFix?.let { fix ->
            PendingFixCard(
                fix = fix,
                onConfirm = onConfirmFix,
                onDiscard = onDiscardFix,
                onChooseArea = {
                    onDiscardFix()
                    onOpenAreaPicker()
                },
            )
        }

        // The device option, and — on a change — the way into the manual picker beside it. A first
        // launch draws its own card explaining what a fix is used for; a change draws the two buttons
        // that sit above the location they would replace.
        if (state.pendingFix == null) {
            if (context.isFirstLaunch) {
                UseCurrentLocationCard(
                    state = state,
                    onUseDeviceLocation = onUseDeviceLocation,
                )
            } else if (state.isAcquiring) {
                SalahSectionCard {
                    Text(
                        text = stringResource(R.string.location_gps_acquiring),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                Button(
                    onClick = onUseDeviceLocation,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true,
                ) {
                    Text(text = stringResource(R.string.location_action_use_gps))
                }
                OutlinedButton(
                    onClick = onOpenAreaPicker,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canChooseArea,
                ) {
                    Text(text = stringResource(R.string.location_action_select_manually))
                }
            }
        }

        // A change states that both options work offline; a first launch says the same thing in its own
        // footer, so the note is not repeated there.
        if (!context.isFirstLaunch) {
            Text(
                text = stringResource(R.string.location_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The result of the last action, read where the action was, plus the one place the settings
        // affordance is decided: a message the user can act on, or a denied permission with nothing
        // stored yet. Both are the same rule for both contexts.
        state.message?.let { message ->
            LocationMessageRow(message = message, onDismiss = onDismissMessage)
        }
        if (state.shouldOfferLocationSettings) {
            TextButton(onClick = onOpenLocationSettings) {
                Text(text = stringResource(R.string.location_action_open_location_settings))
            }
        }

        // The manual picker ------------------------------------------------------
        // One picker for both contexts (`ManualAreaSection`): a first launch shows it outright, under
        // its divider, with nothing to dismiss; a change reveals it from the action above and can
        // cancel it. The wording and the confirm label are the only parameters — the levels, the search
        // and the write are the same code, so a selection cannot be accepted here and refused there.
        if (context.isFirstLaunch || state.isPickerOpen) {
            if (context.isFirstLaunch) {
                Spacer(modifier = Modifier.height(18.dp))
                OrSelectManuallyDivider()
                Spacer(modifier = Modifier.height(18.dp))
            }
            ManualAreaSection(
                state = state,
                onSelectDivision = onSelectDivision,
                onSelectDistrict = onSelectDistrict,
                onSelectUpazila = onSelectUpazila,
                onSaveSelection = onSaveSelection,
                onSearchQueryChanged = onSearchQueryChanged,
                onSelectSearchResult = onSelectSearchResult,
                nameLanguage = nameLanguage,
                titleRes = if (context.isFirstLaunch) {
                    R.string.location_setup_manual_title
                } else {
                    R.string.location_picker_title
                },
                bodyRes = if (context.isFirstLaunch) R.string.location_setup_manual_body else null,
                confirmRes = if (context.isFirstLaunch) {
                    R.string.location_setup_manual_confirm
                } else {
                    R.string.location_action_save
                },
                onClose = if (context.isFirstLaunch) null else onCloseAreaPicker,
            )
        }

        // Actions on an existing location ------------------------------------
        // Only ever offered on a change: a first launch has no location to refresh or remove, and its
        // own framing ends after the picker.
        if (!context.isFirstLaunch && state.stored != null && !state.isPickerOpen && state.pendingFix == null) {
            state.stored?.let { stored ->
                if (stored.freshness.shouldOfferRefresh || stored.origin == LocationOrigin.AREA_SELECTION) {
                    OutlinedButton(
                        onClick = onUseDeviceLocation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.location_action_refresh_gps))
                    }
                }
            }
            OutlinedButton(
                onClick = onClearLocation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.location_action_clear))
            }
        }

        // Manual coordinates --------------------------------------------------
        // The third way to give Salah a location, behind a disclosure: the least used of the three, and
        // the only one that stores a position the country policy does not cover. Kept off the screen
        // until asked for, so the two ways most people are here for are not competing with a form.
        if (!context.isFirstLaunch && !state.isPickerOpen && state.pendingFix == null && !state.isManualFormOpen) {
            OutlinedButton(
                onClick = onToggleManualCoordinates,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_location_manual_open))
            }
        }

        if (!context.isFirstLaunch && state.isManualFormOpen) {
            ManualCoordinatesSection(
                state = state,
                onFormChange = onManualFormChange,
                onSave = onSaveManualLocation,
                onClose = onToggleManualCoordinates,
            )
        }

        // A first launch closes on its own footer, as designed: what the chosen area is used for, and
        // who the app is for. A change has no footer — its last card is the location itself.
        if (context.isFirstLaunch) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.location_setup_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.location_setup_made_for) + " " + FLAG_BANGLADESH,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The manual-coordinate form: a position typed in by hand, with the time zone it belongs to.
 *
 * The reason it exists at all is stated on it: a position is stored as entered and no country check is
 * applied, so it is usable for a place the bundled area dataset does not contain. That is also why it is
 * not the primary path — the area picker stores a published reference coordinate and the bundled policy
 * has checked it, and both of those are the more defensible answer whenever they are available.
 *
 * The fields are raw text, so a half-typed `23.` or a lone `-` is representable while typing; parsing and
 * validation happen once, on save, in the repository. The error state is shown in place rather than only
 * as a message, because the invalid field is right here.
 */
/**
 * The first launch's opening: the app's mark, what Salah needs a location for, and the country drawn
 * from the boundary the app already ships.
 *
 * It lives here rather than among the shared components because the *arrangement* is this screen's
 * framing on a first launch; the pieces it draws — [Branding], [BangladeshMapArt] — are shared.
 */
@Composable
private fun FirstLaunchHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
    }
}

/**
 * What the bundled dataset is, as one section for both contexts.
 *
 * Three states, each saying exactly what is true: a read still in flight (a stand-in line rather than a
 * progress bar — the parse finishes in milliseconds, and every option below stays disabled until it
 * does), a dataset that could not be read (with the file counts that were found), or a usable one. The
 * version line is shown only on a change: on the first launch it would be a technical detail on the
 * app's opening page, and it is not what a new user needs to read.
 */
@Composable
private fun DatasetStatusSection(
    state: LocationUiState,
    showVersion: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> SalahSkeletonBlock(
            height = 14.dp,
            modifier = modifier.fillMaxWidth(0.55f),
        )

        !state.isDatasetUsable -> SalahSectionCard(modifier = modifier) {
            Text(
                text = stringResource(R.string.location_dataset_unavailable_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.location_dataset_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.datasetDetail?.let { detail ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.location_dataset_incomplete, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        showVersion -> state.datasetVersion?.let { version ->
            Text(
                text = stringResource(R.string.location_dataset_label, version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }
    }
}

/**
 * True when the platform's own location settings are the user's next step.
 *
 * Two situations, one answer: the last action failed for a reason a settings screen can fix (a denied
 * permission, or location services switched off), or the permission is denied and nothing is stored
 * yet — on a first launch, that is the state before anything has been tried. Deliberately not shown
 * once a location exists and nothing is wrong: there it would be a button with no purpose.
 */
private val LocationUiState.shouldOfferLocationSettings: Boolean
    get() = (permissionState == LocationPermissionState.DENIED && stored == null) ||
        message?.isAnswerableWithSettings == true

/** The flag that closes the first-launch screen, next to "Made for Muslims in Bangladesh". */
private const val FLAG_BANGLADESH: String = "\uD83C\uDDED\uD83C\uDDE7"

@Composable
private fun ManualCoordinatesSection(
    state: LocationUiState,
    onFormChange: ((LocationFormState) -> LocationFormState) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val form = state.manualForm

    SalahSectionCard {
        Text(
            text = stringResource(R.string.settings_location_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_location_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = form.label,
            onValueChange = { value -> onFormChange { it.copy(label = value) } },
            label = { Text(stringResource(R.string.settings_location_label)) },
            singleLine = true,
            isError = form.hasValidationError,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.latitude,
                onValueChange = { value -> onFormChange { it.copy(latitude = value) } },
                label = { Text(stringResource(R.string.settings_latitude)) },
                singleLine = true,
                isError = form.hasValidationError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.longitude,
                onValueChange = { value -> onFormChange { it.copy(longitude = value) } },
                label = { Text(stringResource(R.string.settings_longitude)) },
                singleLine = true,
                isError = form.hasValidationError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = form.timeZoneId,
            onValueChange = { value -> onFormChange { it.copy(timeZoneId = value) } },
            label = { Text(stringResource(R.string.settings_time_zone)) },
            placeholder = { Text("Asia/Dhaka") },
            singleLine = true,
            isError = form.hasValidationError,
            modifier = Modifier.fillMaxWidth(),
        )

        if (form.hasValidationError) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_location_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSave) {
                Text(text = stringResource(R.string.settings_save))
            }
            OutlinedButton(onClick = onClose) {
                Text(text = stringResource(R.string.location_action_cancel))
            }
        }
    }
}

@Composable
private fun StoredLocationCard(stored: StoredLocationUi) {
    var showAdvanced by remember { mutableStateOf(false) }

    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.location_section_current_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            SalahStatusPill(
                text = stringResource(stored.origin.sourceLabelRes()),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        LocationDetailRow(
            label = stringResource(R.string.location_country_label),
            value = stored.countryCode?.let { stringResource(R.string.location_country_value) }
                ?: stringResource(R.string.location_country_not_established),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_division_label),
            value = stored.divisionDisplayName ?: stringResource(R.string.location_area_unknown),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_district_label),
            value = stored.districtDisplayName ?: stringResource(R.string.location_area_unknown),
        )
        LocationDetailRow(
            label = stringResource(R.string.location_upazila_label),
            value = stored.upazilaDisplayName ?: stringResource(R.string.location_area_unknown),
        )
        // Only when a locality was actually established: an empty row would suggest Salah looked and
        // found nothing, which is not the same as a record that predates the locality layer.
        stored.locality?.let { locality ->
            LocationDetailRow(
                label = stringResource(R.string.location_locality_label),
                value = locality.name,
            )
        }
        LocationDetailRow(
            label = stringResource(R.string.location_time_zone_label),
            value = stored.timeZoneId,
        )
        stored.accuracyText?.let { accuracy ->
            LocationDetailRow(
                label = stringResource(R.string.location_accuracy_label),
                value = accuracy,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(freshnessRes(stored.freshness)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (stored.origin == LocationOrigin.AREA_SELECTION) {
            Spacer(modifier = Modifier.height(8.dp))
            SalahUnavailableNote(text = stringResource(R.string.location_area_reference_note))
        }

        if (stored.isKnownOutsideSupportedCountry) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.location_unsupported_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.location_unsupported_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(text = stringResource(R.string.location_advanced_title))
        }

        if (showAdvanced) {
            Text(
                text = stringResource(R.string.location_advanced_coordinates, stored.coordinateText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stored.location.datasetVersion?.let { version ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.location_advanced_dataset_selected, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    when {
                        stored.isVerifiedInSupportedCountry ->
                            R.string.location_advanced_bangladesh_verified
                        stored.isWithinBoundaryTolerance ->
                            R.string.location_advanced_bangladesh_within_tolerance
                        else -> R.string.location_advanced_bangladesh_unverified
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingFixCard(
    fix: PendingDeviceFix,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onChooseArea: () -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.location_gps_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.location_gps_coordinates, fix.coordinateText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        fix.accuracyText?.let { accuracy ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.location_gps_accuracy, accuracy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        when (val resolution = fix.resolution) {
            is AreaResolution.Suggested -> {
                Text(
                    text = stringResource(
                        R.string.location_gps_matched,
                        resolution.suggestion.reference.hierarchyLabel,
                        fix.matchDistanceText ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                SalahUnavailableNote(text = stringResource(R.string.location_gps_matched_approximate))
            }

            is AreaResolution.Unresolved -> {
                Text(
                    text = stringResource(
                        R.string.location_gps_unmatched,
                        fix.nearestDistanceText?.let { distance ->
                            stringResource(R.string.location_gps_unmatched_distance, distance)
                        }.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            AreaResolution.DatasetUnavailable -> {
                SalahUnavailableNote(text = stringResource(R.string.location_dataset_unavailable_body))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                when {
                    fix.isCountryVerified -> R.string.location_gps_verified
                    fix.isWithinBoundaryTolerance -> R.string.location_gps_within_tolerance
                    else -> R.string.location_gps_unverified
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.location_action_confirm_location))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onChooseArea, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.location_action_change_area))
            }
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.location_action_discard))
            }
        }
    }
}

@Composable
private fun LocationDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun LocationMessageRow(message: LocationMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        LocationMessage.Saved -> stringResource(R.string.location_message_saved)
        LocationMessage.SaveRejected -> stringResource(R.string.location_message_rejected)
        LocationMessage.PermissionDenied -> stringResource(R.string.location_message_permission_denied)
        LocationMessage.ServicesDisabled -> stringResource(R.string.location_message_services_disabled)
        LocationMessage.TimedOut -> stringResource(R.string.location_message_timeout)
        LocationMessage.ProviderFailed -> stringResource(R.string.location_message_provider_failed)
        LocationMessage.Cleared -> stringResource(R.string.location_message_cleared)
        LocationMessage.DatasetUnavailable -> stringResource(R.string.location_message_dataset_unavailable)
        is LocationMessage.OutsideSupportedCountry -> message.distanceMetres?.let { distance ->
            stringResource(
                R.string.location_message_outside_country_distance,
                LocationFormatting.distance(distance),
            )
        } ?: stringResource(R.string.location_message_outside_country)
    }

    SalahSectionCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.location_message_dismiss))
        }
    }
}

private fun freshnessRes(freshness: LocationFreshness): Int = when (freshness) {
    LocationFreshness.DECLARED -> R.string.location_freshness_declared
    LocationFreshness.FRESH -> R.string.location_freshness_fresh
    LocationFreshness.RECENT -> R.string.location_freshness_recent
    LocationFreshness.STALE -> R.string.location_freshness_stale
    LocationFreshness.UNKNOWN -> R.string.location_freshness_unknown
}

/**
 * Builds the "use the device's location" click handler the screen's device option calls.
 *
 * The permission handling is written once, here: the request launcher, the decision from
 * [LocationPermissionPolicy] — so a permanently denied permission opens the app's settings page instead
 * of re-launching a dialog the system would silently ignore — and both settings paths. Nothing runs on
 * composition; the returned lambda is only ever invoked by the user's own tap, which is why merely
 * opening the selection screen never prompts for anything. Manual area selection is unaffected by all
 * of it and stays usable with the permission denied.
 *
 * @param saveOnArrival passed straight through to [LocationViewModel.useCurrentLocation]: true in
 *   [LocationSelectionContext.FIRST_LAUNCH], where a verified fix is the answer and the app opens on it,
 *   and false in [LocationSelectionContext.CHANGE], which holds a fix for confirmation. Only the moment
 *   of the write differs; asking for the permission, and what happens when it is refused, are the same
 *   in both.
 */
@Composable
internal fun rememberDeviceLocationAction(
    viewModel: LocationViewModel,
    permissionState: LocationPermissionState,
    saveOnArrival: Boolean = false,
): () -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    var hasRequestedDialog by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { granted -> granted }) {
            viewModel.refreshPermissionState()
            viewModel.useCurrentLocation(saveOnArrival = saveOnArrival)
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Returning from settings: re-read the permission so the button reflects the new answer.
        viewModel.refreshPermissionState()
    }

    return {
        val shouldShowRationale = activity?.let { host ->
            LOCATION_PERMISSIONS.any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(host, permission)
            }
        } ?: false
        when (
            LocationPermissionPolicy.action(
                state = permissionState,
                hasRequestedDialog = hasRequestedDialog,
                shouldShowRationale = shouldShowRationale,
            )
        ) {
            LocationPermissionAction.ALREADY_GRANTED ->
                viewModel.useCurrentLocation(saveOnArrival = saveOnArrival)
            LocationPermissionAction.REQUEST -> {
                hasRequestedDialog = true
                permissionLauncher.launch(LOCATION_PERMISSIONS)
            }
            LocationPermissionAction.OPEN_APP_SETTINGS -> appSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
    }
}

/** The hosting [Activity], found through any [ContextWrapper] the Compose tree was handed. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Both permissions, requested together so Android 12+ can offer "Approximate" or "Precise". */
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

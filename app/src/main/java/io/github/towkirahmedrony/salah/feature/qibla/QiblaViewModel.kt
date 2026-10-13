package io.github.towkirahmedrony.salah.feature.qibla

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.DeviceFixAdoption
import io.github.towkirahmedrony.salah.core.domain.location.DeviceFixAdoptionPolicy
import io.github.towkirahmedrony.salah.core.domain.location.DeviceFixRejectionReason
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.qibla.CalibrationAdvice
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassAccuracyGate
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassAvailability
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassReading
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorSource
import io.github.towkirahmedrony.salah.core.domain.qibla.GeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.HeadingMath
import io.github.towkirahmedrony.salah.core.domain.qibla.HeadingSmoother
import io.github.towkirahmedrony.salah.core.domain.qibla.KaabaReference
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationUnavailableReason
import io.github.towkirahmedrony.salah.core.domain.qibla.MagneticAccuracy
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaBearing
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaCalculator
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaLocationState
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaStateReducer
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * The Qibla screen's state.
 *
 * Three things are kept deliberately separate, because they change at completely different rates and
 * one of them changes ten times a second:
 *
 *  * **[snapshot]** — the stored location, its declination and the resulting bearing. Recomputed only
 *    when the location changes, never per sensor sample. The declination model evaluation and the
 *    bearing calculation are cheap, but reading DataStore at 10 Hz would not be.
 *  * **[headingInputs]** — the latest smoothed heading, its accuracy and its tilt, written by the
 *    sensor flow.
 *  * **[screenInputs]** — form text, the in-flight flag, the last message and whether the calibration
 *    note has been dismissed.
 *
 * The two cheap flows are combined into [QiblaHeadingUi] and then into [QiblaUiState], so a sensor
 * sample costs one pure calculation and one state emission.
 *
 * **Nothing here reads a coordinate into the log.** Location diagnosis records the origin,
 * freshness, provider and accuracy — never the latitude or longitude.
 *
 * **A write here reschedules the reminders.** This screen stores through the same
 * [LocationRepository] the prayer engine and the location screen use, so a position adopted here
 * changes the calculated times everywhere — which is exactly why the write is followed by the same
 * [PrayerNotificationCoordinator.reconcile] the location screen performs. The bearing is the screen's
 * subject, but the location it is computed from is the app's, and leaving the previous alarms in
 * place would have Salah announce the times of the place the user has just stopped being at.
 */
class QiblaViewModel(
    private val locations: LocationRepository,
    /** The bundled geography, so a device fix is validated and attributed before it replaces anything. */
    private val areaCatalog: BangladeshAreaCatalog,
    private val compassSensor: CompassSensorSource,
    private val locationProvider: LocationProvider,
    private val permissionChecker: LocationPermissionChecker,
    private val declinationProvider: GeomagneticDeclinationProvider,
    private val qiblaCalculator: QiblaCalculator,
    private val coordinator: PrayerNotificationCoordinator,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
) : ViewModel() {

    private val sensorAvailability: CompassAvailability = compassSensor.availability()

    private val smoother = HeadingSmoother()
    private val accuracyGate = CompassAccuracyGate()

    private val snapshot = MutableStateFlow(BearingSnapshot())
    private val headingInputs = MutableStateFlow(HeadingInputs())
    private val screenInputs = MutableStateFlow(ScreenInputs())

    private var compassJob: Job? = null

    /** Last advice written to the log, so only transitions are recorded. */
    private var lastLoggedAdvice: CalibrationAdvice? = null

    private val headingUi: StateFlow<QiblaHeadingUi?> = combine(
        headingInputs,
        snapshot,
    ) { inputs, current ->
        buildHeading(inputs, current)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null,
    )

    val uiState: StateFlow<QiblaUiState> = combine(
        snapshot,
        screenInputs,
        headingUi,
    ) { current, screen, heading ->
        buildUiState(current, screen, heading)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildUiState(BearingSnapshot(), ScreenInputs(), null),
    )

    init {
        // One collector for the stored location. Every downstream value — bearing, declination,
        // freshness — is recomputed from it, so there is no second place a location can arrive from
        // and no chance of the bearing describing a location the UI is not showing.
        viewModelScope.launch {
            locations.location.collect { location ->
                onLocationChanged(location)
            }
        }
    }

    // --- Compass lifecycle ------------------------------------------------------

    /**
     * Starts heading updates. Called when the screen becomes visible.
     *
     * Idempotent: the sensor is registered once no matter how many times a resumed screen asks.
     */
    fun startCompass() {
        if (compassJob?.isActive == true) return
        compassJob = viewModelScope.launch {
            try {
                compassSensor.readings().collect(::onReading)
            } finally {
                // Either the sensor ended or the screen left; in both cases the last heading is no
                // longer live and must not be presented as if it were.
                clearHeading()
            }
        }
    }

    /** Stops heading updates. Called when the screen stops being visible. */
    fun stopCompass() {
        compassJob?.cancel()
        compassJob = null
        clearHeading()
    }

    private fun clearHeading() {
        headingInputs.value = HeadingInputs()
        smoother.reset()
        accuracyGate.reset()
        lastLoggedAdvice = null
    }

    private fun onReading(reading: CompassReading) {
        val smoothed = smoother.update(reading.magneticAzimuthDegrees)
        val accuracy = accuracyGate.accept(reading.accuracy)

        // Logged on transition, not on every sample. The advice is a function of the reported
        // accuracy, which settles and then stays put, so a transition log records exactly the events
        // worth knowing about without turning a 10 Hz sensor into a 10 Hz log stream.
        val advice = QiblaStateReducer.calibrationAdvice(sensorAvailability, accuracy)
        if (advice != lastLoggedAdvice) {
            if (advice == CalibrationAdvice.RECOMMENDED) {
                logger.info(
                    SalahDiagnosticEvents.QIBLA_CALIBRATION_RECOMMENDED,
                    mapOf("accuracy" to (accuracy?.name ?: "unknown")),
                )
            }
            lastLoggedAdvice = advice
        }

        headingInputs.value = HeadingInputs(
            smoothedMagneticDegrees = smoothed,
            accuracy = accuracy,
            tiltDegrees = reading.tiltDegrees,
            isOrientationUsable = reading.isOrientationUsable,
        )
    }

    // --- Location ---------------------------------------------------------------

    private fun onLocationChanged(location: LocationInfo?) {
        if (location == null) {
            snapshot.value = BearingSnapshot()
            return
        }

        val now = clock.millis()
        val declination = declinationProvider.declinationDegrees(location = location, atEpochMillis = now)
        val bearing = qiblaCalculator.bearingFrom(location)

        when (bearing) {
            is QiblaBearing.Available -> logger.info(
                SalahDiagnosticEvents.QIBLA_BEARING_CALCULATED,
                mapOf(
                    "bearingDegrees" to rounded(bearing.degrees),
                    "distanceKm" to rounded(bearing.distanceMetres / 1_000.0),
                    "origin" to location.origin.name,
                    "declinationAvailable" to (declination != null),
                ),
            )

            is QiblaBearing.Unavailable -> logger.warn(
                SalahDiagnosticEvents.QIBLA_BEARING_FAILED,
                mapOf("reason" to bearing.reason.name, "origin" to location.origin.name),
            )
        }

        snapshot.value = BearingSnapshot(
            location = location,
            declinationDegrees = declination,
            bearing = bearing,
        )

        logger.info(
            SalahDiagnosticEvents.QIBLA_LOCATION_SOURCE_CHANGED,
            mapOf(
                "origin" to location.origin.name,
                "freshness" to LocationFreshness.of(location, clock.instant()).name,
                "accuracyMetres" to location.accuracyMetres,
                // Coordinates are intentionally absent.
                "timeZoneId" to location.timeZoneId,
            ),
        )
    }

    /**
     * Asks the platform for a position and stores it.
     *
     * One request per call, with a bounded wait, so the screen can never be left spinning: every
     * outcome — including a timeout — becomes a state the user can read.
     */
    fun useCurrentLocation() {
        if (screenInputs.value.isAcquiringLocation) return
        logger.info(SalahDiagnosticEvents.QIBLA_LOCATION_REQUESTED)

        viewModelScope.launch {
            screenInputs.update { it.copy(isAcquiringLocation = true, message = null) }
            try {
                when (val result = locationProvider.currentLocation(ACQUISITION_TIMEOUT)) {
                    is LocationAcquisitionResult.Acquired -> storeDeviceLocation(result)

                    LocationAcquisitionResult.PermissionDenied -> update {
                        it.copy(
                            locationFailure = QiblaLocationState.PermissionDenied,
                            message = QiblaMessage.PermissionDenied,
                        )
                    }

                    LocationAcquisitionResult.ServicesDisabled -> update {
                        it.copy(
                            locationFailure = QiblaLocationState.Unavailable(
                                LocationUnavailableReason.SERVICES_DISABLED,
                            ),
                            message = QiblaMessage.ServicesDisabled,
                        )
                    }

                    LocationAcquisitionResult.TimedOut -> update {
                        it.copy(
                            locationFailure = QiblaLocationState.Unavailable(
                                LocationUnavailableReason.TIMEOUT,
                            ),
                            message = QiblaMessage.LocationTimedOut,
                        )
                    }

                    is LocationAcquisitionResult.Failed -> update {
                        it.copy(
                            locationFailure = QiblaLocationState.Unavailable(
                                LocationUnavailableReason.PROVIDER_ERROR,
                            ),
                            message = QiblaMessage.LocationFailed,
                        )
                    }
                }
            } finally {
                update { it.copy(isAcquiringLocation = false) }
                refreshPermissionState()
            }
        }
    }

    /**
     * Adopts a device fix only when the shared policy accepts it, otherwise leaves the stored location
     * untouched.
     *
     * The Qibla screen has no confirmation step, so the fix replaces the stored location outright —
     * which is exactly why it must pass the same Bangladesh policy and area resolution the location
     * screen applies before offering one. A fix that is outside Bangladesh or that cannot be attributed
     * to an area keeps the location the user already has; see [DeviceFixAdoptionPolicy].
     */
    private suspend fun storeDeviceLocation(acquired: LocationAcquisitionResult.Acquired) {
        val point = GeoPoint(latitude = acquired.latitude, longitude = acquired.longitude)
        val dataset = runCatching { areaCatalog.dataset() }.getOrNull()

        when (val adoption = DeviceFixAdoptionPolicy.decide(point, dataset)) {
            is DeviceFixAdoption.Accepted -> {
                val saved = locations.saveDeviceLocation(
                    label = adoption.reference.label,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    // A fix adopted as a Bangladeshi area is stored in Bangladesh's single zone, the same
                    // zone the location screen stores one in — not the device's current one, which would
                    // mis-time a user who selected a location while travelling.
                    timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID,
                    accuracyMetres = acquired.accuracyMetres,
                    acquiredAtEpochMillis = acquired.acquiredAtEpochMillis,
                    countryCode = adoption.countryCode,
                    area = adoption.reference,
                    datasetVersion = adoption.datasetVersion,
                )

                update {
                    it.copy(
                        locationFailure = null,
                        message = if (saved) QiblaMessage.DeviceLocationAcquired else QiblaMessage.LocationFailed,
                    )
                }
                if (saved) reconcileNotifications()
            }

            is DeviceFixAdoption.Rejected -> {
                logger.warn(
                    SalahDiagnosticEvents.QIBLA_LOCATION_NOT_ADOPTED,
                    mapOf(
                        "reason" to adoption.reason.name,
                        "distanceMetres" to adoption.distanceMetres?.toInt(),
                        // Coordinates are intentionally absent.
                    ),
                )
                val message = when (adoption.reason) {
                    DeviceFixRejectionReason.OUTSIDE_BANGLADESH -> QiblaMessage.OutsideSupportedCountry
                    DeviceFixRejectionReason.AREA_UNRESOLVED -> QiblaMessage.AreaNotAttributed
                    DeviceFixRejectionReason.DATASET_UNAVAILABLE -> QiblaMessage.LocationFailed
                }
                update { it.copy(message = message) }
            }
        }
    }

    /** Re-reads the permission state, after the screen has asked the platform for it. */
    fun refreshPermissionState() {
        val state = permissionChecker.state()
        update { it.copy(permissionState = state) }
    }

    /** The user declined the permission dialog, or it was dismissed. */
    fun onLocationPermissionDenied() {
        update {
            it.copy(
                permissionState = LocationPermissionState.DENIED,
                locationFailure = QiblaLocationState.PermissionDenied,
                message = QiblaMessage.PermissionDenied,
            )
        }
    }

    // --- Manual location --------------------------------------------------------

    fun onFormChange(transform: (LocationFormState) -> LocationFormState) {
        update { current ->
            current.copy(
                form = transform(current.form).copy(isDirty = true),
                message = null,
            )
        }
    }

    /**
     * Validates and stores manually entered coordinates.
     *
     * The same repository and the same validation as the Settings screen: one location, one set of
     * rules. Coordinates entered here stay usable with location permission denied and with no
     * network, which is what makes manual entry the offline fallback rather than a convenience.
     */
    fun saveManualLocation() {
        val form = screenInputs.value.form
        val latitude = form.latitude.trim().toDoubleOrNull()
        val longitude = form.longitude.trim().toDoubleOrNull()

        viewModelScope.launch {
            val saved = latitude != null && longitude != null &&
                locations.saveManualLocation(
                    label = form.label.trim(),
                    latitude = latitude,
                    longitude = longitude,
                    timeZoneId = form.timeZoneId.trim(),
                )

            update { current ->
                current.copy(
                    form = current.form.copy(
                        isDirty = !saved,
                        hasValidationError = !saved,
                    ),
                    locationFailure = if (saved) null else current.locationFailure,
                    message = if (saved) QiblaMessage.LocationSaved else QiblaMessage.LocationRejected,
                )
            }
            if (saved) reconcileNotifications()
        }
    }

    fun clearLocation() {
        viewModelScope.launch {
            locations.clearLocation()
            update {
                it.copy(
                    form = LocationFormState.blankWithSuggestedZone(ZoneId.systemDefault().id),
                    locationFailure = null,
                    message = QiblaMessage.LocationCleared,
                )
            }
            reconcileNotifications()
        }
    }

    /**
     * Rebuilds the alarm set for the location that was just stored.
     *
     * Called after every successful write and after a clear, because the schedule and the reminders are
     * derived from the coordinates. A failed reconciliation is logged rather than thrown: the location
     * is already stored, and the next app start reconciles again.
     */
    private suspend fun reconcileNotifications() {
        runCatching { coordinator.reconcile(ReconcileReason.LOCATION_CHANGED) }
            .onFailure { error ->
                logger.error(
                    SalahDiagnosticEvents.RECONCILE_UNAVAILABLE,
                    error,
                    mapOf("reason" to "location_changed"),
                )
            }
    }

    // --- Presentation -----------------------------------------------------------

    /** Fills the manual-entry form with the device's zone, if it is still empty. */
    fun suggestDeviceZone() {
        update { current ->
            if (current.form.timeZoneId.isNotBlank()) return@update current
            current.copy(form = current.form.copy(timeZoneId = ZoneId.systemDefault().id))
        }
    }

    fun dismissMessage() = update { it.copy(message = null) }

    fun dismissCalibrationNote() = update { it.copy(isCalibrationNoteDismissed = true) }

    // --- Derivation -------------------------------------------------------------

    private fun buildHeading(
        inputs: HeadingInputs,
        current: BearingSnapshot,
    ): QiblaHeadingUi? {
        val magneticDegrees = inputs.smoothedMagneticDegrees ?: return null
        val magnetic = HeadingMath.magnetic(magneticDegrees)

        // Declination is applied here and only here. HeadingMath.toTrueHeading consumes a
        // MagneticHeading, so there is no code path that could apply it a second time.
        val trueHeading = current.declinationDegrees?.let { declination ->
            HeadingMath.toTrueHeading(magnetic, declination)
        }

        val turn = trueHeading?.let { heading ->
            (current.bearing as? QiblaBearing.Available)?.let { available ->
                HeadingMath.turnFromTrue(heading, HeadingMath.trueHeading(available.degrees))
            }
        }

        return QiblaHeadingUi(
            trueDegrees = trueHeading?.degrees,
            magneticDegrees = magnetic.degrees,
            turnDegrees = turn,
            accuracy = inputs.accuracy,
            tiltDegrees = inputs.tiltDegrees,
            isOrientationUsable = inputs.isOrientationUsable,
        )
    }

    private fun buildUiState(
        current: BearingSnapshot,
        screen: ScreenInputs,
        heading: QiblaHeadingUi?,
    ): QiblaUiState {
        val locationState = resolveLocationState(current, screen)
        val advice = if (heading == null) {
            CalibrationAdvice.NOT_REPORTED
        } else {
            QiblaStateReducer.calibrationAdvice(sensorAvailability, heading.accuracy)
        }
        val showNote = advice == CalibrationAdvice.RECOMMENDED && !screen.isCalibrationNoteDismissed

        return QiblaUiState(
            phase = QiblaStateReducer.phase(
                isAcquiringLocation = screen.isAcquiringLocation,
                location = locationState,
                bearing = current.bearing,
                sensor = sensorAvailability,
                hasHeading = heading != null,
                // A dismissed note stops driving the phase, so the screen cannot keep nagging.
                calibration = if (showNote) advice else CalibrationAdvice.NOT_NEEDED,
            ),
            location = locationState,
            bearing = current.bearing,
            heading = heading,
            sensorAvailable = sensorAvailability is CompassAvailability.Available,
            sensorFamily = (sensorAvailability as? CompassAvailability.Available)?.family,
            calibration = advice,
            showCalibrationNote = showNote,
            kaabaSource = KaabaReference.SOURCE,
            form = screen.form.withStoredLocation(current.location),
            isAcquiringLocation = screen.isAcquiringLocation,
            message = screen.message,
            permissionState = screen.permissionState,
        )
    }

    /**
     * What the screen knows about the user's position.
     *
     * A stored location wins over a failed acquisition: if the user has coordinates, a declined
     * permission does not stop the bearing working, and reporting "permission denied" while showing
     * a valid bearing would be contradictory.
     */
    private fun resolveLocationState(
        current: BearingSnapshot,
        screen: ScreenInputs,
    ): QiblaLocationState {
        val stored = current.location
        if (stored != null) {
            return QiblaLocationState.Available(
                location = stored,
                freshness = LocationFreshness.of(stored, clock.instant()),
            )
        }
        return screen.locationFailure ?: QiblaLocationState.None
    }

    private fun update(transform: (ScreenInputs) -> ScreenInputs) {
        screenInputs.update(transform)
    }

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    /** The stored location, its declination and the bearing derived from them. */
    private data class BearingSnapshot(
        val location: LocationInfo? = null,
        val declinationDegrees: Double? = null,
        val bearing: QiblaBearing? = null,
    )

    private data class HeadingInputs(
        val smoothedMagneticDegrees: Double? = null,
        val accuracy: MagneticAccuracy? = null,
        val tiltDegrees: Double = 0.0,
        val isOrientationUsable: Boolean = true,
    )

    private data class ScreenInputs(
        val form: LocationFormState = LocationFormState(),
        val isAcquiringLocation: Boolean = false,
        val message: QiblaMessage? = null,
        val isCalibrationNoteDismissed: Boolean = false,
        val permissionState: LocationPermissionState = LocationPermissionState.DENIED,
        val locationFailure: QiblaLocationState? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * How long a fix is given.
         *
         * Fifteen seconds is long enough for a cold GNSS start outdoors in most conditions and short
         * enough that the button does not appear to have done nothing. Every outcome is reported, so
         * exceeding it produces an explanation rather than an endless spinner.
         */
        val ACQUISITION_TIMEOUT = 15.seconds
    }
}

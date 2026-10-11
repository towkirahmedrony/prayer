package io.github.towkirahmedrony.salah.data.qibla

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationAcquisitionResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProviderSelection
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * One-shot device positioning over `LocationManager`.
 *
 * **Why `LocationManager` and not the fused provider.** Google Play Services is not a dependency of
 * this project and does not need to become one for a single fix. The framework provider needs no
 * account, works on devices without Play Services, and is accurate enough that the difference does
 * not show up in a bearing.
 *
 * **Why `LocationManagerCompat.getCurrentLocation` and not
 * `LocationManager.getCurrentLocation`.** The framework method exists only from API 30, and the
 * pre-30 alternative — `requestLocationUpdates` plus manual removal — is deprecated and would have
 * forced a hand-rolled single-shot listener. The androidx compatibility method provides exactly the
 * same one-shot, cancellable request from API 14 upwards, so there is a single code path with no
 * deprecated calls and no version branch to get wrong.
 *
 * **Why one-shot.** A continuous subscription would keep the GNSS receiver awake to answer a
 * question that does not change while the user stands still. The position is stored and reused, which
 * is also what makes the feature work offline.
 *
 * **No coordinates are logged.** The acquisition event records the accuracy and the provider, never
 * the latitude or longitude.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val permissions: LocationPermissionChecker,
    private val logger: DiagnosticsLogger,
) : LocationProvider {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun servicesEnabled(): Boolean {
        val manager = locationManager ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)
    }

    override suspend fun currentLocation(timeout: Duration): LocationAcquisitionResult {
        val manager = locationManager
            ?: return fail(LocationAcquisitionResult.Failed("no location service"), "no_service")

        val precision = permissions.state().precision
            ?: return fail(LocationAcquisitionResult.PermissionDenied, "permission_denied")

        if (!servicesEnabled()) {
            return fail(LocationAcquisitionResult.ServicesDisabled, "services_disabled")
        }

        val source = LocationProviderSelection.bestEnabled(precision) { candidate ->
            runCatching { manager.isProviderEnabled(candidate.toAndroidProvider()) }
                .getOrDefault(false)
        } ?: return fail(LocationAcquisitionResult.ServicesDisabled, "no_provider_enabled")

        val result = withTimeoutOrNull(timeout) { requestOnce(manager, source) }
            ?: fail(LocationAcquisitionResult.TimedOut, "timeout")

        if (result is LocationAcquisitionResult.Acquired) {
            logger.info(
                SalahDiagnosticEvents.QIBLA_LOCATION_ACQUIRED,
                mapOf(
                    // Precision bucket, not a coordinate: the log stays free of the user's position.
                    "provider" to source.name,
                    "precision" to precision.name,
                    "accuracyMetres" to result.accuracyMetres,
                ),
            )
        }
        return result
    }

    private suspend fun requestOnce(
        manager: LocationManager,
        source: LocationSource,
    ): LocationAcquisitionResult = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }

        try {
            LocationManagerCompat.getCurrentLocation(
                manager,
                source.toAndroidProvider(),
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (!continuation.isActive) return@getCurrentLocation
                continuation.resume(location?.toAcquisitionResult() ?: noFix())
            }
        } catch (error: SecurityException) {
            if (continuation.isActive) {
                continuation.resume(LocationAcquisitionResult.PermissionDenied)
            }
        } catch (error: Exception) {
            if (continuation.isActive) {
                continuation.resume(
                    LocationAcquisitionResult.Failed(error.javaClass.simpleName),
                )
            }
        }
    }

    private fun noFix(): LocationAcquisitionResult {
        logger.warn(SalahDiagnosticEvents.QIBLA_LOCATION_FAILED, mapOf("reason" to "no_fix"))
        return LocationAcquisitionResult.Failed("provider returned no fix")
    }

    private fun fail(
        result: LocationAcquisitionResult,
        reason: String,
    ): LocationAcquisitionResult {
        logger.warn(SalahDiagnosticEvents.QIBLA_LOCATION_FAILED, mapOf("reason" to reason))
        return result
    }

    /**
     * Converts a platform fix.
     *
     * `Location.time` is the UTC instant of the fix and is `0` when the provider did not set one;
     * substituting the current time keeps the reading ageable instead of making it unusable, and the
     * value is only ever used to decide whether to call a position current.
     */
    private fun Location.toAcquisitionResult(): LocationAcquisitionResult =
        LocationAcquisitionResult.Acquired(
            latitude = latitude,
            longitude = longitude,
            accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else null,
            acquiredAtEpochMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )

    private fun LocationSource.toAndroidProvider(): String = when (this) {
        LocationSource.GPS -> LocationManager.GPS_PROVIDER
        LocationSource.NETWORK -> LocationManager.NETWORK_PROVIDER
        LocationSource.PASSIVE -> LocationManager.PASSIVE_PROVIDER
    }
}

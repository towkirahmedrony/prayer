package io.github.towkirahmedrony.salah.data.qibla

import android.annotation.SuppressLint
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
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPrecision
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationProviderSelection
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One-shot device positioning over `LocationManager`.
 *
 * **Speed strategy.** The result is only used for prayer times and for naming an area against a
 * 20 km / 75 km threshold, so a network fix is accurate enough. The order is therefore:
 *
 * 1. A recent cached fix from any enabled provider (returns immediately).
 * 2. A live network fix, limited to [NETWORK_SLICE].
 * 3. A live GPS fix with whatever time remains (only when precise location is granted).
 *
 * The cached fix keeps the platform's own timestamp, so the freshness logic still ages it honestly.
 *
 * **No coordinates are logged.** Only the provider, precision and accuracy are recorded.
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

        val enabled = LocationProviderSelection.candidates(LocationPrecision.PRECISE)
            .filter { isEnabled(manager, it) }
        if (enabled.isEmpty()) {
            return fail(LocationAcquisitionResult.ServicesDisabled, "no_provider_enabled")
        }

        // 1. Recent cached fix: instant.
        cachedFix(manager, enabled)?.let { cached ->
            logAcquired("CACHED", precision, cached)
            return cached
        }

        // 2 and 3. Live fix. Network first (fast, enough for an area name), GPS as the fallback.
        // PASSIVE never produces a fix on request, so it is only useful for the cached step.
        val liveOrder = LocationProviderSelection.candidates(LocationPrecision.APPROXIMATE)
            .filter { it != LocationSource.PASSIVE }
            .filter { it in enabled }
            .filter { precision == LocationPrecision.PRECISE || it != LocationSource.GPS }

        val start = TimeSource.Monotonic.markNow()
        var failure: LocationAcquisitionResult.Failed? = null

        for ((index, source) in liveOrder.withIndex()) {
            val remaining = timeout - start.elapsedNow()
            if (!remaining.isPositive()) break
            val isLast = index == liveOrder.lastIndex
            val slice = if (isLast) remaining else minOf(remaining, NETWORK_SLICE)

            when (val result = withTimeoutOrNull(slice) { requestOnce(manager, source) }) {
                is LocationAcquisitionResult.Acquired -> {
                    logAcquired(source.name, precision, result)
                    return result
                }
                LocationAcquisitionResult.PermissionDenied ->
                    return fail(result, "permission_denied")
                is LocationAcquisitionResult.Failed -> failure = result
                else -> Unit
            }
        }

        return failure ?: fail(LocationAcquisitionResult.TimedOut, "timeout")
    }

    private fun isEnabled(manager: LocationManager, source: LocationSource): Boolean =
        runCatching { manager.isProviderEnabled(source.toAndroidProvider()) }.getOrDefault(false)

    /** Newest cached fix that is recent and reasonably accurate, or `null`. */
    @SuppressLint("MissingPermission")
    private fun cachedFix(
        manager: LocationManager,
        sources: List<LocationSource>,
    ): LocationAcquisitionResult.Acquired? {
        val now = System.currentTimeMillis()
        return sources
            .mapNotNull { source ->
                runCatching { manager.getLastKnownLocation(source.toAndroidProvider()) }.getOrNull()
            }
            .filter { it.time > 0L && now - it.time in 0..MAX_CACHED_AGE_MILLIS }
            .filter { !it.hasAccuracy() || it.accuracy <= MAX_CACHED_ACCURACY_METRES }
            .maxByOrNull { it.time }
            ?.toAcquisitionResult()
    }

    private fun logAcquired(
        origin: String,
        precision: LocationPrecision,
        result: LocationAcquisitionResult.Acquired,
    ) {
        logger.info(
            SalahDiagnosticEvents.QIBLA_LOCATION_ACQUIRED,
            mapOf(
                "provider" to origin,
                "precision" to precision.name,
                "accuracyMetres" to result.accuracyMetres,
            ),
        )
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
     * Converts a platform fix. `Location.time` of `0` means "no recorded time" and is preserved as
     * `null`, so such a fix is aged as unknown instead of being relabelled as just taken.
     */
    private fun Location.toAcquisitionResult(): LocationAcquisitionResult.Acquired =
        LocationAcquisitionResult.Acquired(
            latitude = latitude,
            longitude = longitude,
            accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else null,
            acquiredAtEpochMillis = time.takeIf { it > 0L },
        )

    private fun LocationSource.toAndroidProvider(): String = when (this) {
        LocationSource.GPS -> LocationManager.GPS_PROVIDER
        LocationSource.NETWORK -> LocationManager.NETWORK_PROVIDER
        LocationSource.PASSIVE -> LocationManager.PASSIVE_PROVIDER
    }

    private companion object {
        /** How long the network provider gets before GPS is tried. */
        val NETWORK_SLICE: Duration = 6.seconds

        /** A cached fix older than this is ignored (10 minutes). */
        const val MAX_CACHED_AGE_MILLIS: Long = 10L * 60L * 1000L

        /** A cached fix less accurate than this is ignored. */
        const val MAX_CACHED_ACCURACY_METRES: Float = 5_000f
    }
}

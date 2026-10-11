package io.github.towkirahmedrony.salah.data.qibla

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionChecker
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState

/**
 * Reads the location permission state from the platform.
 *
 * **Never prompts.** Requesting permission is a user-visible action that belongs to the screen the
 * user is looking at, so this port answers only "what has already been decided" and Salah asks at
 * the moment the user taps a button.
 *
 * **Both permissions are declared, and either alone is enough.** On Android 12 and later the runtime
 * dialog offers "Approximate" and "Precise", which the platform implements by granting
 * `ACCESS_COARSE_LOCATION` alone or both. Reporting the two as distinct states means the UI can say
 * which one is in force and what it costs — for the Qibla it costs almost nothing, since a coarse
 * fix moves the bearing by a few hundredths of a degree.
 *
 * Note the ordering: fine is checked first because a device can hold both, and precise is the
 * stronger statement.
 */
class AndroidLocationPermissionChecker(
    private val context: Context,
) : LocationPermissionChecker {

    override fun state(): LocationPermissionState {
        val fine = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> LocationPermissionState.PRECISE
            coarse -> LocationPermissionState.COARSE
            else -> LocationPermissionState.DENIED
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

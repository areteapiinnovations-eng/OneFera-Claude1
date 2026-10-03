package com.onefera.app.data.near

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import com.onefera.app.data.model.Geo
import com.onefera.app.data.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One-shot approximate location from the platform LocationManager (no Play services needed).
 * Only ACCESS_COARSE_LOCATION is requested, and the result is rounded again by [Geo.coarse].
 */
@Singleton
class LocationProvider @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): LatLng? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = providers.firstNotNullOfOrNull { provider ->
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, EXECUTOR) { cont.resume(it) }
                }
            }
        }
        val location = fresh ?: providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        return location?.let { Geo.coarse(LatLng(it.latitude, it.longitude)) }
    }

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

/** "fused" exists as a provider name from API 31; older devices simply won't report it enabled. */
private const val FUSED_PROVIDER = "fused"

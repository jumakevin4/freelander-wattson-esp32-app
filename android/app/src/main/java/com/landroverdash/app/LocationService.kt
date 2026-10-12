package com.landroverdash.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

private const val TAG_LOC = "EvLocation"
/** How often to re-check for a GPS provider that was off at start(). */
private const val RETRY_MS = 20_000L

/** One GPS fix. Altitude is metres above the WGS84 ellipsoid (raw GPS). */
data class GpsSample(
    val timeMs:      Long,
    val altitudeM:   Float,
    val accuracyM:   Float,    // horizontal accuracy, -1 if unknown
    val lat:         Double,
    val lon:         Double,
    val hasAltitude: Boolean
)

/** One point on the trip elevation profile: smoothed altitude at a trip distance. */
data class ElevationPoint(val distanceKm: Float, val altitudeM: Float)

/**
 * Thin wrapper over the framework [LocationManager] (no Google Play Services,
 * which can be unreliable on aftermarket head units). Emits a [GpsSample] per fix.
 *
 * Callers MUST hold ACCESS_FINE_LOCATION before calling [start]; the activity
 * gates this behind a runtime permission request.
 */
class LocationService(context: Context) {

    private val lm = context.applicationContext
        .getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _samples = MutableSharedFlow<GpsSample>(extraBufferCapacity = 64)
    val samples: SharedFlow<GpsSample> = _samples

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _samples.tryEmit(
                GpsSample(
                    timeMs      = location.time,
                    altitudeM   = if (location.hasAltitude()) location.altitude.toFloat() else 0f,
                    accuracyM   = if (location.hasAccuracy()) location.accuracy else -1f,
                    lat         = location.latitude,
                    lon         = location.longitude,
                    hasAltitude = location.hasAltitude()
                )
            )
        }

        // Required overrides for API 29 (default methods only arrive at API 30).
        override fun onProviderEnabled(provider: String) {
            Log.i(TAG_LOC, "GPS provider enabled")
        }
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG_LOC, "GPS provider disabled — elevation will stall until it returns")
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private var running = false
    private var wanted  = false        // start() was asked for, whether or not it took
    private val retry   = Handler(Looper.getMainLooper())

    /**
     * Begins GPS updates (~1 s cadence). No-op if already running.
     *
     * Retries while the provider is off rather than giving up. This used to
     * return silently if GPS happened to be disabled at the instant it was
     * called — very likely on a head unit that has just booted — and nothing
     * ever called it again, so elevation read "--" for the entire trip with no
     * indication why.
     */
    @SuppressLint("MissingPermission")  // permission is enforced by the caller
    fun start() {
        wanted = true
        if (running) return

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG_LOC, "GPS off — retrying in ${RETRY_MS / 1000}s")
            retry.removeCallbacks(retryRunnable)
            retry.postDelayed(retryRunnable, RETRY_MS)
            return
        }

        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,    // min 1 s between updates
                0f,        // every fix, regardless of distance
                listener,
                Looper.getMainLooper()
            )
            running = true
            Log.i(TAG_LOC, "GPS updates started")
        } catch (e: SecurityException) {
            // Permission revoked between the caller's check and here.
            Log.e(TAG_LOC, "GPS denied — no elevation this trip", e)
            wanted = false
        }
    }

    private val retryRunnable = Runnable { if (wanted && !running) start() }

    fun stop() {
        wanted = false
        retry.removeCallbacks(retryRunnable)
        if (!running) return
        lm.removeUpdates(listener)
        running = false
        Log.i(TAG_LOC, "GPS updates stopped")
    }
}

package com.landroverdash.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide copy of the current trip, published by [DashboardViewModel].
 *
 * The side rail can open Energy or Terrain from any screen, not only from the
 * dashboard, so those pages can't rely on Intent extras built by the
 * dashboard. They observe this instead — and update live while open, which
 * the old snapshot pages couldn't.
 *
 * Written only by DashboardViewModel; everything else reads.
 */
object LiveTripData {
    val rangeKm             = MutableStateFlow(0f)
    /** 0–100, or -1 when not known yet. */
    val socPct              = MutableStateFlow(-1)

    /** Wh/km the range projection is using right now; 0 when there is none. */
    val rangeBasisWhPerKm   = MutableStateFlow(0f)
    /** True when that basis is the all-trips average because the 30-minute
     *  window has no driving in it yet. */
    val rangeBasisIsLifetime = MutableStateFlow(false)

    /** Last 30 minutes of driving, Wh/km. 0 until there is enough of it. */
    val rollingEffWhPerKm   = MutableStateFlow(0f)
    /** This trip, Wh/km (net, so regen is counted). */
    val tripEffWhPerKm      = MutableStateFlow(0f)
    /** Average over all saved trips, Wh/km. */
    val lifetimeEffWhPerKm  = MutableStateFlow(0f)

    val tripDistanceKm      = MutableStateFlow(0f)
    val tripTimeMin         = MutableStateFlow(0)
    val avgSpeedKmh         = MutableStateFlow(0f)

    /** Energy put back by regen this trip, kWh (gross). */
    val regenKwh            = MutableStateFlow(0f)

    val energyBuckets       = MutableStateFlow<List<EnergyBucket>>(emptyList())
    val bucketMinutes       = MutableStateFlow(1)

    val elevationProfile    = MutableStateFlow<List<ElevationPoint>>(emptyList())
    val currentElevationM   = MutableStateFlow(Float.NaN)
    val minElevationM       = MutableStateFlow(Float.NaN)
    val maxElevationM       = MutableStateFlow(Float.NaN)

    /** Cross-checks on this trip's figures (Settings → Diagnostics); null before the first packet. */
    val check               = MutableStateFlow<TripCheck?>(null)
}

/**
 * Independent checks on the two inputs km/kWh is built from, so a wrong
 * figure can be traced to its source: GPS distance against the distance from
 * the car's speed signal, the battery-% drop against the measured energy, and
 * how many readings arrived without a value.
 */
data class TripCheck(
    /** Distance from the car's speed signal, live packets only. */
    val liveKm: Float,
    /** GPS distance over the same live, moving stretches. */
    val gpsKm: Float,
    /** Distance from history the car replayed (driving before the link was up). */
    val replayedKm: Float,
    /** Readings taken while moving (above 3 km/h). */
    val movingReadings: Int,
    /** …of which arrived with no pack voltage. */
    val noVoltage: Int,
    /** …of which had exactly 0 A (no fresh current reading, or a true coast). */
    val zeroCurrent: Int,
    /**
     * …of which were a lone 0 A between two readings drawing current. A coast
     * eases down through smaller values; a lone zero is a reading that didn't
     * arrive.
     */
    val zeroBlips: Int = 0,
    /**
     * Pack sag, Ω: how far the pack voltage drops per amp drawn, from quick load
     * changes (median). Measured from the same readings the energy uses, so if
     * the current reads low this comes out high. NaN until [sagPairs] ≥ 20.
     */
    val sagOhm: Float = Float.NaN,
    val sagPairs: Int = 0,
    val drawnKwh: Float,
    val regenKwh: Float,
    val netKwh: Float,
    /** Battery % when the trip got going (live), and now. -1 = not known. */
    val socStart: Int,
    val socNow: Int,
    /** Net energy measured over the same span as that battery-% drop. */
    val socSpanKwh: Float
)

package com.landroverdash.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Terrain — elevation along this trip, with the stretches where regen put
 * energy back drawn in the accent colour.
 *
 * Reads [LiveTripData], so it opens from the rail on any screen and keeps
 * drawing while open. (It used to take a snapshot through Intent extras.)
 */
class ElevationActivity : WattsonActivity() {

    /** Ignore up-and-down smaller than this when adding up climb and descent:
     *  GPS altitude wanders a few metres even when parked. */
    private val CLIMB_HYSTERESIS_M = 3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_elevation)
        bindNavRail(Screen.TERRAIN)

        val profile = findViewById<ElevationProfileView>(R.id.elevationProfile)
        val subtitle = findViewById<TextView>(R.id.tvTerrainSubtitle)
        val now = cell(R.id.cellNow, "Now")
        val highest = cell(R.id.cellHighest, "Highest")
        val lowest = cell(R.id.cellLowest, "Lowest")
        val climbed = cell(R.id.cellClimbed, "Climbed")
        val descended = cell(R.id.cellDescended, "Descended")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LiveTripData.elevationProfile.collect { pts ->
                        profile.setPoints(pts)
                        val (up, down) = climbAndDescent(pts)
                        val known = pts.size >= 2
                        climbed.text = metres(when {
                            !known -> Fmt.NONE; up < 0.5f -> "0"; else -> Fmt.signed(up) })
                        descended.text = metres(when {
                            !known -> Fmt.NONE; down < 0.5f -> "0"; else -> Fmt.signed(-down) })
                        profile.contentDescription = describe(pts, up, down)
                    }
                }
                launch {
                    combine(LiveTripData.currentElevationM, LiveTripData.maxElevationM,
                        LiveTripData.minElevationM) { c, hi, lo -> Triple(c, hi, lo) }
                        .collect { (c, hi, lo) ->
                            now.text = metres(Fmt.grouped(c))
                            highest.text = metres(Fmt.grouped(hi))
                            lowest.text = metres(Fmt.grouped(lo))
                        }
                }
                launch {
                    LiveTripData.tripDistanceKm.collect { km ->
                        subtitle.text = String.format(Locale.US, "This trip · %.1f km", km)
                    }
                }
            }
        }
    }

    private fun cell(id: Int, label: String): TextView {
        val root = findViewById<View>(id)
        root.findViewById<TextView>(R.id.tvCellLabel).text = label
        return root.findViewById(R.id.tvCellValue)
    }

    private fun metres(v: String): CharSequence =
        if (v == Fmt.NONE) v else Fmt.valueUnit(this, v, "m")

    /**
     * Total climb and descent along the profile, in metres. A change only
     * counts once it has moved [CLIMB_HYSTERESIS_M] from the last turning
     * point, so GPS jitter doesn't add up to phantom hills.
     */
    private fun climbAndDescent(pts: List<ElevationPoint>): Pair<Float, Float> {
        if (pts.size < 2) return 0f to 0f
        var ref = pts[0].altitudeM
        var up = 0f
        var down = 0f
        for (p in pts) {
            val d = p.altitudeM - ref
            if (d >= CLIMB_HYSTERESIS_M) { up += d; ref = p.altitudeM }
            else if (d <= -CLIMB_HYSTERESIS_M) { down -= d; ref = p.altitudeM }
        }
        return up to down
    }

    private fun describe(pts: List<ElevationPoint>, up: Float, down: Float): String {
        if (pts.size < 2) return "No elevation data yet this trip"
        val hi = pts.maxByOrNull { it.altitudeM }!!
        return String.format(Locale.US,
            "Elevation along this trip: %.0f km, highest %,.0f m at %.0f km, climbed %,.0f m, descended %,.0f m, now %,.0f m",
            pts.last().distanceKm, hi.altitudeM, hi.distanceKm, up, down, pts.last().altitudeM)
    }
}

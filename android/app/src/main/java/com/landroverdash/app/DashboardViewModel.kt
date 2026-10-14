package com.landroverdash.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One energy window for the trip energy graph: [wh] drawn from the pack and
 * [regenWh] put back by regen during the window (both positive).
 */
data class EnergyBucket(val timestampMs: Long, val wh: Float, val regenWh: Float = 0f)

private const val TAG_VM = "EvDashboard"

/**
 * Pack resistance the app assumes, Ω: typical for an 84S pack (a 200 A pull
 * sags it ~24 V). Used to undo the sag for the battery %, and as the yardstick
 * for the pack-sag check in Diagnostics.
 */
internal const val PACK_R_OHM = 0.12f

/** One 500ms data sample for the rolling 30-min efficiency window. */
private data class WindowSample(val timestampMs: Long, val wh: Float, val distKm: Float)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    val btService = BluetoothService()
    private val locationService = LocationService(app)
    private val db  = AppDatabase.get(app)
    private val dao = db.tripDao()
    private val prefs = app.getSharedPreferences("trip_backup", Context.MODE_PRIVATE)

    // PersistScope, not viewModelScope: ViewModel.clear() cancels viewModelScope
    // before onCleared() runs, so a ten-minute or hourly block flushing near
    // teardown was silently discarded.
    val energyManager = EnergyManager(app, db.energyDao(), PersistScope)

    private val _carData = MutableStateFlow(CarData())
    val carData: StateFlow<CarData> = _carData.asStateFlow()

    val connectionState = btService.connectionState

    private var tripStartMs:       Long    = 0L
    private var lastUpdateMs:      Long    = 0L
    private var lastBufferedTs:    Long    = 0L   // ESP32 uptime of previous synced record
    private var lastMovementMs:    Long    = 0L
    private var lastBackupMs:      Long    = 0L   // throttles disk-backed trip backups
    private var tripElapsedMs:     Long    = 0L   // accumulated drive time (incl. buffered)
    private var tripSaved:         Boolean = false
    private var startSOC:          Int     = -1
    private var lastSOC:           Int     = 0
    private var maxSpeedKmh:       Float   = 0f
    private var totalSpeedSamples: Int     = 0
    private var totalSpeedSum:     Float   = 0f

    // ── Current-trip vehicle telemetry (snapshot for the details screen) ──────
    /** Latest pack voltage (V). 0 until live power data is connected. */
    var latestVoltageV: Float = 0f
        private set
    /** Latest battery temperature (°C). 0 until the firmware reports it. */
    var latestBattTempC: Float = 0f
        private set
    /** Latest motor temperature (°C). 0 until the firmware reports it. */
    var latestMotorTempC: Float = 0f
        private set
    /** Peak battery current (A) seen this trip. */
    var peakCurrentA: Float = 0f
        private set

    // Largest gap between samples we'll integrate over. Anything longer is treated
    // as a dropout (BT loss, app paused) so we don't fabricate distance/energy.
    private val MAX_SAMPLE_GAP_MS = 5_000L

    /**
     * Last pack voltage the car actually reported. A packet that arrives with
     * 0 V (the ESP32 had no fresh answer from the inverter) still carries its
     * speed and current, so it used to add its distance to the trip but no
     * energy at all, which made km/kWh look better than it was: a third of
     * readings like that turns 6 km/kWh into 8.7. Pack voltage changes slowly,
     * so the last real reading stands in. Cleared when the link drops.
     */
    private var lastPackVoltageV = 0f

    // ── Trip check (Settings → Diagnostics), see [TripCheck] ─────────────────
    private var chkLiveKm      = 0f
    private var chkReplayedKm  = 0f
    private var chkGpsKm       = 0f
    private var chkMoving      = 0
    private var chkNoVolts     = 0
    private var chkZeroAmps    = 0
    private var chkDrawnWh     = 0f
    private var chkSocStart    = -1
    private var chkSocNow      = -1
    private var chkSocSpanWh   = 0f
    /** Last usable GPS fix and the newest live speed, for the GPS distance. */
    private var lastFix: GpsSample? = null
    private var lastLiveSpeedKmh = 0f
    /**
     * The current's behaviour, from consecutive LIVE readings (replayed ones
     * interleave from firmware v7.3, so they would make false neighbours):
     * lone 0 A readings between two drawing ones, and the pack sag (volts
     * lost per amp) across quick load changes.
     */
    private var chkPrevA = Float.NaN
    private var chkPrevPrevA = Float.NaN
    private var chkPrevV = 0f
    private var chkPrevMs = 0L
    private var chkBlips = 0
    private val chkSag = FloatArray(600)
    private var chkSagCount = 0
    // Minimum interval between disk-backed trip backups.
    private val BACKUP_INTERVAL_MS = 15_000L

    private val _tripTimeMin       = MutableStateFlow(0)
    val tripTimeMin: StateFlow<Int> = _tripTimeMin.asStateFlow()

    private val _avgSpeedKmh       = MutableStateFlow(0f)
    val avgSpeedKmh: StateFlow<Float> = _avgSpeedKmh.asStateFlow()

    private val _tripDistanceKm    = MutableStateFlow(0f)
    val tripDistanceKm: StateFlow<Float> = _tripDistanceKm.asStateFlow()

    private val _efficiencyWhPerKm = MutableStateFlow(0f)
    val efficiencyWhPerKm: StateFlow<Float> = _efficiencyWhPerKm.asStateFlow()

    private val _remainingKwh      = MutableStateFlow(0f)
    val remainingKwh: StateFlow<Float> = _remainingKwh.asStateFlow()

    // Projected range in km (requires OpenInverter energy data — Task #15)
    private val _projectedRangeKm = MutableStateFlow(0f)
    val projectedRangeKm: StateFlow<Float> = _projectedRangeKm.asStateFlow()

    // Energy buckets for the trip energy-use graph — ADAPTIVE granularity:
    // accumulate at 1-min base resolution; the display stays on the FINER
    // ladder step until the coarser one would already look populated
    // (≥ MIN_BARS), with a hard legibility cap as backstop. The chart fills
    // continuously from minute one and never collapses to a sparse view.
    //   0–48 min → 1-min bars (grows 1..48)
    //   then     → 5-min bars (starts at ~10, grows to ~20)
    //   100 min  → 10-min bars (starts at 10) … and so on up the ladder.
    private val BUCKET_MS       = 60 * 1000L          // 1-min base buckets
    private val GRAN_LADDER     = intArrayOf(1, 5, 10, 30, 60, 120)
    private val MIN_BARS        = 10                  // coarser view must show at least this
    private val ABS_MAX_BARS    = 48                  // legibility cap for the finer view
    /** Ceiling on buckets closed by a single sample, so a long gap can't spin. */
    private val MAX_BUCKETS_PER_SAMPLE = 120
    private var bucketStartMs   = 0L
    private var bucketWh        = 0f
    private var lastSessionWh   = 0f
    /**
     * Clock the energy buckets advance on. Equals the wall clock for live
     * packets; steps forward by each replayed record's own dt during a history
     * sync, when thousands of records share one wall-clock instant.
     */
    private var sampleClockMs   = 0L
    private val baseBuckets     = mutableListOf<EnergyBucket>()
    private val _energyBuckets  = MutableStateFlow<List<EnergyBucket>>(emptyList())
    val energyBuckets: StateFlow<List<EnergyBucket>> = _energyBuckets.asStateFlow()

    private val _bucketMinutes  = MutableStateFlow(1)
    val bucketMinutes: StateFlow<Int> = _bucketMinutes.asStateFlow()

    /** Step up the ladder only when the coarser view is worth looking at. */
    private fun chooseGranularity(nBase: Int): Int {
        var g = GRAN_LADDER[0]
        for (i in 1 until GRAN_LADDER.size) {
            val coarser     = GRAN_LADDER[i]
            val fineCount   = (nBase + g - 1) / g
            val coarseCount = (nBase + coarser - 1) / coarser
            if (coarseCount >= MIN_BARS || fineCount > ABS_MAX_BARS) g = coarser else break
        }
        return g
    }

    private fun rebuildDisplayBuckets() {
        val g = chooseGranularity(baseBuckets.size)
        val out = ArrayList<EnergyBucket>((baseBuckets.size + g - 1) / g)
        var i = 0
        while (i < baseBuckets.size) {
            val end = minOf(i + g, baseBuckets.size)
            var wh = 0f
            var regenWh = 0f
            for (j in i until end) { wh += baseBuckets[j].wh; regenWh += baseBuckets[j].regenWh }
            out.add(EnergyBucket(baseBuckets[i].timestampMs, wh, regenWh))
            i = end
        }
        _bucketMinutes.value = g
        _energyBuckets.value = out
    }

    // Rolling 30-min efficiency window for projected range
    private val WINDOW_MS     = 30 * 60 * 1000L
    private val windowSamples = ArrayDeque<WindowSample>()
    /** Running totals over [windowSamples], kept in step with every add/remove. */
    private var winWhSum   = 0f
    private var winDistSum = 0f

    // ── Elevation (GPS) ──────────────────────────────────────────────────────
    private val _currentElevationM = MutableStateFlow(Float.NaN)
    val currentElevationM: StateFlow<Float> = _currentElevationM.asStateFlow()

    private val _minElevationM = MutableStateFlow(Float.NaN)
    val minElevationM: StateFlow<Float> = _minElevationM.asStateFlow()

    private val _maxElevationM = MutableStateFlow(Float.NaN)
    val maxElevationM: StateFlow<Float> = _maxElevationM.asStateFlow()

    private val _elevationProfile = MutableStateFlow<List<ElevationPoint>>(emptyList())
    val elevationProfile: StateFlow<List<ElevationPoint>> = _elevationProfile.asStateFlow()

    private val elevationSmoothing = ArrayDeque<Float>()  // last few raw altitudes
    private val elevationPoints    = mutableListOf<ElevationPoint>()
    private var lastProfileDistKm  = -1f
    private val ELEV_SMOOTH_N        = 5       // samples to average out GPS noise
    // Adaptive resolution: start dense (~20 m per point) so the profile fills
    // from the first metres; when the budget is hit, thin to every 2nd point
    // and double the step — the profile always fits and keeps growing.
    private var elevStepKm           = 0.02f
    private val ELEV_PROFILE_BUDGET  = 400

    private val STOP_TIMEOUT_MS      = 15 * 60 * 1000L
    private val MIN_TRIP_DISTANCE_KM = 0.5f

    /** Lifetime avg Wh/km from trip history — startup-range fallback until the
     *  rolling 30-min window has data, and the "your average" the Drive and
     *  Energy screens coach against. 0 while no trips are recorded yet. */
    private val _lifetimeEffWhPerKm = MutableStateFlow(0f)
    val lifetimeEffWhPerKm: StateFlow<Float> = _lifetimeEffWhPerKm.asStateFlow()

    /** Last 30 minutes of driving, Wh/km — what the range projection uses. */
    private val _rollingEffWhPerKm = MutableStateFlow(0f)
    val rollingEffWhPerKm: StateFlow<Float> = _rollingEffWhPerKm.asStateFlow()

    // ── Regen ────────────────────────────────────────────────────────────────
    // sessionWh is net (regen lowers it). Each packet's fall in it is energy
    // put back; tracked separately so the energy graph can draw regen below the
    // line and the Energy page can say how much was recovered.
    /** Recovered this trip, Wh. Replayed records count — it is a trip total. */
    private var regenWhTotal  = 0f
    /** Recovered in the energy bucket being filled. */
    private var bucketRegenWh = 0f
    /** Drawn / recovered since the last elevation profile point (live only). */
    private var segDrawWh     = 0f
    private var segRegenWh    = 0f

    init {
        // Inverter errors are logged on the phone (Settings → Inverter).
        FaultLog.init(app)

        // Recover any interrupted trip off the main thread — the DB open + insert
        // must never block ViewModel construction (cold-start ANR).
        viewModelScope.launch(Dispatchers.IO) { recoverBackupIfExists() }

        viewModelScope.launch {
            dao.avgEfficiencyWhPerKm().collect { _lifetimeEffWhPerKm.value = it }
        }

        // Publish the trip process-wide. The side rail opens Energy and Terrain
        // from any screen, so they read these instead of Intent extras — and
        // keep updating while they are open.
        mirror(projectedRangeKm,   LiveTripData.rangeKm)
        mirror(efficiencyWhPerKm,  LiveTripData.tripEffWhPerKm)
        mirror(lifetimeEffWhPerKm, LiveTripData.lifetimeEffWhPerKm)
        mirror(rollingEffWhPerKm,  LiveTripData.rollingEffWhPerKm)
        mirror(tripDistanceKm,     LiveTripData.tripDistanceKm)
        mirror(tripTimeMin,        LiveTripData.tripTimeMin)
        mirror(avgSpeedKmh,        LiveTripData.avgSpeedKmh)
        mirror(energyBuckets,      LiveTripData.energyBuckets)
        mirror(bucketMinutes,      LiveTripData.bucketMinutes)
        mirror(elevationProfile,   LiveTripData.elevationProfile)
        mirror(currentElevationM,  LiveTripData.currentElevationM)
        mirror(minElevationM,      LiveTripData.minElevationM)
        mirror(maxElevationM,      LiveTripData.maxElevationM)
        viewModelScope.launch { carData.collect { LiveTripData.socPct.value = it.battSOC } }

        // LiveVehicleData is a process-wide singleton, so it outlives this
        // ViewModel. Back out of the dashboard and return without the process
        // being killed and it still held the PREVIOUS trip's peak current,
        // minimum 12 V and used kWh — shown next to a trip reading 0.0 km, and
        // unable to fall because peak and minimum only ever move one way.
        LiveVehicleData.reset()
        // Nothing is live until this ViewModel's own link delivers a packet.
        LiveVehicleData.onLinkLost()
        LiveTripData.regenKwh.value = 0f
        LiveTripData.rangeBasisWhPerKm.value = 0f
        LiveTripData.rangeBasisIsLifetime.value = false
        LiveTripData.check.value = null

        // Link lost: clear the live readings so no screen shows them as current.
        viewModelScope.launch {
            btService.connectionState.collect { s ->
                if (s == BluetoothService.ConnectionState.DISCONNECTED ||
                    s == BluetoothService.ConnectionState.ERROR) {
                    LiveVehicleData.onLinkLost()
                    // The pack may be charged before the link is back.
                    lastPackVoltageV = 0f
                }
            }
        }

        // Publish the live link so screens that don't own it (Settings →
        // firmware update) can talk to the ESP32 over the same socket.
        VehicleLink.register(btService)

        viewModelScope.launch {
            btService.dataFlow.collect { raw ->
                val now = System.currentTimeMillis()
                // Voltage-based SOC (Settings): when 100%/0% voltages are set and
                // live pack voltage exists, SOC is derived from voltage. The
                // substituted packet feeds EVERYTHING downstream (battery circle,
                // energy, efficiency, trip SOC).
                // Replayed history is for the ACCUMULATORS ONLY — it must never
                // reach the display. Publishing it drove thousands of historical
                // readings through the dashboard during a sync, so voltages,
                // speeds and SOC flickered through old values and meant nothing
                // until the replay finished. Computed before SOC so the smoother
                // can skip replayed samples too.
                val live = !raw.isSynced
                // battSOC = -1 means "not known yet" (no usable pack voltage), so
                // the ring can show "--" for that and a real "0%" for a flat pack.
                // The firmware's own "soc" field is always 0 and never meant 0 %.
                val data = raw.copy(battSOC = voltageSoc(raw, live) ?: -1)

                if (live) {
                    _carData.value = data
                    _remainingKwh.value =
                        if (data.battSOC >= 0) energyManager.remainingKwh(data.battSOC) else 0f
                    latestVoltageV = data.battVoltage
                    if (data.battTempC  != 0f) latestBattTempC  = data.battTempC
                    if (data.motorTempC != 0f) latestMotorTempC = data.motorTempC
                    // Publish for the Live Vehicle Data panel (details page), which
                    // doesn't own the BT connection but must update in real time.
                    LiveVehicleData.onSample(data)
                }

                if (data.battSOC >= 0) {
                    if (startSOC == -1) startSOC = data.battSOC
                    lastSOC = data.battSOC
                }
                // Peak is a property of the trip, so replayed records count too.
                if (data.battCurrent > peakCurrentA) peakCurrentA = data.battCurrent

                // Elapsed time since the previous sample, integrated over real time.
                // Synced records use the ESP32 uptime they carry; live packets use the
                // wall clock. Clamped so a dropout can't fabricate distance/energy.
                val dtMs: Long = if (data.isSynced) {
                    val d = if (lastBufferedTs > 0L) data.tsMs - lastBufferedTs else 0L
                    lastBufferedTs = data.tsMs
                    d.coerceIn(0L, MAX_SAMPLE_GAP_MS)
                } else {
                    val d = if (lastUpdateMs > 0L) now - lastUpdateMs else 0L
                    d.coerceIn(0L, MAX_SAMPLE_GAP_MS)
                }

                val distDelta = if (data.speedKmh > 0f)
                    data.speedKmh * dtMs / 3_600_000f else 0f

                if (live) { chkLiveKm += distDelta; lastLiveSpeedKmh = data.speedKmh }
                else chkReplayedKm += distDelta
                if (data.speedKmh > 3f) {
                    chkMoving++
                    if (data.battVoltage <= 0f) chkNoVolts++
                    if (data.battCurrent == 0f) chkZeroAmps++
                }
                if (live) checkCurrent(data, now)

                if (data.speedKmh > 0) {
                    tripSaved = false
                    // Only LIVE movement resets the inactivity timer. A replay
                    // is the ESP32 handing over driving that already happened,
                    // possibly hours ago, and treating it as "moving right now"
                    // restarted the 15-minute stop timeout at every sync.
                    if (live) lastMovementMs = now
                    if (tripStartMs == 0L) tripStartMs = now
                    _tripDistanceKm.value += distDelta
                    if (data.speedKmh > maxSpeedKmh) maxSpeedKmh = data.speedKmh
                    totalSpeedSamples++
                    totalSpeedSum += data.speedKmh
                    _avgSpeedKmh.value = totalSpeedSum / totalSpeedSamples
                }

                if (data.battVoltage > 0f) lastPackVoltageV = data.battVoltage
                val packVoltage = if (data.battVoltage > 0f) data.battVoltage else lastPackVoltageV
                energyManager.onSample(packVoltage, data.battCurrent,
                    data.speedKmh, distDelta, dtMs)
                // Publish used energy for the details page. Integrated from
                // measured volts x amps, so it is a real figure rather than
                // SOC x nominal capacity. Only on live packets: during a replay
                // this would race up through the whole trip in front of the user.
                if (live) LiveVehicleData.onEnergy(energyManager.sessionWh / 1000f)

                // Live clock only. From firmware v7.3 live packets arrive in the
                // middle of a sync; letting replayed records (100 a second) touch
                // this squeezed every live dt to ~10 ms and under-counted the
                // distance and energy actually being driven during the sync.
                if (live) lastUpdateMs = now
                // Accumulate real elapsed time from dtMs so buffered (pre-connect)
                // driving counts too — not just wall-clock since the app opened.
                if (tripStartMs > 0) {
                    tripElapsedMs += dtMs
                    _tripTimeMin.value = (tripElapsedMs / 60_000L).toInt()
                }

                val dist = _tripDistanceKm.value
                if (dist > 0.1f && energyManager.sessionWh > 0f)
                    _efficiencyWhPerKm.value = energyManager.sessionWh / dist

                // Delta energy this 500ms packet: drawn (delta) and recovered by
                // regen (regenDelta). The bucket and range maths use the drawn
                // energy exactly as before; regen is tracked alongside it.
                val rawDelta   = energyManager.sessionWh - lastSessionWh
                val delta      = rawDelta.coerceAtLeast(0f)
                val regenDelta = (-rawDelta).coerceAtLeast(0f)
                lastSessionWh = energyManager.sessionWh
                regenWhTotal += regenDelta
                chkDrawnWh += delta
                if (live && data.battSOC >= 0) {
                    // The % drop is compared with energy measured over the same
                    // live span, starting when the trip gets going.
                    if (chkSocStart < 0 && data.speedKmh > 0f) chkSocStart = data.battSOC
                    else if (chkSocStart >= 0) chkSocSpanWh += rawDelta
                    chkSocNow = data.battSOC
                }
                if (live) publishCheck()
                if (live) {
                    segDrawWh  += delta
                    segRegenWh += regenDelta
                    LiveTripData.regenKwh.value = regenWhTotal / 1000f
                }

                // 1-min base energy buckets → adaptive display granularity.
                //
                // Buckets advance on a clock built from each record's OWN dt, not
                // the wall clock. Replayed records all arrive within the same
                // second, so a wall-clock test never fires and an entire recovered
                // trip collapsed into one bucket. sampleClockMs stays equal to the
                // wall clock for live packets and steps forward by dtMs for
                // replayed ones, so bar spacing reflects when the driving actually
                // happened rather than when the phone heard about it.
                if (sampleClockMs == 0L) sampleClockMs = now
                // Replayed time never runs past the present: with live packets
                // interleaved (v7.3) it would otherwise race ahead and file the
                // replayed energy under minutes that haven't happened yet.
                sampleClockMs = if (live) now else minOf(sampleClockMs + dtMs, now)

                if (tripStartMs > 0L) {
                    if (bucketStartMs == 0L) bucketStartMs = tripStartMs
                    bucketWh += delta
                    bucketRegenWh += regenDelta
                    // CLOSE EVERY ELAPSED MINUTE, not just one per packet.
                    //
                    // Closing a single bucket regardless of how far the clock
                    // had moved meant a 30-minute Bluetooth dropout produced one
                    // bar covering half an hour — drawn the same width as every
                    // 1-minute bar beside it, so a long gap read as a brief dip.
                    // Idle minutes are emitted as genuine zeros instead.
                    var closed = 0
                    while ((sampleClockMs - bucketStartMs) >= BUCKET_MS &&
                           closed < MAX_BUCKETS_PER_SAMPLE) {
                        baseBuckets.add(EnergyBucket(bucketStartMs, bucketWh, bucketRegenWh))
                        bucketWh = 0f          // only the first bucket gets the energy
                        bucketRegenWh = 0f
                        bucketStartMs += BUCKET_MS
                        closed++
                    }
                    // A gap longer than the cap (a very long dropout) would cost
                    // thousands of iterations and thousands of meaningless bars;
                    // skip straight to the present instead.
                    if ((sampleClockMs - bucketStartMs) >= BUCKET_MS) {
                        bucketStartMs = sampleClockMs
                    }
                    // Only repaint the chart for live packets — during a replay
                    // this rebuilt the whole series once per recovered minute,
                    // for no one to see.
                    if (closed > 0 && live) rebuildDisplayBuckets()
                }

                // Rolling 30-min efficiency window → projected range.
                //
                // LIVE PACKETS ONLY, and this matters more than it looks. Every
                // replayed record carries the same wall-clock `now`, so the
                // eviction test below could never fire: the deque grew to the
                // entire sync while sumOf() walked all of it once per record.
                // Twenty thousand records is on the order of 200 million
                // operations — enough to stall this collector for minutes, which
                // then suspends dataFlow.emit(), which blocks the socket reader,
                // which leaves BluetoothService.active stuck true so no reconnect
                // can ever succeed. That is why the app had to be force-quit.
                //
                // Range is a live measure anyway; projecting it from replayed
                // history was never meaningful.
                if (live) {
                    // Running sums maintained incrementally. Summing the deque
                    // on every packet meant two full traversals of up to ~3,600
                    // entries twice a second, on the main thread, to produce two
                    // numbers that only change by one element at each end.
                    windowSamples.addLast(WindowSample(now, delta, distDelta))
                    winWhSum   += delta
                    winDistSum += distDelta
                    while (windowSamples.isNotEmpty() &&
                        (now - windowSamples.first().timestampMs) > WINDOW_MS) {
                        val gone = windowSamples.removeFirst()
                        winWhSum   -= gone.wh
                        winDistSum -= gone.distKm
                    }
                    // Float drift over a long trip: clamp rather than let a tiny
                    // negative residue invert the efficiency.
                    if (winWhSum   < 0f) winWhSum   = 0f
                    if (winDistSum < 0f) winDistSum = 0f
                    val rollingEff = if (winDistSum > 0.1f) winWhSum / winDistSum else 0f
                    val lifetimeEff = _lifetimeEffWhPerKm.value
                    // Same rule as before: the 30-minute window when it has data,
                    // else the all-trips average. Published so the Drive screen
                    // can say which one the range is based on.
                    val basis = when {
                        rollingEff > 5f  -> rollingEff
                        lifetimeEff > 5f -> lifetimeEff
                        else             -> 0f
                    }
                    _rollingEffWhPerKm.value = rollingEff
                    _projectedRangeKm.value =
                        if (basis > 0f) _remainingKwh.value * 1000f / basis else 0f
                    LiveTripData.rangeBasisWhPerKm.value = basis
                    LiveTripData.rangeBasisIsLifetime.value = rollingEff <= 5f && lifetimeEff > 5f

                    // Throttle disk-backed backups — no need to write twice a second.
                    if (tripStartMs > 0 && dist >= MIN_TRIP_DISTANCE_KM &&
                        now - lastBackupMs >= BACKUP_INTERVAL_MS) {
                        lastBackupMs = now
                        backupTripState()
                    }
                }
            }
        }

        // Independent inactivity timer — updates UI every 30s even when BT is off
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val now    = System.currentTimeMillis()
                val dist   = _tripDistanceKm.value
                val stopMs = if (lastMovementMs > 0) now - lastMovementMs else 0L
                if (lastMovementMs > 0 && !tripSaved &&
                    stopMs >= STOP_TIMEOUT_MS &&
                    dist >= MIN_TRIP_DISTANCE_KM) {
                    saveTripToDb()
                }
            }
        }

        // GPS elevation — smooth out noise, track current/min/max, build profile.
        viewModelScope.launch {
            locationService.samples.collect { s ->
                onGpsDistance(s)
                if (!s.hasAltitude) return@collect
                onElevationSample(s.altitudeM)
            }
        }
    }

    /**
     * GPS distance for the trip check. Only while live packets are arriving and
     * the car is moving, so it covers the same stretches as [chkLiveKm]: GPS
     * wander while parked isn't distance, and a stretch with no packets has no
     * speed distance to compare against.
     */
    private fun onGpsDistance(s: GpsSample) {
        if (s.accuracyM < 0f || s.accuracyM > 30f) return
        val prev = lastFix
        lastFix = s
        if (prev == null || tripStartMs == 0L) return
        val live = System.currentTimeMillis() - lastUpdateMs < 3_000L
        if (!live || lastLiveSpeedKmh < 3f) return
        val dtS = (s.timeMs - prev.timeMs) / 1000f
        if (dtS <= 0f || dtS > 30f) return
        val m = FloatArray(1)
        Location.distanceBetween(prev.lat, prev.lon, s.lat, s.lon, m)
        if (m[0] / dtS > 70f) return                // over 250 km/h: a GPS jump
        chkGpsKm += m[0] / 1000f
    }

    private fun publishCheck() {
        val pairs = minOf(chkSagCount, chkSag.size)
        val sag = if (pairs >= 20) chkSag.copyOf(pairs).also { it.sort() }[pairs / 2] else Float.NaN
        LiveTripData.check.value = TripCheck(
            liveKm = chkLiveKm, gpsKm = chkGpsKm, replayedKm = chkReplayedKm,
            movingReadings = chkMoving, noVoltage = chkNoVolts, zeroCurrent = chkZeroAmps,
            zeroBlips = chkBlips, sagOhm = sag, sagPairs = chkSagCount,
            drawnKwh = chkDrawnWh / 1000f, regenKwh = regenWhTotal / 1000f,
            netKwh = energyManager.sessionWh / 1000f,
            socStart = chkSocStart, socNow = chkSocNow, socSpanKwh = chkSocSpanWh / 1000f)
    }

    /**
     * Two checks on the current, from consecutive live readings.
     *
     * Lone zeros: a 0 A reading between two drawing more than 5 A, while
     * moving. Power can't drop to nothing and come straight back in half a
     * second; a coast eases down through smaller values first. So these are
     * readings that arrived without a current.
     *
     * Pack sag: across a quick load change (≥ 25 A within 1.5 s) the pack
     * voltage drops by current × pack resistance, so −ΔV/ΔI measures that
     * resistance from the very readings the energy is built on. If the current
     * reads low by some factor, this comes out high by the same factor. Zero
     * readings are left out, as they may be missing currents.
     */
    private fun checkCurrent(d: CarData, now: Long) {
        val a = d.battCurrent
        val v = d.battVoltage
        if (d.speedKmh > 3f && chkPrevA == 0f && chkPrevPrevA > 5f && a > 5f) chkBlips++
        val dA = a - chkPrevA
        if (v > 0f && chkPrevV > 0f && a != 0f && chkPrevA != 0f && !chkPrevA.isNaN() &&
            now - chkPrevMs <= 1_500L && kotlin.math.abs(dA) >= 25f) {
            val r = -(v - chkPrevV) / dA
            if (r > 0f && r < 1f) chkSag[chkSagCount++ % chkSag.size] = r
        }
        chkPrevPrevA = chkPrevA
        chkPrevA = a
        chkPrevV = v
        chkPrevMs = now
    }

    private fun onElevationSample(rawAltM: Float) {
        // Moving-average smoothing to tame GPS vertical noise (±10–20 m typical).
        elevationSmoothing.addLast(rawAltM)
        while (elevationSmoothing.size > ELEV_SMOOTH_N) elevationSmoothing.removeFirst()
        val alt = elevationSmoothing.average().toFloat()

        _currentElevationM.value = alt
        if (_minElevationM.value.isNaN() || alt < _minElevationM.value) _minElevationM.value = alt
        if (_maxElevationM.value.isNaN() || alt > _maxElevationM.value) _maxElevationM.value = alt

        // Add a profile point every elevStepKm of travel; thin adaptively.
        val dist = _tripDistanceKm.value
        if (lastProfileDistKm < 0f || dist - lastProfileDistKm >= elevStepKm) {
            lastProfileDistKm = dist
            // Net regen over the stretch since the previous point (≥ 1 Wh so a
            // coast with a trickle of regen doesn't count).
            val regen = segRegenWh > segDrawWh && segRegenWh >= 1f
            segDrawWh = 0f
            segRegenWh = 0f
            elevationPoints.add(ElevationPoint(dist, alt, regen))
            if (elevationPoints.size > ELEV_PROFILE_BUDGET) {
                // Halve resolution: keep every 2nd point, double the step. A kept
                // point's stretch now also covers the dropped one before it, so
                // it is regen if either stretch was.
                val thinned = elevationPoints.mapIndexedNotNull { i, p ->
                    when {
                        i % 2 != 0 -> null
                        i > 0 && elevationPoints[i - 1].regen && !p.regen -> p.copy(regen = true)
                        else -> p
                    }
                }
                elevationPoints.clear()
                elevationPoints.addAll(thinned)
                elevStepKm *= 2f
            }
            _elevationProfile.value = elevationPoints.toList()
        }
    }

    // ── Voltage-based SOC ────────────────────────────────────────────────────
    // ONE table, written directly in PACK VOLTS: 270 V = 0 %, 340 V = 100 %.
    //
    // The curve is not a straight line, because an NMC pack isn't. Its
    // open-circuit voltage has steep knees at both ends and a long flat
    // plateau through the middle, so a linear map overstates remaining charge
    // by 15-20 points across the middle of the range. What changed is that
    // this is now a single lookup in pack volts, instead of a per-cell table
    // normalised at runtime through two extra layers.
    //
    // Derived from a standard NMC open-circuit curve at 84 cells in series:
    //   340 V = 4.05 V/cell (100 %)   270 V = 3.21 V/cell (0 %)
    // Note the plateau: 300 V to 310 V is 22 points of SOC, while the same
    // 10 V swing near the ends is worth about half that.
    //
    // Must stay consistent with can_sniffer.ino, which drives the physical
    // fuel gauge from the same table.
    private val SOC_V = floatArrayOf(
        270f, 275f, 280f, 285f, 290f, 295f, 300f, 305f,
        310f, 315f, 320f, 325f, 330f, 335f, 340f)
    private val SOC_P = floatArrayOf(
          0f,   1f,   4f,   9f,  15f,  23f,  32f,  44f,
         54f,  63f,  72f,  80f,  87f,  94f, 100f)

    // Terminal voltage sags under load, which reads as lost charge, so SOC is
    // taken from estimated open-circuit voltage (v + i * PACK_R) rather than
    // the raw reading. Without it the dial dips on every hill and recovers at
    // the top. 0.12 Ω is typical for an 84S pack: a 200 A pull sags it ~24 V.
    private val PACK_R = PACK_R_OHM

    /**
     * SOC from pack open-circuit volts. vFull/vEmpty come from Settings; if
     * they differ from the table's 270-340 V the reading is mapped
     * proportionally onto it, so changing the charge limit still gives a
     * true 0 % and 100 % at the ends.
     */
    private fun socFromOcv(ocvPack: Float, vFull: Float, vEmpty: Float): Float {
        if (vFull - vEmpty < 1f) return 0f              // nonsense config
        val lo = SOC_V.first()
        val hi = SOC_V.last()
        val v = lo + (ocvPack - vEmpty) / (vFull - vEmpty) * (hi - lo)
        if (v <= lo) return 0f
        if (v >= hi) return 100f
        for (i in 1 until SOC_V.size) {
            if (v <= SOC_V[i]) {
                val t = (v - SOC_V[i - 1]) / (SOC_V[i] - SOC_V[i - 1])
                return SOC_P[i - 1] + t * (SOC_P[i] - SOC_P[i - 1])
            }
        }
        return 100f
    }

    private var smoothedOcv    = 0f
    private var lastVoltSocPct = -1
    private val VOLT_EMA_ALPHA = 0.03f       // slow tracking, once settled
    private val VOLT_EMA_FAST  = 0.20f       // fast-acquire, first few seconds
    /** Outlier guard only — IR compensation handles normal sag. */
    private val SOC_GATE_AMPS_MAX = 250f
    // Fast-acquire seeding: seed the estimate from the median of the first few
    // plausible samples (so one low startup reading can't anchor it at "empty"),
    // and track with a larger alpha for a few seconds so the dial snaps to the
    // right SOC quickly instead of crawling up for a minute.
    private val SEED_SAMPLES    = 5
    private val SEED_MARGIN_V   = 15f        // volts of slack beyond vEmpty/vFull
    private val FAST_ACQUIRE_MS = 8_000L
    private val seedSamples     = ArrayList<Float>(SEED_SAMPLES)
    private var acquiredAtMs    = 0L
    // The app now stays running when Back is pressed and through head-unit
    // sleep, so the estimate can be hours old when data resumes — and the pack
    // may have been charged meanwhile. After a gap this long, acquire afresh
    // instead of crawling there at the slow alpha.
    private val REACQUIRE_GAP_MS = 120_000L
    private var lastPlausibleLiveMs = 0L

    private fun voltageSoc(data: CarData, live: Boolean): Int? {
        val ctx: Application = getApplication()
        if (!SettingsActivity.voltageSocEnabled(ctx)) {
            if (live) { smoothedOcv = 0f; lastVoltSocPct = -1; seedSamples.clear() }
            return null
        }
        val v = data.battVoltage
        if (v <= 0f)   // no live voltage (e.g. inverter poll stale) — hold last estimate
            return if (lastVoltSocPct >= 0) lastVoltSocPct else null

        val i      = data.battCurrent
        val ocv    = v + i * PACK_R                 // undo the sag
        val vFull  = SettingsActivity.voltFull(ctx)
        val vEmpty = SettingsActivity.voltEmpty(ctx)

        // Replayed history feeds the accumulators only. Map it statelessly so the
        // buffered boot samples (batt=0, then a possibly-low first reading) can
        // never seed or drag the live smoother — the cause of the dial waking up
        // "empty" and crawling for a minute.
        if (!live) return socFromOcv(ocv, vFull, vEmpty).toInt().coerceIn(0, 100)

        // Reject implausible samples (startup transients, huge current spikes) so
        // the estimate can't anchor to a bad reading. From firmware v7.3 also
        // wait for the inverter to leave Off: before the start signal this car's
        // DC link is a capacitor bank bleeding down, which looked like a nearly
        // flat pack. Same gate as the fuel needle, so the two agree at key-on.
        val plausible = data.dcLinkUp != false &&
                        i <= SOC_GATE_AMPS_MAX &&
                        ocv >= vEmpty - SEED_MARGIN_V && ocv <= vFull + SEED_MARGIN_V

        if (plausible) {
            val nowMs = System.currentTimeMillis()
            if (smoothedOcv > 0f && lastPlausibleLiveMs > 0L &&
                nowMs - lastPlausibleLiveMs > REACQUIRE_GAP_MS) {
                smoothedOcv = 0f
                seedSamples.clear()
            }
            lastPlausibleLiveMs = nowMs
        }

        if (smoothedOcv <= 0f) {
            if (!plausible) return if (lastVoltSocPct >= 0) lastVoltSocPct else null
            // Acquiring: show the raw reading meanwhile (snaps to the right SOC
            // immediately) and seed smoothedOcv from the median of a few samples.
            seedSamples.add(ocv)
            if (seedSamples.size < SEED_SAMPLES) {
                lastVoltSocPct = socFromOcv(ocv, vFull, vEmpty).toInt().coerceIn(0, 100)
                return lastVoltSocPct
            }
            seedSamples.sort()
            smoothedOcv  = seedSamples[seedSamples.size / 2]   // median
            seedSamples.clear()
            acquiredAtMs = System.currentTimeMillis()
        } else if (plausible) {
            // Faster tracking for the first few seconds after seeding, then settle.
            val alpha = if (System.currentTimeMillis() - acquiredAtMs < FAST_ACQUIRE_MS)
                VOLT_EMA_FAST else VOLT_EMA_ALPHA
            smoothedOcv += alpha * (ocv - smoothedOcv)
        }

        lastVoltSocPct = socFromOcv(smoothedOcv, vFull, vEmpty).toInt().coerceIn(0, 100)
        return lastVoltSocPct
    }

    /** Called by the activity once ACCESS_FINE_LOCATION has been granted. */
    fun startLocationTracking() = locationService.start()

    /** Copy a ViewModel flow into the process-wide [LiveTripData]. */
    private fun <T> mirror(src: StateFlow<T>, dst: MutableStateFlow<T>) {
        viewModelScope.launch { src.collect { dst.value = it } }
    }

    /** Saves the trip and resets for the next one. True if it is in the database. */
    private suspend fun saveTripToDb(): Boolean {
        if (tripSaved) return true
        tripSaved = true
        val trip = buildTripSession()
        try {
            // PersistScope, awaited: running the insert on a scope that outlives
            // the ViewModel means a save triggered just as the screen closes
            // still completes, while the await keeps the error handling below
            // meaningful.
            PersistScope.async { dao.insert(trip) }.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e            // not a failed save — never swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG_VM, "trip save failed, will retry", e)
            tripSaved = false  // allow retry on next timer tick
            return false
        }
        clearBackup()
        resetTrip()
        return true
    }

    private fun buildTripSession() = TripSession(
        startTime            = tripStartMs,
        endTime              = System.currentTimeMillis(),
        distanceKm           = _tripDistanceKm.value,
        durationMin          = _tripTimeMin.value,
        startSOC             = startSOC.coerceAtLeast(0),
        endSOC               = lastSOC,
        energyKwh            = energyManager.sessionWh / 1000f,
        avgEfficiencyWhPerKm = _efficiencyWhPerKm.value,
        avgSpeedKmh          = _avgSpeedKmh.value,
        maxSpeedKmh          = maxSpeedKmh
    )

    /**
     * Persists the live trip to the backup prefs so it survives a crash/kill.
     * Uses async [apply] during driving; [sync] = true forces a blocking [commit]
     * for teardown, where the write must complete before the process may die.
     */
    @Suppress("ApplySharedPref")
    /**
     * Snapshot the trip to backup prefs so a crash or process death doesn't
     * lose it — recovered on next launch by recoverBackupIfExists().
     *
     * [sync] is only for onCleared(), where the process may not survive long
     * enough for an async write. Everywhere else apply() is used, but note that
     * apply()'s queued write is force-flushed by the system at the next
     * onPause/onStop, so the values are gathered on the caller's thread and the
     * disk write is what gets deferred.
     */
    private fun backupTripState(sync: Boolean = false) {
        val editor = prefs.edit()
            .putBoolean("active",           true)
            .putLong(   "start_ms",         tripStartMs)
            .putFloat(  "dist_km",          _tripDistanceKm.value)
            .putInt(    "mins",             _tripTimeMin.value)
            .putInt(    "start_soc",        startSOC.coerceAtLeast(0))
            .putInt(    "last_soc",         lastSOC)
            .putFloat(  "kwh",              energyManager.sessionWh / 1000f)
            .putFloat(  "eff_wh_per_km",    _efficiencyWhPerKm.value)
            .putFloat(  "avg_speed",        _avgSpeedKmh.value)
            .putFloat(  "max_speed",        maxSpeedKmh)
        if (sync) editor.commit() else editor.apply()
    }

    private fun clearBackup() = prefs.edit().clear().apply()

    private suspend fun recoverBackupIfExists() {
        if (!prefs.getBoolean("active", false)) return
        val distKm = prefs.getFloat("dist_km", 0f)
        if (distKm < MIN_TRIP_DISTANCE_KM) { clearBackup(); return }
        val trip = TripSession(
            startTime            = prefs.getLong("start_ms", System.currentTimeMillis()),
            endTime              = System.currentTimeMillis(),
            distanceKm           = distKm,
            durationMin          = prefs.getInt("mins", 0),
            startSOC             = prefs.getInt("start_soc", 0),
            endSOC               = prefs.getInt("last_soc", 0),
            energyKwh            = prefs.getFloat("kwh", 0f),
            avgEfficiencyWhPerKm = prefs.getFloat("eff_wh_per_km", 0f),
            avgSpeedKmh          = prefs.getFloat("avg_speed", 0f),
            maxSpeedKmh          = prefs.getFloat("max_speed", 0f)
        )
        try {
            dao.insert(trip)   // already on Dispatchers.IO
            Log.i(TAG_VM, "recovered trip of %.1f km from backup".format(distKm))
            clearBackup()
        } catch (e: Exception) {
            // Backup prefs are deliberately left in place so the next launch
            // tries again — but say so, rather than losing a trip in silence.
            Log.e(TAG_VM, "trip recovery insert failed, backup kept for retry", e)
        }
    }

    // ── Drive mode ───────────────────────────────────────────────────────────
    /**
     * Mode the ESP32 reports as active. Authoritative: the ESP32 reads fweak
     * back off the CAN bus, so this is the inverter's real state rather than
     * whatever was last requested.
     *
     * Starts blank, not "NORMAL". Claiming a mode before the first packet
     * arrives was showing NORMAL on a car that was actually in ECO.
     */
    val driveMode: StateFlow<String> = carData
        .map { it.driveMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Refusal reasons from the ESP32, e.g. "vehicle moving — stop first". */
    val modeRefusals = btService.modeRefusals

    /** Sync progress, so a long replay reads as busy rather than frozen. */
    val syncState = btService.syncState
    /** (received, announced) during a history replay. */
    val syncProgress = btService.syncProgress

    enum class ResetResult { SAVED, TOO_SHORT, SAVE_FAILED }

    /** Shortest trip that is saved to history (for the reset confirmation). */
    val minTripKm: Float get() = MIN_TRIP_DISTANCE_KM

    /**
     * RESET TRIP: save the trip to history first, then start a new one. (It
     * used to discard the trip outright, on a single tap.) Too short to be a
     * trip (< MIN_TRIP_DISTANCE_KM) is just reset. If the save fails nothing is
     * reset, so nothing is lost.
     */
    fun saveAndResetTrip(onDone: (ResetResult) -> Unit) {
        viewModelScope.launch {
            val longEnough = _tripDistanceKm.value >= MIN_TRIP_DISTANCE_KM
            when {
                longEnough && !tripSaved -> {
                    val ok = saveTripToDb()          // resets on success
                    onDone(if (ok) ResetResult.SAVED else ResetResult.SAVE_FAILED)
                }
                // The stop timer's save is already writing this trip (a
                // few-millisecond window) — it is being saved, so say so.
                longEnough -> { resetTrip(); onDone(ResetResult.SAVED) }
                else       -> { resetTrip(); onDone(ResetResult.TOO_SHORT) }
            }
        }
    }

    /** Why the last connect attempt failed, for the status line. */
    fun lastLinkError(): String? = btService.lastError

    // sendCommand is suspend now (it does blocking IO): launched rather than
    // called inline so a congested link cannot block the click handler.
    fun requestDriveMode(mode: String) {
        viewModelScope.launch { btService.sendCommand(mode) }
    }

    fun connect(device: BluetoothDevice) {
        viewModelScope.launch { btService.connect(device) }
    }

    fun disconnect() = btService.disconnect()

    fun resetTrip() {
        tripStartMs       = 0L
        lastUpdateMs      = 0L
        lastBufferedTs    = 0L
        lastMovementMs    = 0L
        lastBackupMs      = 0L
        tripElapsedMs     = 0L
        tripSaved         = false
        startSOC          = -1
        lastSOC           = 0
        maxSpeedKmh       = 0f
        totalSpeedSamples = 0
        totalSpeedSum     = 0f
        latestVoltageV    = 0f
        latestBattTempC   = 0f
        latestMotorTempC  = 0f
        peakCurrentA      = 0f
        LiveVehicleData.reset()
        _tripTimeMin.value       = 0
        _avgSpeedKmh.value       = 0f
        _tripDistanceKm.value    = 0f
        _efficiencyWhPerKm.value = 0f
        energyManager.resetSession()
        bucketStartMs = 0L
        bucketWh = 0f
        bucketRegenWh = 0f
        regenWhTotal = 0f
        segDrawWh = 0f
        segRegenWh = 0f
        _rollingEffWhPerKm.value = 0f
        LiveTripData.regenKwh.value = 0f
        LiveTripData.rangeBasisWhPerKm.value = 0f
        LiveTripData.rangeBasisIsLifetime.value = false
        lastSessionWh = 0f
        chkLiveKm = 0f; chkReplayedKm = 0f; chkGpsKm = 0f
        chkMoving = 0; chkNoVolts = 0; chkZeroAmps = 0
        chkDrawnWh = 0f; chkSocStart = -1; chkSocNow = -1; chkSocSpanWh = 0f
        chkPrevA = Float.NaN; chkPrevPrevA = Float.NaN; chkPrevV = 0f; chkPrevMs = 0L
        chkBlips = 0; chkSagCount = 0
        lastFix = null
        LiveTripData.check.value = null
        sampleClockMs = 0L
        baseBuckets.clear()
        _energyBuckets.value = emptyList()
        _bucketMinutes.value = 1
        _projectedRangeKm.value = 0f
        windowSamples.clear()
        winWhSum   = 0f
        winDistSum = 0f
        // Elevation
        elevationSmoothing.clear()
        elevationPoints.clear()
        lastProfileDistKm = -1f
        elevStepKm = 0.02f
        _currentElevationM.value = Float.NaN
        _minElevationM.value     = Float.NaN
        _maxElevationM.value     = Float.NaN
        _elevationProfile.value  = emptyList()
        // Abandon the crash-recovery snapshot too — a manual reset means this trip
        // should not be recovered into history on the next launch.
        clearBackup()
    }

    override fun onCleared() {
        // Don't block teardown on a DB write. Flush the current trip to the backup
        // prefs synchronously (one fast write); the next launch recovers it into
        // the trip database via recoverBackupIfExists().
        if (_tripDistanceKm.value >= MIN_TRIP_DISTANCE_KM && !tripSaved) {
            backupTripState(sync = true)
        }
        VehicleLink.release(btService)
        btService.disconnect()
        locationService.stop()
        super.onCleared()
    }
}

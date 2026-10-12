package com.landroverdash.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dashboard — 3×2 stat grid (Range · Efficiency · Distance / Avg speed ·
 * Session · Elevation) + battery ring. Tap Efficiency → full-screen energy
 * chart page; tap Elevation → elevation profile; ⓘ → trip details.
 */
class DashboardActivity : AppCompatActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var batteryCircle:  BatteryCircleView
    private lateinit var tvStatRangeVal: TextView
    private lateinit var tvStatEffVal:   TextView
    private lateinit var tvStatDistVal:  TextView
    private lateinit var tvStatSpeedVal: TextView
    private lateinit var tvSessionTime:  TextView
    private lateinit var tvElevCur:      TextView
    private lateinit var btnModeEco:     TextView
    private lateinit var btnModeNormal:  TextView
    private lateinit var btnModeSport:   TextView

    // Batch-themed view groups
    private lateinit var panels:  List<LinearLayout>
    private lateinit var labels:  List<TextView>

    // ── State ──────────────────────────────────────────────────────────────
    private var address: String? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startLocationTracking() }

    // ── Colors (dark-only) ──────────────────────────────────────────────────
    private val rootBg    = Color.parseColor("#000000")
    private val labelText = Color.parseColor("#DDEEDF")
    private val valueText = Color.WHITE

    // Earthy accent palette: Range = teal, Efficiency = amber,
    // Distance = steel blue, Session = olive, Elevation = clay;
    // Avg speed stays the white anchor.
    private val accentTeal  = Color.parseColor("#4DD0C4")
    private val accentAmber = Color.parseColor("#FFC14D")
    private val accentBlue  = Color.parseColor("#7EB6FF")
    private val accentOlive = Color.parseColor("#D8D27E")
    private val accentClay  = Color.parseColor("#E8926B")

    /** Text on the selected (pale) drive-mode chip. */
    private val chipOnText  = Color.parseColor("#08110C")

    // ── Drive-mode double-tap confirmation ──────────────────────────────────
    /** Mode awaiting a confirming second tap, or null. */
    private var armedMode: String? = null
    /**
     * Mode the ESP32 reports as active. Blank until the first packet lands, so
     * no chip is highlighted rather than falsely highlighting NORMAL.
     */
    private var activeMode = ""
    /** Latest link state, written by the collector and read by the supervisor. */
    @Volatile private var connected = false

    /** Status-line state. */
    private var lastLinkState: BluetoothService.ConnectionState? = null
    private var failedAttempts = 0
    private var syncing = false
    /** (received, announced) records of the current history replay. */
    private var syncCounts = 0 to 0
    /** Connected, but nothing at all from the inverter yet. */
    private var inverterSilent = true
    /** Inverter answering but still Off, and no battery % known yet. */
    private var socWaitsForRun = false
    private var tvLinkStatus: TextView? = null

    /**
     * BLUETOOTH_CONNECT is required from API 31 for both
     * createRfcommSocketToServiceRecord() and connect(). Below 31 the install-
     * time permissions cover it.
     */
    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the current low-12 V condition has been shown to the user. */
    private var auxShown = false

    /** Failed connects (~2–4 min with retry and connect time) before offering
     *  the picker. Was 60 (~10 min), long enough to look like a hung app. */
    private val UNREACHABLE_ATTEMPTS = 24

    /** Failures before the status line names a cause instead of "Connecting…". */
    private val EXPLAIN_AFTER_ATTEMPTS = 3

    private val armHandler = Handler(Looper.getMainLooper())
    private val disarm = Runnable { armedMode = null; renderModeChips() }
    private val ARM_TIMEOUT_MS = 3000L

    // ══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // Keep the dashboard awake while driving
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        buildThemeLists()
        applyTheme()
        renderModeChips()   // paint the chips before the first packet arrives

        // BT connect — address is required, else bail back to picker
        address = intent.getStringExtra("device_address")

        // MainActivity checks BLUETOOTH_CONNECT and then finishes itself, which
        // makes THIS the root of the task. Any relaunch that skips the picker —
        // process death and task restore, permission revoked while backgrounded,
        // Android's unused-app hibernation — lands here with no permission at
        // all. connect() then threw SecurityException every 5 s into a silent
        // catch, and the dashboard sat on "--" looking like a parked car.
        if (!hasBtPermission()) {
            Toast.makeText(this,
                "Bluetooth permission needed — reopening setup",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        val device = address?.let { remoteDevice(it) }
        if (device != null) {
            viewModel.connect(device)
        } else {
            finish()
            return
        }

        // Back leaves the dashboard running behind the home screen instead of
        // closing it. Closing it dropped the car link and the trip in progress,
        // and the next open had to reconnect and re-sync from scratch.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        // Clicks
        findViewById<TextView>(R.id.btnSettings).apply {
            expandTouchArea(12)
            setOnClickListener {
                startActivity(Intent(this@DashboardActivity, SettingsActivity::class.java))
            }
        }
        findViewById<TextView>(R.id.btnResetTrip).apply {
            expandTouchArea(14)
            setOnClickListener {
                it.parent.requestDisallowInterceptTouchEvent(true)
                confirmResetTrip()
            }
        }
        // Tap the Session tile (ⓘ next to its label) → trip data + history
        findViewById<LinearLayout>(R.id.cellSession).setOnClickListener {
            // No snapshot extras — the details page observes LiveVehicleData
            // so its panel updates with every packet while it's open.
            startActivity(Intent(this, DetailsActivity::class.java))
        }
        // Tap Efficiency tile → full-screen trip energy chart
        findViewById<LinearLayout>(R.id.cellEfficiency).setOnClickListener {
            openEnergyChart()
        }
        // Tap Elevation tile → elevation profile for the current trip
        findViewById<LinearLayout>(R.id.rowElevation).setOnClickListener {
            openElevationProfile()
        }

        // Drive mode selector — requires a DOUBLE tap so a stray touch can't
        // retune the car. The ESP32 stays authoritative: we only REQUEST a
        // mode, and the chips update when it reports the change back to us.
        btnModeEco.setOnClickListener    { onModeTap("ECO") }
        btnModeNormal.setOnClickListener { onModeTap("NORMAL") }
        btnModeSport.setOnClickListener  { onModeTap("SPORT") }

        ensureLocationPermission()
        observeViewModel()
    }

    /**
     * RESET TRIP used to wipe the trip on one tap, unsaved. Now it asks first,
     * then saves the trip to history before starting a new one.
     */
    private fun confirmResetTrip() {
        val km = viewModel.tripDistanceKm.value
        val minKm = viewModel.minTripKm
        val msg = if (km >= minKm)
            "Saves this %.1f km trip to history, then starts a new one.".format(km)
        else
            "This trip is under %.1f km, so it won't be saved to history.".format(minKm)
        AlertDialog.Builder(this)
            .setTitle("Reset trip?")
            .setMessage(msg)
            .setPositiveButton("Reset") { _, _ ->
                val app = applicationContext
                viewModel.saveAndResetTrip { r ->
                    val text = when (r) {
                        DashboardViewModel.ResetResult.SAVED ->
                            "Trip saved to history — new trip started"
                        DashboardViewModel.ResetResult.TOO_SHORT ->
                            "Trip reset"
                        DashboardViewModel.ResetResult.SAVE_FAILED ->
                            "Couldn't save the trip, so nothing was reset. Try again."
                    }
                    Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Requests GPS permission if needed; starts tracking immediately if already granted. */
    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocationTracking()
        } else {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun openEnergyChart() {
        val buckets = viewModel.energyBuckets.value
        val intent = Intent(this, EnergyChartActivity::class.java)
        intent.putExtra(EnergyChartActivity.EXTRA_TS, LongArray(buckets.size) { buckets[it].timestampMs })
        intent.putExtra(EnergyChartActivity.EXTRA_WH, FloatArray(buckets.size) { buckets[it].wh })
        intent.putExtra(EnergyChartActivity.EXTRA_GRAN_MIN, viewModel.bucketMinutes.value)
        startActivity(intent)
    }

    private fun openElevationProfile() {
        val profile = viewModel.elevationProfile.value
        val intent = Intent(this, ElevationActivity::class.java)
        intent.putExtra(ElevationActivity.EXTRA_DIST, FloatArray(profile.size) { profile[it].distanceKm })
        intent.putExtra(ElevationActivity.EXTRA_ALT,  FloatArray(profile.size) { profile[it].altitudeM })
        intent.putExtra(ElevationActivity.EXTRA_MIN,  viewModel.minElevationM.value)
        intent.putExtra(ElevationActivity.EXTRA_MAX,  viewModel.maxElevationM.value)
        startActivity(intent)
    }

    // ══════════════════════════════════════════════════════════════════════
    private fun bindViews() {
        batteryCircle  = findViewById(R.id.batteryCircle)
        tvStatRangeVal = findViewById(R.id.tvStatRangeVal)
        tvStatEffVal   = findViewById(R.id.tvStatEffVal)
        tvStatDistVal  = findViewById(R.id.tvStatDistVal)
        tvStatSpeedVal = findViewById(R.id.tvStatSpeedVal)
        tvSessionTime  = findViewById(R.id.tvSessionTime)
        tvElevCur      = findViewById(R.id.tvElevCur)
        btnModeEco     = findViewById(R.id.btnModeEco)
        btnModeNormal  = findViewById(R.id.btnModeNormal)
        btnModeSport   = findViewById(R.id.btnModeSport)
        tvLinkStatus   = findViewById(R.id.tvLinkStatus)
        // Only acts once the link has been failing long enough to be hopeless;
        // before that a stray tap on a transient "Connecting…" does nothing.
        tvLinkStatus?.setOnClickListener {
            if (failedAttempts >= UNREACHABLE_ATTEMPTS) openDevicePicker()
        }
    }

    private fun buildThemeLists() {
        // Single pane — one tile background around the whole dashboard
        panels = listOf(
            findViewById(R.id.panelMain)
        )
        labels = listOf(
            findViewById(R.id.tvStatRangeLbl),
            findViewById(R.id.tvStatEffLbl),
            findViewById(R.id.tvStatDistLbl),
            findViewById(R.id.tvStatSpeedLbl),
            findViewById(R.id.tvSessionLbl),
            findViewById(R.id.tvSessionHint),
            findViewById(R.id.tvElevTitle),
            findViewById(R.id.tvElevHint),
            findViewById(R.id.tvEffHint)
        )
        // (stat values get individual accent colours in applyTheme)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ViewModel observers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * "62 km" with the unit shrunk so the number dominates. [unitScale] can be
     * tightened for wide units (km/kWh) so they don't force the whole value to
     * auto-shrink.
     */
    private fun statText(value: String, unit: String, unitScale: Float = 0.38f): CharSequence =
        SpannableStringBuilder("$value $unit").apply {
            setSpan(RelativeSizeSpan(unitScale), value.length, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun observeViewModel() {
        // UI collectors: scoped to STARTED so they stop updating views while the
        // activity is backgrounded (state is replayed on return — StateFlows).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.carData.collect { d ->
                        batteryCircle.soc = d.battSOC
                        // No reply from the inverter at all, vs. answering but
                        // not running yet (so the ring reads "--" on purpose).
                        val silent  = d.errCode < 0 && d.battVoltage <= 0f
                        val waitRun = !silent && d.battSOC < 0 && d.dcLinkUp == false
                        if (silent != inverterSilent || waitRun != socWaitsForRun) {
                            inverterSilent = silent
                            socWaitsForRun = waitRun
                            renderLinkStatus(null)
                        }
                    }
                }
                // remainingKwh is no longer drawn in the ring, but the flow is
                // still used for the range projection in the ViewModel.
                launch {
                    // Displayed as km/kWh (= 1000 / Wh/km). Wh/km stays the
                    // internal unit because the range maths needs it.
                    viewModel.efficiencyWhPerKm.collect { eff ->
                        // km/kWh is the widest unit here — a tighter suffix lets
                        // the number itself stay large.
                        tvStatEffVal.text = statText(
                            if (eff > 0f) "%.1f".format(1000f / eff) else "--",
                            "km/kWh", unitScale = 0.30f)
                    }
                }
                launch {
                    viewModel.tripTimeMin.collect { mins ->
                        tvSessionTime.text = sessionText(mins)
                    }
                }
                launch {
                    viewModel.avgSpeedKmh.collect { avg ->
                        tvStatSpeedVal.text = statText(if (avg > 0f) "%.0f".format(avg) else "--", "km/h")
                    }
                }
                launch {
                    viewModel.tripDistanceKm.collect { dist ->
                        tvStatDistVal.text = statText("%.1f".format(dist), "km")
                    }
                }
                launch {
                    viewModel.projectedRangeKm.collect { range ->
                        tvStatRangeVal.text = statText(if (range > 0f) "%.0f".format(range) else "--", "km")
                    }
                }
                launch {
                    viewModel.currentElevationM.collect { m ->
                        tvElevCur.text = statText(if (m.isNaN()) "--" else "%.0f".format(m), "m")
                    }
                }
                launch {
                    viewModel.driveMode.collect { mode -> showDriveMode(mode) }
                }
                launch {
                    // e.g. "vehicle moving — stop first". The flow replays its
                    // latest refusal to every new collector, i.e. on every return
                    // from Settings — compare by identity so only a NEW refusal
                    // (a new String from a new reply) is shown.
                    viewModel.modeRefusals.collect { reason ->
                        if (reason === viewModel.lastRefusalShown) return@collect
                        viewModel.lastRefusalShown = reason
                        Toast.makeText(this@DashboardActivity, reason, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    // 12 V warning. The dashboard grid is full, so this is a
                    // toast rather than a tile — the reading itself lives on the
                    // details page.
                    //
                    // The latch is in LiveVehicleData, not here, so it tracks the
                    // rail continuously even while this screen is stopped. This
                    // collector only decides whether the CURRENT latch state has
                    // already been announced to the user; auxShown is per-visit,
                    // so a dip that occurred behind Settings is reported as soon
                    // as the dashboard is visible again rather than being lost.
                    LiveVehicleData.auxLow.collect { low ->
                        if (low && !auxShown) {
                            auxShown = true
                            val v = LiveVehicleData.auxVoltageV.value
                            Toast.makeText(this@DashboardActivity,
                                "12V LOW: %.1f V — DC-DC not charging".format(v),
                                Toast.LENGTH_LONG).show()
                        } else if (!low) {
                            auxShown = false
                        }
                    }
                }
            }
        }

        // Connection state: recorded for the supervisor, and now SHOWN.
        //
        // Previously nothing rendered it, so "ESP32 off", "out of range", "wrong
        // MAC", "permission denied" and "car parked" all produced an identical
        // screen of dashes with no way to tell them apart.
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                connected = state == BluetoothService.ConnectionState.CONNECTED
                if (connected) {
                    failedAttempts = 0
                    address?.let {
                        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                            .putString(MainActivity.PREF_LAST_DEVICE, it).apply()
                    }
                } else if (state == BluetoothService.ConnectionState.ERROR) {
                    failedAttempts++
                }
                renderLinkStatus(state)
            }
        }

        // Reconnect supervisor. Deliberately a POLLING loop rather than a
        // reaction to DISCONNECTED/ERROR events.
        //
        // The old version retried from inside the state collector, with the
        // delay in the collector itself. While it slept, connectionState (a
        // SharedFlow with a one-item buffer) had nowhere to put new values, so
        // disconnect()'s tryEmit silently returned false and the DISCONNECTED
        // that should have triggered the next retry was dropped. The loop then
        // sat idle forever and only a restart brought the app back.
        //
        // Polling cannot get stuck that way: it does not care whether any event
        // arrived. connect() is a no-op when a socket is already live, so
        // calling it on every tick is harmless.
        //
        // Not lifecycle-gated: it must keep retrying while a child screen
        // (Settings/Details/Chart) covers the dashboard.
        // Quick retries first: at key-on the head unit's Bluetooth and the
        // ESP32 come up within seconds of each other, so the first attempts
        // usually fail only because the other side isn't ready yet. Waiting a
        // full 5 s after each of those added noticeable delay to every start.
        lifecycleScope.launch {
            while (isActive) {
                if (!connected) {
                    address?.let { remoteDevice(it) }?.let { viewModel.connect(it) }
                }
                delay(if (failedAttempts < 3) 1_500L else 5_000L)
            }
        }

        // History replay status, with a count so a long one reads as progress.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.syncState.collect { s ->
                        syncing = s == BluetoothService.SyncState.SYNCING
                        renderLinkStatus(null)
                    }
                }
                launch {
                    viewModel.syncProgress.collect { p ->
                        syncCounts = p
                        if (syncing) renderLinkStatus(null)
                    }
                }
            }
        }
    }

    /** Turns a raw socket exception message into something actionable. */
    private fun friendlyLinkError(raw: String?): String {
        val e = raw?.lowercase() ?: return "is the car on?"
        return when {
            "permission" in e ->
                "Bluetooth permission denied — reopen the app"
            "refused" in e || "busy" in e ->
                "the car interface is busy — is another phone or tablet connected to it?"
            "discovery" in e || "read failed" in e || "timeout" in e ||
            "timed out" in e || "host is down" in e ->
                "the car interface isn't answering — is the ignition on?"
            else -> "is the car on?"
        }
    }

    /**
     * One line above the grid describing the link. Deliberately terse and only
     * present when there is something to say — connected and idle shows nothing.
     */
    private fun renderLinkStatus(state: BluetoothService.ConnectionState?) {
        if (state != null) lastLinkState = state
        val v = tvLinkStatus ?: return
        val btOn = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull() != false
        val text = when {
            syncing -> syncCounts.let { (got, total) ->
                if (total > 0) "Syncing trip history… $got / $total" else "Syncing trip history…"
            }
            lastLinkState == BluetoothService.ConnectionState.CONNECTED -> when {
                // Linked to the ESP32 but the inverter hasn't answered yet.
                inverterSilent -> "Connected — waiting for the inverter"
                // Before run mode the DC link isn't the pack, so the battery %
                // is deliberately not guessed from it.
                socWaitsForRun -> "Battery % shows once the car is in run mode"
                else -> ""
            }
            !btOn -> "Bluetooth is off — waiting for it to come back"
            lastLinkState == BluetoothService.ConnectionState.CONNECTING &&
                failedAttempts < EXPLAIN_AFTER_ATTEMPTS -> "Connecting…"
            failedAttempts >= UNREACHABLE_ATTEMPTS ->
                "Not connected — tap to choose device"
            // After a few failures stop implying it is about to work and name
            // the likely cause.
            failedAttempts >= EXPLAIN_AFTER_ATTEMPTS ->
                "Not connected — " + friendlyLinkError(viewModel.lastLinkError())
            else -> "Connecting…"
        }
        v.text = text
        v.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Escape hatch for a device that can never be reached.
     *
     * Auto-connect resolves the saved MAC directly and never checks it is still
     * bonded, which is correct at startup — bondedDevices is empty for a second
     * while the stack comes up. But if the ESP32 is unpaired, re-flashed with a
     * new MAC, or the bond is dropped, the app would auto-launch here on every
     * start and retry forever with the picker unreachable. After roughly five
     * minutes of solid failure the status line becomes a way back to it.
     */
    private fun openDevicePicker() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .remove(MainActivity.PREF_LAST_DEVICE).apply()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        armHandler.removeCallbacks(disarm)
        super.onDestroy()
    }

    /**
     * First tap arms a mode (amber, "ECO ?"), a second tap within
     * ARM_TIMEOUT_MS commits it. Tapping the already-active mode, a different
     * chip, or simply waiting cancels — so no single touch can change the tune.
     */
    private fun onModeTap(mode: String) {
        if (mode.equals(activeMode, ignoreCase = true)) return   // already active
        if (!connected) {
            // It used to arm, accept the second tap and then silently drop
            // the command.
            armedMode = null
            armHandler.removeCallbacks(disarm)
            renderModeChips()
            Toast.makeText(this, "Not connected to the car — mode not changed",
                Toast.LENGTH_SHORT).show()
            return
        }
        armHandler.removeCallbacks(disarm)
        if (armedMode == mode) {                 // confirming tap
            armedMode = null
            viewModel.requestDriveMode(mode)
        } else {                                 // first tap — arm and wait
            armedMode = mode
            armHandler.postDelayed(disarm, ARM_TIMEOUT_MS)
        }
        renderModeChips()
    }

    /** Called when the ESP32 reports which mode is actually active. */
    private fun showDriveMode(mode: String) {
        activeMode = mode
        armedMode = null                         // confirmed — clear any arming
        armHandler.removeCallbacks(disarm)
        renderModeChips()
    }

    private fun renderModeChips() {
        val chips = listOf(
            "ECO"    to btnModeEco,
            "NORMAL" to btnModeNormal,
            "SPORT"  to btnModeSport
        )
        for ((name, chip) in chips) {
            when {
                name == armedMode -> {           // awaiting confirmation
                    chip.setBackgroundResource(R.drawable.mode_chip_armed)
                    chip.setTextColor(accentAmber)
                    chip.text = "$name ?"
                }
                name.equals(activeMode, ignoreCase = true) -> {
                    // Active chip inverts: pale fill + near-black text, so it
                    // reads instantly rather than relying on colour alone.
                    chip.setBackgroundResource(R.drawable.mode_chip_selected)
                    chip.setTextColor(chipOnText)
                    chip.text = name
                }
                else -> {
                    chip.setBackgroundResource(R.drawable.mode_chip_idle)
                    chip.setTextColor(labelText)
                    chip.text = name
                }
            }
        }
    }

    /** "1h 24m" with the h/m units shrunk to 38%, matching the other stats. */
    private fun sessionText(mins: Int): CharSequence {
        val s = "%dh %02dm".format(mins / 60, mins % 60)
        return SpannableStringBuilder(s).apply {
            val hPos = s.indexOf('h')
            setSpan(RelativeSizeSpan(0.38f), hPos, hPos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.38f), s.length - 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Resolves a MAC address to a BluetoothDevice, null-safe if BT is unavailable. */
    private fun remoteDevice(addr: String): android.bluetooth.BluetoothDevice? =
        try {
            getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(addr)
        } catch (_: IllegalArgumentException) {
            null   // malformed address
        }

    // ══════════════════════════════════════════════════════════════════════
    //  Theme
    // ══════════════════════════════════════════════════════════════════════

    private fun applyTheme() {
        findViewById<View>(R.id.rootLayout).setBackgroundColor(rootBg)
        panels.forEach  { it.setBackgroundResource(R.drawable.tile_background_dark) }
        labels.forEach  { it.setTextColor(labelText) }

        // Per-stat accents (same earthy palette, one hue per data point)
        tvStatRangeVal.setTextColor(accentTeal)
        tvStatEffVal.setTextColor(accentAmber)
        tvStatDistVal.setTextColor(accentBlue)
        tvStatSpeedVal.setTextColor(valueText)   // white anchor
        tvSessionTime.setTextColor(accentOlive)
        tvElevCur.setTextColor(accentClay)

        findViewById<TextView>(R.id.btnResetTrip).setTextColor(labelText)
        findViewById<TextView>(R.id.btnSettings).setTextColor(labelText)
    }
}

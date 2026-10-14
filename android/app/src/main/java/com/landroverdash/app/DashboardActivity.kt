package com.landroverdash.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Drive — the power band dashboard. Owns the Bluetooth link (through its
 * ViewModel) and stays alive underneath every other screen.
 *
 * Range and battery up top, live power across the middle, efficiency against
 * your average, and a strip with the trip and the car's health. Drive modes
 * moved to their own screen (Modes); the MODE pill shows the active one.
 */
class DashboardActivity : WattsonActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var linkStatusGroup: View
    private lateinit var linkDot:         View
    private lateinit var tvLinkStatus:    TextView
    private lateinit var acIndicator:     View
    private lateinit var modePill:        View
    private lateinit var alertPill:       LinearLayout
    private lateinit var ivAlertIcon:     ImageView
    private lateinit var ivAlertChevron:  ImageView
    private lateinit var tvAlertTitle:    TextView
    private lateinit var tvAlertDetail:   TextView
    private lateinit var tvModeName:      TextView

    private lateinit var tvRangeVal:   TextView
    private lateinit var socBar:       MeterBar
    private lateinit var tvSoc:        TextView
    private lateinit var tvRangeBasis: TextView

    private lateinit var tvEffVal:   TextView
    private lateinit var ivEffTrend: ImageView
    private lateinit var tvEffDelta: TextView
    private lateinit var effScale:   EfficiencyScaleView

    private lateinit var tvPowerState: TextView
    private lateinit var tvPowerVal:   TextView
    private lateinit var powerBand:    PowerBandView

    private lateinit var tvTripDist:  TextView
    private lateinit var tvTripTime:  TextView
    private lateinit var tvTripSpeed: TextView
    private lateinit var tvTripElev:  TextView

    private lateinit var ivHealthIcon:   ImageView
    private lateinit var tvHealthStatus: TextView
    private lateinit var tvMotorLabel:   TextView
    private lateinit var tvMotorTemp:    TextView
    private lateinit var motorBar:       MeterBar
    private lateinit var tvInvLabel:     TextView
    private lateinit var tvInvTemp:      TextView
    private lateinit var invBar:         MeterBar
    private lateinit var tv12vLabel:     TextView
    private lateinit var tv12v:          TextView
    private lateinit var tvFaultsLabel:  TextView
    private lateinit var tvFaults:       TextView

    // ── State ──────────────────────────────────────────────────────────────
    private var address: String? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startLocationTracking() }

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

    /** Latest health, so the alert pill and the link status agree on who shows. */
    private var health: Health.State? = null
    private var linkText = ""

    /**
     * BLUETOOTH_CONNECT is required from API 31 for both
     * createRfcommSocketToServiceRecord() and connect(). Below 31 the install-
     * time permissions cover it.
     */
    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Failed connects (~2–4 min with retry and connect time) before offering
     *  the picker. Was 60 (~10 min), long enough to look like a hung app. */
    private val UNREACHABLE_ATTEMPTS = 24

    /** Failures before the status line names a cause instead of "Connecting…". */
    private val EXPLAIN_AFTER_ATTEMPTS = 3

    // ══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // Keep the dashboard awake while driving
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        bindNavRail(Screen.DRIVE)

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
        findViewById<View>(R.id.btnResetTrip).setOnClickListener { confirmResetTrip() }
        modePill.setOnClickListener {
            navigateTo(Screen.MODES, Screen.DRIVE)
        }
        findViewById<View>(R.id.cardEfficiency).setOnClickListener {
            navigateTo(Screen.ENERGY, Screen.DRIVE)
        }
        findViewById<View>(R.id.statElevation).setOnClickListener {
            navigateTo(Screen.TERRAIN, Screen.DRIVE)
        }
        findViewById<View>(R.id.healthGroup).setOnClickListener {
            navigateTo(Screen.VEHICLE, Screen.DRIVE)
        }
        alertPill.setOnClickListener { navigateTo(Screen.VEHICLE, Screen.DRIVE) }
        // Only acts once the link has been failing long enough to be hopeless;
        // before that a stray tap on a transient "Connecting…" does nothing.
        linkStatusGroup.setOnClickListener {
            if (failedAttempts >= UNREACHABLE_ATTEMPTS) openDevicePicker()
        }

        ensureLocationPermission()
        observeViewModel()
    }

    /**
     * RESET TRIP asks first, then saves the trip to history before starting a
     * new one.
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

    // ══════════════════════════════════════════════════════════════════════
    private fun bindViews() {
        linkStatusGroup = findViewById(R.id.linkStatusGroup)
        linkDot         = findViewById(R.id.linkDot)
        tvLinkStatus    = findViewById(R.id.tvLinkStatus)
        acIndicator     = findViewById(R.id.acIndicator)
        modePill        = findViewById(R.id.modePill)
        alertPill       = findViewById(R.id.alertPill)
        ivAlertIcon     = findViewById(R.id.ivAlertIcon)
        ivAlertChevron  = findViewById(R.id.ivAlertChevron)
        tvAlertTitle    = findViewById(R.id.tvAlertTitle)
        tvAlertDetail   = findViewById(R.id.tvAlertDetail)
        tvModeName      = findViewById(R.id.tvModeName)

        tvRangeVal   = findViewById(R.id.tvRangeVal)
        socBar       = findViewById(R.id.socBar)
        tvSoc        = findViewById(R.id.tvSoc)
        tvRangeBasis = findViewById(R.id.tvRangeBasis)

        tvEffVal   = findViewById(R.id.tvEffVal)
        ivEffTrend = findViewById(R.id.ivEffTrend)
        tvEffDelta = findViewById(R.id.tvEffDelta)
        effScale   = findViewById(R.id.effScale)

        tvPowerState = findViewById(R.id.tvPowerState)
        tvPowerVal   = findViewById(R.id.tvPowerVal)
        powerBand    = findViewById(R.id.powerBand)

        tvTripDist  = findViewById(R.id.tvTripDist)
        tvTripTime  = findViewById(R.id.tvTripTime)
        tvTripSpeed = findViewById(R.id.tvTripSpeed)
        tvTripElev  = findViewById(R.id.tvTripElev)

        ivHealthIcon   = findViewById(R.id.ivHealthIcon)
        tvHealthStatus = findViewById(R.id.tvHealthStatus)
        tvMotorLabel   = findViewById(R.id.tvMotorLabel)
        tvMotorTemp    = findViewById(R.id.tvMotorTemp)
        motorBar       = findViewById(R.id.motorBar)
        tvInvLabel     = findViewById(R.id.tvInvLabel)
        tvInvTemp      = findViewById(R.id.tvInvTemp)
        invBar         = findViewById(R.id.invBar)
        tv12vLabel     = findViewById(R.id.tv12vLabel)
        tv12v          = findViewById(R.id.tv12v)
        tvFaultsLabel  = findViewById(R.id.tvFaultsLabel)
        tvFaults       = findViewById(R.id.tvFaults)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ViewModel observers
    // ══════════════════════════════════════════════════════════════════════

    private fun observeViewModel() {
        // UI collectors: scoped to STARTED so they stop updating views while the
        // activity is backgrounded (state is replayed on return — StateFlows).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.carData.collect { d ->
                        renderSoc(d.battSOC)
                        // No reply from the inverter at all, vs. answering but
                        // not running yet (so the battery % reads "--" on purpose).
                        val silent  = d.errCode < 0 && d.battVoltage <= 0f
                        val waitRun = !silent && d.battSOC < 0 && d.dcLinkUp == false
                        if (silent != inverterSilent || waitRun != socWaitsForRun) {
                            inverterSilent = silent
                            socWaitsForRun = waitRun
                            renderLinkStatus(null)
                        }
                    }
                }
                launch {
                    viewModel.projectedRangeKm.collect { range ->
                        tvRangeVal.text = Fmt.whole(range)
                        // Unknown reads as a quiet dash rather than a big white one.
                        tvRangeVal.setTextColor(color(if (range > 0f) R.color.text else R.color.text_muted))
                    }
                }
                launch {
                    LiveTripData.rangeBasisWhPerKm.combine(LiveTripData.rangeBasisIsLifetime) { b, life ->
                        b to life
                    }.collect { (basis, lifetime) -> renderRangeBasis(basis, lifetime) }
                }
                launch {
                    viewModel.efficiencyWhPerKm.combine(viewModel.lifetimeEffWhPerKm) { trip, avg ->
                        trip to avg
                    }.collect { (trip, avg) -> renderEfficiency(trip, avg) }
                }
                launch {
                    LiveVehicleData.powerKw.combine(LiveVehicleData.voltageV) { p, v -> p to v }
                        .collect { (p, v) -> renderPower(p, v > 0f) }
                }
                launch {
                    viewModel.tripDistanceKm.collect { dist ->
                        tvTripDist.text = Fmt.valueUnit(this@DashboardActivity,
                            String.format(Locale.US, "%.1f", dist), "km")
                    }
                }
                launch {
                    viewModel.tripTimeMin.collect { mins ->
                        tvTripTime.text = Fmt.hoursMinutes(this@DashboardActivity, mins)
                    }
                }
                launch {
                    viewModel.avgSpeedKmh.collect { avg ->
                        tvTripSpeed.text = Fmt.valueUnit(this@DashboardActivity, Fmt.whole(avg), "km/h")
                    }
                }
                launch {
                    viewModel.currentElevationM.collect { m ->
                        tvTripElev.text = Fmt.valueUnit(this@DashboardActivity,
                            if (m.isNaN()) Fmt.NONE else Fmt.grouped(m), "m")
                    }
                }
                launch {
                    LiveVehicleData.driveMode.collect { mode ->
                        // "Normal", "Eco", "Sport": sentence case, like the old
                        // "Connected" line this replaced.
                        val name = mode.trim().lowercase(Locale.US)
                            .replaceFirstChar { it.titlecase(Locale.US) }
                        tvModeName.text = name.ifEmpty { Fmt.NONE }
                        modePill.contentDescription = if (name.isEmpty())
                            "Drive mode not known yet. Opens drive modes."
                        else "Drive mode $name. Opens drive modes."
                    }
                }
                launch {
                    LiveVehicleData.acOn.collect { on ->
                        acIndicator.visibility = if (on) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    Health.state.collect { h ->
                        health = h
                        renderHealth(h)
                        renderAlert()
                    }
                }
                launch {
                    // A refusal the Modes screen didn't get to show — it arrived
                    // after you left it. The flow replays its latest value to
                    // every new collector, so only one not already shown counts.
                    viewModel.modeRefusals.collect { reason ->
                        if (reason === LiveVehicleData.lastModeRefusalShown) return@collect
                        LiveVehicleData.lastModeRefusalShown = reason
                        Toast.makeText(this@DashboardActivity,
                            "Drive mode not changed: $reason", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Connection state: recorded for the supervisor, and SHOWN — "ESP32 off",
        // "out of range", "wrong MAC", "permission denied" and "car parked" used
        // to produce an identical screen of dashes.
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                connected = state == BluetoothService.ConnectionState.CONNECTED
                if (connected) {
                    failedAttempts = 0
                    address?.let {
                        getSharedPreferences("prefs", MODE_PRIVATE).edit()
                            .putString(MainActivity.PREF_LAST_DEVICE, it)
                            .putString(MainActivity.PREF_LAST_USED, it).apply()
                    }
                } else if (state == BluetoothService.ConnectionState.ERROR) {
                    failedAttempts++
                }
                renderLinkStatus(state)
            }
        }

        // Reconnect supervisor. Deliberately a POLLING loop rather than a
        // reaction to DISCONNECTED/ERROR events: retrying from inside the state
        // collector could drop the DISCONNECTED that should trigger the next
        // retry (one-item buffer), leaving the loop idle until a restart.
        // connect() is a no-op when a socket is already live, so calling it on
        // every tick is harmless. Not lifecycle-gated: it must keep retrying
        // while another screen covers the dashboard. Quick retries first — at
        // key-on the head unit's Bluetooth and the ESP32 come up within seconds
        // of each other.
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

    // ══════════════════════════════════════════════════════════════════════
    //  Rendering
    // ══════════════════════════════════════════════════════════════════════

    private fun renderSoc(soc: Int) {
        val fill = when {
            soc in 0..9   -> color(R.color.crit)
            soc in 10..19 -> color(R.color.warn)
            else          -> color(R.color.fill)
        }
        socBar.fillColor = fill
        socBar.fraction = if (soc >= 0) soc / 100f else 0f
        tvSoc.setTextColor(when {
            soc < 0 -> color(R.color.text_muted)
            soc in 0..19 -> fill
            else -> color(R.color.text)
        })
        tvSoc.text = if (soc >= 0)
            Fmt.valueUnit(this, "$soc", "%", unitSp = 17, gap = "") else Fmt.NONE
    }

    private fun renderRangeBasis(basisWhPerKm: Float, lifetime: Boolean) {
        tvRangeBasis.text = when {
            SettingsActivity.designCapacity(this) <= 0f ->
                "Set the battery capacity in Settings to see range"
            basisWhPerKm <= 0f -> "Range appears after a few minutes of driving"
            lifetime -> "Based on your ${Fmt.kmPerKwh(basisWhPerKm)} km/kWh average"
            else -> "Based on ${Fmt.kmPerKwh(basisWhPerKm)} km/kWh over the last 30 min"
        }
    }

    private fun renderEfficiency(tripWhPerKm: Float, avgWhPerKm: Float) {
        val trip = if (tripWhPerKm > 0f) 1000f / tripWhPerKm else 0f
        val avg  = if (avgWhPerKm > 0f) 1000f / avgWhPerKm else 0f
        tvEffVal.text = Fmt.one(trip)
        tvEffVal.setTextColor(color(if (trip > 0f) R.color.text else R.color.text_muted))
        effScale.setValues(trip, avg)

        val accent = color(R.color.accent)
        val text2  = color(R.color.text2)
        when {
            trip <= 0f -> {
                ivEffTrend.visibility = View.GONE
                tvEffDelta.text = "Measuring this trip…"
            }
            avg <= 0f -> {
                ivEffTrend.visibility = View.GONE
                tvEffDelta.text = "No saved trips to compare yet"
            }
            else -> {
                val pct = (trip / avg - 1f) * 100f
                ivEffTrend.visibility = View.VISIBLE
                when {
                    abs(pct) < 1f -> {
                        ivEffTrend.visibility = View.GONE
                        tvEffDelta.text = "Right on your average"
                        effScale.dotColor = text2
                    }
                    pct > 0f -> {
                        ivEffTrend.setImageResource(R.drawable.ic_arrow_up)
                        ivEffTrend.imageTintList = ColorStateList.valueOf(accent)
                        val head = String.format(Locale.US, "%.0f%% better", pct)
                        tvEffDelta.text = SpannableStringBuilder("$head than your average").apply {
                            setSpan(ForegroundColorSpan(accent), 0, head.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        effScale.dotColor = accent
                    }
                    else -> {
                        ivEffTrend.setImageResource(R.drawable.ic_arrow_down)
                        ivEffTrend.imageTintList = ColorStateList.valueOf(text2)
                        tvEffDelta.text = String.format(Locale.US,
                            "%.0f%% below your average", -pct)
                        effScale.dotColor = text2
                    }
                }
            }
        }
    }

    private fun renderPower(kw: Float, live: Boolean) {
        val accent = color(R.color.accent)
        powerBand.powerKw = if (live) kw else 0f
        when {
            !live -> {
                tvPowerVal.text = Fmt.NONE
                tvPowerVal.setTextColor(color(R.color.text_muted))
                tvPowerState.text = ""
            }
            kw <= -0.3f -> {
                tvPowerVal.text = "−" + String.format(Locale.US, "%.1f", -kw)
                tvPowerVal.setTextColor(accent)
                tvPowerState.text = "Regenerating"
                tvPowerState.setTextColor(accent)
            }
            kw >= 0.3f -> {
                tvPowerVal.text = String.format(Locale.US, "%.1f", kw)
                tvPowerVal.setTextColor(color(R.color.text))
                tvPowerState.text = "Drawing"
                tvPowerState.setTextColor(color(R.color.text2))
            }
            else -> {
                tvPowerVal.text = "0.0"
                tvPowerVal.setTextColor(color(R.color.text))
                tvPowerState.text = "Idle"
                tvPowerState.setTextColor(color(R.color.text3))
            }
        }
    }

    private fun renderHealth(h: Health.State) {
        val warn  = color(R.color.warn)
        val crit  = color(R.color.crit)
        val text  = color(R.color.text)
        val text3 = color(R.color.text3)
        val text2 = color(R.color.text2)
        val warnTrack = color(R.color.warn_track)
        val line = color(R.color.line)

        fun temp(tv: TextView, label: TextView, bar: MeterBar, c: Float, derate: Float, isWarn: Boolean) {
            val col = if (isWarn) warn else text
            tv.setTextColor(col)
            label.setTextColor(if (isWarn) warn else text3)
            // 0 means "not reported yet"; anything else, below freezing too, is real.
            tv.text = Fmt.valueUnit(this, Fmt.whole(c, known = c != 0f), "°C",
                unitColor = if (isWarn) warn else null)
            bar.fraction = Health.headroom(c, derate)
            bar.fillColor = if (isWarn) warn else text2
            bar.trackColor = if (isWarn) warnTrack else line
        }
        temp(tvMotorTemp, tvMotorLabel, motorBar, h.motorC, Health.MOTOR_DERATE_C, h.motorWarn)
        temp(tvInvTemp, tvInvLabel, invBar, h.inverterC, Health.INVERTER_DERATE_C, h.inverterWarn)

        tv12v.setTextColor(if (h.auxLow) warn else text)
        tv12vLabel.setTextColor(if (h.auxLow) warn else text3)
        tv12v.text = Fmt.valueUnit(this, Fmt.one(h.auxV), "V", unitColor = if (h.auxLow) warn else null)

        // Only a temperature fault the readings back up shows here; any other
        // inverter error is logged under Settings → Inverter instead (it stays
        // set until the inverter restarts, long after a glitch has passed).
        when {
            h.errCode < 0 -> { tvFaults.text = Fmt.NONE; tvFaults.setTextColor(text); tvFaultsLabel.setTextColor(text3) }
            h.derate != null -> {
                tvFaults.text = h.errName.ifBlank { "Code ${h.errCode}" }
                tvFaults.setTextColor(crit)
                tvFaultsLabel.setTextColor(crit)
            }
            else -> { tvFaults.text = "None"; tvFaults.setTextColor(text); tvFaultsLabel.setTextColor(text3) }
        }

        val noData = h.errCode < 0 && h.motorC == 0f && h.inverterC == 0f
        when {
            noData -> {
                ivHealthIcon.visibility = View.GONE
                tvHealthStatus.text = "Waiting for data"
                tvHealthStatus.setTextColor(text3)
            }
            h.level == Health.Level.FAULT -> {
                ivHealthIcon.visibility = View.VISIBLE
                ivHealthIcon.setImageResource(R.drawable.ic_warning)
                ivHealthIcon.imageTintList = ColorStateList.valueOf(crit)
                tvHealthStatus.text =
                    if (h.derate == Health.Part.MOTOR) "Motor derating" else "Inverter derating"
                tvHealthStatus.setTextColor(crit)
            }
            h.level == Health.Level.WARN -> {
                ivHealthIcon.visibility = View.VISIBLE
                ivHealthIcon.setImageResource(R.drawable.ic_warning)
                ivHealthIcon.imageTintList = ColorStateList.valueOf(warn)
                tvHealthStatus.text = if (h.warnings == 1) "1 warning" else "${h.warnings} warnings"
                tvHealthStatus.setTextColor(warn)
            }
            else -> {
                ivHealthIcon.visibility = View.VISIBLE
                ivHealthIcon.setImageResource(R.drawable.ic_check)
                ivHealthIcon.imageTintList = ColorStateList.valueOf(color(R.color.accent))
                tvHealthStatus.text = "All normal"
                tvHealthStatus.setTextColor(text2)
            }
        }
    }

    /**
     * The alert pill, on the far right of the header, while something needs
     * attention. Most serious first: a derate the readings back up, 12 V low,
     * inverter, motor. Other inverter errors are only logged (Settings →
     * Inverter): the code stays set until the inverter restarts, so a passing
     * glitch would otherwise sit here in red all drive.
     */
    private fun renderAlert() {
        val h = health
        val alert: Triple<String, String, Boolean>? = when {
            // Only while the link is up: the readings aren't live otherwise.
            h == null || !connected -> null
            h.derate != null -> {
                val motor = h.derate == Health.Part.MOTOR
                Triple(if (motor) "Motor derating" else "Inverter derating",
                    "${Fmt.whole(if (motor) h.motorC else h.inverterC)} °C · " +
                    "${h.errName.ifBlank { "code" }} (${h.errCode})", true)
            }
            h.auxLow -> Triple("12 V low · ${Fmt.one(h.auxV)} V", "DC-DC not charging", false)
            h.inverterWarn -> Triple("Inverter ${Fmt.whole(h.inverterC)} °C",
                "derates at ${Health.INVERTER_DERATE_C.toInt()} °C", false)
            h.motorWarn -> Triple("Motor ${Fmt.whole(h.motorC)} °C",
                "derates at ${Health.MOTOR_DERATE_C.toInt()} °C", false)
            else -> null
        }
        if (alert == null) {
            alertPill.visibility = View.GONE
            return
        }
        val (title, detail, fault) = alert
        val main = color(if (fault) R.color.crit else R.color.warn)
        val soft = color(if (fault) R.color.crit_soft else R.color.warn_soft)
        alertPill.setBackgroundResource(if (fault) R.drawable.bg_alert_crit else R.drawable.bg_alert_warn)
        ivAlertIcon.imageTintList = ColorStateList.valueOf(main)
        ivAlertChevron.imageTintList = ColorStateList.valueOf(main)
        tvAlertTitle.setTextColor(main)
        tvAlertDetail.setTextColor(soft)
        tvAlertTitle.text = title
        tvAlertDetail.text = detail
        alertPill.visibility = View.VISIBLE
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
     * Left of the header: the drive mode when the link is up and all is well;
     * otherwise what is happening with the link, in words, in its place.
     */
    private fun renderLinkStatus(state: BluetoothService.ConnectionState?) {
        if (state != null) lastLinkState = state
        val btOn = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull() != false
        val isConnected = lastLinkState == BluetoothService.ConnectionState.CONNECTED
        linkText = when {
            syncing -> syncCounts.let { (got, total) ->
                if (total > 0) "Syncing trip history… $got / $total" else "Syncing trip history…"
            }
            isConnected -> when {
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
        val failing = !isConnected && (failedAttempts >= EXPLAIN_AFTER_ATTEMPTS || !btOn)
        val dotColor = when {
            isConnected || syncing -> color(R.color.accent)
            failing -> color(R.color.warn)
            else -> color(R.color.text3)
        }
        linkDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        renderAlert()
        tvLinkStatus.text = linkText.ifEmpty { "Connected" }
        tvLinkStatus.setTextColor(if (failing) color(R.color.warn) else color(R.color.text2))
        val linkUp = isConnected && linkText.isEmpty()
        modePill.visibility = if (linkUp) View.VISIBLE else View.GONE
        linkStatusGroup.visibility = if (linkUp) View.GONE else View.VISIBLE
    }

    /**
     * Escape hatch for a device that can never be reached: after roughly five
     * minutes of solid failure the status line becomes a way back to the picker.
     */
    private fun openDevicePicker() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .remove(MainActivity.PREF_LAST_DEVICE).apply()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Resolves a MAC address to a BluetoothDevice, null-safe if BT is unavailable. */
    private fun remoteDevice(addr: String): android.bluetooth.BluetoothDevice? =
        try {
            getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(addr)
        } catch (_: IllegalArgumentException) {
            null   // malformed address
        }
}

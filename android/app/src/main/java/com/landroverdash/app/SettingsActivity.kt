package com.landroverdash.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREF_DESIGN_CAPACITY = "design_capacity_kwh"
        const val PREF_SOH             = "battery_soh"
        const val PREF_VOLT_FULL       = "voltage_at_100"
        const val PREF_VOLT_EMPTY      = "voltage_at_0"
        const val PREF_AUTOSTART       = "autostart_on_boot"

        // The firmware's own SOC table (can_sniffer.ino PACK_V_FULL/EMPTY). Used
        // whenever no custom voltages are saved: the inverter conversion sends
        // soc = 0 and the old fuel-gauge SOC source no longer exists, so without
        // a default the ring and range simply read "--" forever.
        const val DEFAULT_VOLT_FULL  = 340f
        const val DEFAULT_VOLT_EMPTY = 270f

        /** Open the dashboard automatically when the head unit starts. */
        fun autoStartEnabled(ctx: Context): Boolean =
            ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTOSTART, true)

        /**
         * Cached snapshot of the four battery settings.
         *
         * These are read on the Bluetooth packet path — twice a second, four
         * lookups per packet through voltageSoc() and remainingKwh(). Each one
         * took the SharedPreferences lock, on the main thread, to fetch values
         * that change only when the user edits Settings.
         *
         * Kept honest by a change listener rather than a timeout, so an edit is
         * picked up immediately. The listener is held in a strong reference on
         * purpose — SharedPreferences keeps only a weak one, and a collected
         * listener would leave this cache permanently stale.
         */
        @Volatile private var cache: Settings? = null
        private var prefsRef: android.content.SharedPreferences? = null
        private val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                cache = null
            }

        private data class Settings(
            val designCapacity: Float,
            val soh: Float,
            val voltFull: Float,
            val voltEmpty: Float
        )

        private fun settings(ctx: Context): Settings {
            cache?.let { return it }
            return synchronized(this) {
                cache ?: run {
                    val p = prefsRef ?: ctx.applicationContext
                        .getSharedPreferences("prefs", Context.MODE_PRIVATE)
                        .also {
                            prefsRef = it
                            it.registerOnSharedPreferenceChangeListener(listener)
                        }
                    Settings(
                        designCapacity = p.getFloat(PREF_DESIGN_CAPACITY, 0f),
                        soh            = p.getFloat(PREF_SOH, 0.99f),
                        voltFull       = p.getFloat(PREF_VOLT_FULL, 0f)
                                          .takeIf { it > 0f } ?: DEFAULT_VOLT_FULL,
                        voltEmpty      = p.getFloat(PREF_VOLT_EMPTY, 0f)
                                          .takeIf { it > 0f } ?: DEFAULT_VOLT_EMPTY
                    ).also { cache = it }
                }
            }
        }

        /** Read design capacity from prefs — defaults to 0.0 if not set */
        fun designCapacity(ctx: Context): Float = settings(ctx).designCapacity

        /** Read SOH from prefs — defaults to 0.99 */
        fun soh(ctx: Context): Float = settings(ctx).soh

        /** Usable capacity = designCapacity × SOH */
        fun usableCapacity(ctx: Context): Float =
            settings(ctx).let { it.designCapacity * it.soh }

        /** Pack voltage that means 100% SOC — the saved value, else the default */
        fun voltFull(ctx: Context): Float = settings(ctx).voltFull

        /** Pack voltage that means 0% SOC — the saved value, else the default */
        fun voltEmpty(ctx: Context): Float = settings(ctx).voltEmpty

        /** True when voltage-based SOC is fully configured */
        fun voltageSocEnabled(ctx: Context): Boolean {
            val s = settings(ctx)
            return s.voltEmpty > 0f && s.voltFull > s.voltEmpty
        }
    }

    // ── Firmware file pickers ─────────────────────────────────────────────────
    // The system document browser lists every storage provider the head unit
    // has: USB drives, internal storage, SD card, Downloads, cloud apps. The
    // extras ask it to show internal storage and file sizes by default (some
    // builds hide both). "*/*" because .bin has no reliable MIME type — the
    // file is validated as an ESP32 image after it's picked.
    private val pickDocument = registerForActivityResult(
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).apply {
                    putExtra("android.content.extra.SHOW_ADVANCED", true)
                    putExtra("android.content.extra.SHOW_FILESIZE", true)
                }
        }
    ) { uri -> uri?.let { onFirmwarePicked(it) } }

    // Fallback for when the system picker is missing (as on the ATOTO): a plain
    // GET_CONTENT with NO "openable" category. The stock GetContent contract
    // adds CATEGORY_OPENABLE, which head-unit file managers don't declare, so
    // it found nothing even with a file manager installed.
    private val getContentPlain = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r -> r.data?.data?.let { onFirmwarePicked(it) } }

    // Built-in browser — works on any ROM. Needs storage access first.
    private val storageBrowser by lazy {
        StorageBrowser(this) { file -> onFirmwareFilePicked(file) }
    }
    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) storageBrowser.showRoots()
        else Toast.makeText(this, "Storage permission is needed to browse for the firmware file",
            Toast.LENGTH_LONG).show()
    }

    private lateinit var etCapacity:      EditText
    private lateinit var etSOH:           EditText
    private lateinit var etVoltFull:      EditText
    private lateinit var etVoltEmpty:     EditText
    private lateinit var tvUsable:        TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        applyTheme()

        etCapacity  = findViewById(R.id.etDesignCapacity)
        etSOH       = findViewById(R.id.etSOH)
        etVoltFull  = findViewById(R.id.etVoltFull)
        etVoltEmpty = findViewById(R.id.etVoltEmpty)
        tvUsable    = findViewById(R.id.tvUsableCapacity)

        // Load saved values
        val savedCapacity = designCapacity(this)
        val savedSOH      = soh(this)
        if (savedCapacity > 0f) etCapacity.setText("%.1f".format(savedCapacity))
        etSOH.setText("%.2f".format(savedSOH))
        // Only CUSTOM voltages fill the boxes; otherwise they stay blank with the
        // defaults that are actually in use shown as the hint.
        val rawPrefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val customFull  = rawPrefs.getFloat(PREF_VOLT_FULL, 0f)
        val customEmpty = rawPrefs.getFloat(PREF_VOLT_EMPTY, 0f)
        if (customFull  > 0f) etVoltFull.setText("%.1f".format(customFull))
        if (customEmpty > 0f) etVoltEmpty.setText("%.1f".format(customEmpty))
        etVoltFull.hint  = "default %.0f".format(DEFAULT_VOLT_FULL)
        etVoltEmpty.hint = "default %.0f".format(DEFAULT_VOLT_EMPTY)
        updateUsable()

        // Live preview of usable capacity as user types
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int)     {}
            override fun afterTextChanged(s: Editable?)  { updateUsable() }
        }
        etCapacity.addTextChangedListener(watcher)
        etSOH.addTextChangedListener(watcher)

        bindInverterStatus()
        bindFirmwareUpdate()
        bindDiagnostics()
        bindAutoStart()

        findViewById<TextView>(R.id.btnChangeDevice).setOnClickListener {
            // Clear saved device address → breaks retry loop → returns to picker
            getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                .remove(MainActivity.PREF_LAST_DEVICE).apply()
            // Restart app from MainActivity
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        findViewById<TextView>(R.id.btnSaveSettings).setOnClickListener {
            val capacity = etCapacity.text.toString().toFloatOrNull()
            val soh      = etSOH.text.toString().toFloatOrNull()

            if (capacity == null || capacity <= 0f) {
                Toast.makeText(this, "Enter a valid design capacity", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (soh == null || soh <= 0f || soh > 1f) {
                Toast.makeText(this, "SOH must be between 0.01 and 1.00", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Voltage-based SOC: both blank = the defaults (stored as 0), otherwise
            // both must be valid and 100% must be above 0%.
            val vFullText  = etVoltFull.text.toString().trim()
            val vEmptyText = etVoltEmpty.text.toString().trim()
            var vFull  = 0f
            var vEmpty = 0f
            if (vFullText.isNotEmpty() || vEmptyText.isNotEmpty()) {
                vFull  = vFullText.toFloatOrNull()  ?: -1f
                vEmpty = vEmptyText.toFloatOrNull() ?: -1f
                if (vFull <= 0f || vEmpty <= 0f) {
                    Toast.makeText(this, "Enter both 100% and 0% voltages, or clear both to use the defaults (%.0f V / %.0f V)"
                        .format(DEFAULT_VOLT_FULL, DEFAULT_VOLT_EMPTY), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (vFull <= vEmpty) {
                    Toast.makeText(this, "100% voltage must be higher than 0% voltage", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                .putFloat(PREF_DESIGN_CAPACITY, capacity)
                .putFloat(PREF_SOH, soh)
                .putFloat(PREF_VOLT_FULL, vFull)
                .putFloat(PREF_VOLT_EMPTY, vEmpty)
                .apply()

            // Hide keyboard
            val imm = getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)

            Toast.makeText(this,
                "Saved — usable capacity: ${"%.1f".format(capacity * soh)} kWh",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Live inverter fault display, fed from [LiveVehicleData] so it updates
     * while this screen is open rather than showing a snapshot.
     *
     * Deliberately shows the raw code next to the name. If the parameter id or
     * the enum ever drifts from what this firmware version reports, a bare name
     * would quietly mislead; a visible number makes the mismatch obvious.
     */
    private fun bindInverterStatus() {
        val tvErr    = findViewById<TextView>(R.id.tvInvError)
        val tvStatus = findViewById<TextView>(R.id.tvInvStatus)
        val tvHint   = findViewById<TextView>(R.id.tvInvHint)

        val ok    = Color.parseColor("#6AAA7A")
        val bad   = Color.parseColor("#C4553D")
        val warn  = Color.parseColor("#FFC14D")
        val plain = Color.parseColor("#DDEEDF")

        // Link state, polled: VehicleLink.service can appear or change while
        // this screen is open, so there is no single flow to collect.
        val linkUp = flow {
            while (true) { emit(VehicleLink.service?.isConnected() == true); delay(1_000) }
        }.distinctUntilChanged()

        lifecycleScope.launch {
            LiveVehicleData.errCode.combine(linkUp) { code, up -> code to up }.collect { (code, up) ->
                val name = LiveVehicleData.errName.value
                when {
                    // Connected to the ESP32 but no inverter reply yet: it is
                    // still waking (or its CAN isn't reaching the ESP32).
                    code < 0 && up -> {
                        tvErr.text = "Waiting for the inverter…"
                        tvErr.setTextColor(plain)
                    }
                    code < 0 -> {
                        tvErr.text = "Not connected"
                        tvErr.setTextColor(plain)
                    }
                    code == 0 -> {
                        // A fault can appear and clear before you reach this
                        // screen, so surface anything seen earlier this session.
                        val seen = LiveVehicleData.errSeen.value
                        if (seen > 0) {
                            tvErr.text = "No fault now (was " +
                                "${LiveVehicleData.errSeenName.value} this session)"
                            tvErr.setTextColor(ok)
                        } else {
                            tvErr.text = "No faults"
                            tvErr.setTextColor(ok)
                        }
                    }
                    else -> {
                        tvErr.text = "$name  ($code)"
                        tvErr.setTextColor(bad)
                    }
                }
            }
        }

        lifecycleScope.launch {
            LiveVehicleData.statusText.collect { s ->
                tvStatus.text = if (s.isEmpty()) "—" else s
                // Green only for "None" (nothing holding the inverter back);
                // any flag is amber — something to look at, not necessarily
                // broken; unknown stays neutral.
                tvStatus.setTextColor(when {
                    s == "None"               -> ok
                    s.isEmpty() || s == "?"   -> plain
                    else                      -> warn
                })
                // Translate the two flags that most often look like a fault but
                // are the inverter waiting on you, not something broken.
                tvHint.text = when {
                    s.contains("BrakeCheck") ->
                        "BrakeCheck: waiting for a brake press before it will start."
                    s.contains("WaitStart") ->
                        "WaitStart: ready, waiting for the start signal."
                    s.contains("UdcBelowUdcSw") ->
                        "Pack voltage is below the contactor switch-on threshold."
                    s.contains("EmcyStop") ->
                        "Emergency stop input is active."
                    else -> ""
                }
            }
        }
    }

    // ── Car firmware update ──────────────────────────────────────────────────

    private fun bindFirmwareUpdate() {
        val tvVer      = findViewById<TextView>(R.id.tvFwVersion)
        val btnPick    = findViewById<TextView>(R.id.btnPickFirmware)
        val tvFile     = findViewById<TextView>(R.id.tvFwFile)
        val btnInstall = findViewById<TextView>(R.id.btnInstallFirmware)
        val pb         = findViewById<ProgressBar>(R.id.pbFirmware)
        val tvStatus   = findViewById<TextView>(R.id.tvFwStatus)
        val btnCancel  = findViewById<TextView>(R.id.btnCancelFirmware)

        val ok    = Color.parseColor("#6AAA7A")
        val bad   = Color.parseColor("#C4553D")
        val plain = Color.parseColor("#DDEEDF")

        // Primary: the built-in browser (internal storage, USB, SD) — works on
        // any ROM, including ones with no system picker.
        btnPick.setOnClickListener {
            if (FirmwareUpdater.state.value.busy) return@setOnClickListener
            openStorageBrowser()
        }

        // Secondary: hand off to the system picker or a file-manager app.
        findViewById<TextView>(R.id.btnPickFirmwareApp).setOnClickListener {
            if (FirmwareUpdater.state.value.busy) return@setOnClickListener
            try {
                pickDocument.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                try {
                    getContentPlain.launch(
                        Intent.createChooser(
                            Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
                            "Choose firmware file"))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this,
                        "No app on this unit accepts file requests — use the button above instead",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        btnInstall.setOnClickListener {
            val img = FirmwareUpdater.staged.value ?: return@setOnClickListener
            // Compare with what the car last reported, so a downgrade or a
            // same-version reinstall is a conscious choice.
            val cur  = VehicleLink.service?.firmwareInfo?.value
                ?.optString("version")?.takeIf { it.isNotEmpty() }
            val curV = FirmwareUpdater.parseVersion(cur)
            val newV = FirmwareUpdater.parseVersion(img.version)
            val versionNote = if (curV == null || newV == null) "" else {
                val cmp = compareValuesBy(newV, curV, { it.first }, { it.second })
                when {
                    cmp == 0 -> "The car already runs $cur — installing it again is harmless.\n\n"
                    cmp < 0  -> "Note: this is OLDER than the car's current $cur.\n\n"
                    else     -> "Replaces $cur.\n\n"
                }
            }
            AlertDialog.Builder(this)
                .setTitle("Install car firmware ${img.version}?")
                .setMessage(
                    "${img.name} (${img.bytes.size / 1024} KB)\n\n" +
                    versionNote +
                    "• The car must be stopped with the inverter off.\n" +
                    "• Keep the ignition on — it takes about 2 minutes.\n" +
                    "• Gauges may pause briefly; the interface restarts at the end.\n\n" +
                    "If anything goes wrong the car keeps its current firmware.")
                .setPositiveButton("Install") { _, _ -> FirmwareUpdater.install() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnCancel.setOnClickListener { FirmwareUpdater.cancel() }

        lifecycleScope.launch {
            var wasDone = false
            FirmwareUpdater.state.combine(FirmwareUpdater.staged) { s, img -> s to img }
                .collect { (s, img) ->
                    // Re-read the version once when an install finishes — not on
                    // every later emission while the state still says Done.
                    if (s is OtaState.Done && !wasDone) startVersionRefresh(tvVer)
                    wasDone = s is OtaState.Done
                    tvFile.text = img?.let {
                        "${it.name}  ·  ${it.version}  ·  ${it.bytes.size / 1024} KB  ·  md5 ${it.md5.take(8)}…"
                    } ?: "No file selected. Use the .ino.bin from Sketch → Export Compiled Binary."
                    tvFile.setTextColor(if (img != null) plain else Color.parseColor("#888888"))

                    val canInstall = img != null && !s.busy
                    btnInstall.isEnabled = canInstall
                    btnInstall.alpha = if (canInstall) 1f else 0.4f
                    btnPick.alpha = if (s.busy) 0.4f else 1f

                    btnCancel.visibility =
                        if (s is OtaState.Starting || s is OtaState.Transferring) View.VISIBLE
                        else View.GONE

                    // Don't let the head unit's screen timeout interrupt it.
                    if (s.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                    pb.visibility = when (s) {
                        is OtaState.Transferring, OtaState.Verifying -> View.VISIBLE
                        else -> View.GONE
                    }
                    tvStatus.setTextColor(plain)
                    tvStatus.text = when (s) {
                        OtaState.Idle -> ""
                        OtaState.Starting -> "Asking the car to start the update…"
                        is OtaState.Transferring -> {
                            pb.progress = (s.sent.toLong() * 1000 / s.total).toInt()
                            "Sending… ${s.sent * 100L / s.total}%  (${s.sent / 1024} / ${s.total / 1024} KB)"
                        }
                        OtaState.Verifying -> { pb.progress = 1000; "Checking the image…" }
                        OtaState.Rebooting -> "Installed — the car is restarting. Waiting for it to reconnect…"
                        OtaState.Confirming -> "Reconnected — confirming the new firmware…"
                        is OtaState.Done -> {
                            tvStatus.setTextColor(ok)
                            s.message
                        }
                        is OtaState.Failed -> { tvStatus.setTextColor(bad); s.message }
                    }
                }
        }

        // Tap the version line to ask again (e.g. after "No reply").
        tvVer.setOnClickListener { startVersionRefresh(tvVer) }
        startVersionRefresh(tvVer)
    }

    private var versionJob: Job? = null

    /** At most one version check at a time. */
    private fun startVersionRefresh(tv: TextView) {
        if (versionJob?.isActive != true)
            versionJob = lifecycleScope.launch { refreshFirmwareVersion(tv) }
    }

    private suspend fun refreshFirmwareVersion(tv: TextView) {
        val busyText = "Update in progress…"
        if (FirmwareUpdater.state.value.busy) { tv.text = busyText; return }
        tv.text = "Checking…"
        tv.text = when (val r = FirmwareUpdater.queryVersion()) {
            FwQuery.Busy -> busyText
            FwQuery.NotConnected -> "Not connected to the car"
            FwQuery.NoAnswer ->
                "No reply — the link may be busy syncing history. Tap to try again."
            FwQuery.TooOld ->
                "Older than v7.1 — can't update over Bluetooth yet (flash v7.1 once over USB)"
            is FwQuery.Info -> {
                val info = r.json
                when {
                    !info.optBoolean("ota") ->
                        "${info.optString("version")}  ·  no OTA partition — reflash once over USB"
                    !info.optBoolean("rollback") ->
                        "${info.optString("version")}  ·  ready (automatic rollback unavailable)"
                    else ->
                        "${info.optString("version")}  ·  ready for updates"
                }
            }
        }
    }

    // ── Car interface diagnostics (firmware v7.3+) ───────────────────────────

    private var diagJob: Job? = null

    private fun bindDiagnostics() {
        val tv  = findViewById<TextView>(R.id.tvDiag)
        val btn = findViewById<TextView>(R.id.btnRunDiag)
        btn.setOnClickListener {
            if (diagJob?.isActive == true) return@setOnClickListener
            diagJob = lifecycleScope.launch {
                btn.alpha = 0.4f
                try { tv.text = runDiag() } finally { btn.alpha = 1f }
            }
        }
    }

    /** Sends DIAG and turns the reply into plain sentences. */
    private suspend fun runDiag(): CharSequence {
        val svc = VehicleLink.service
        if (svc == null || !svc.isConnected()) return "Not connected to the car."
        if (FirmwareUpdater.state.value.busy) return "Wait for the firmware update to finish."
        // One exchange at a time on the link (see FirmwareUpdater.queryLock).
        return FirmwareUpdater.queryLock.withLock {
            if (FirmwareUpdater.state.value.busy)
                return@withLock "Wait for the firmware update to finish."
            val prev    = svc.diagInfo.value
            val prevErr = svc.lastRefusal.value
            if (!svc.sendCommand("DIAG"))
                return@withLock "Couldn't reach the car — the link dropped."
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val d = svc.diagInfo.value
                if (d != null && d !== prev) return@withLock formatDiag(d)
                val err = svc.lastRefusal.value
                if (err != null && err !== prevErr)
                    return@withLock "The car's firmware is older than v7.3, which added " +
                        "this report. Install v7.3 with Car Firmware Update above."
                if (!svc.isConnected()) return@withLock "The link dropped — try again."
                delay(50)
            }
            "No reply — the link may be busy syncing history. Try again in a minute."
        }
    }

    private fun formatDiag(j: JSONObject): String {
        val lines = mutableListOf<String>()

        val up = j.optLong("up")
        val upText = if (up >= 3600) "${up / 3600} h ${(up % 3600) / 60} min"
                     else "${up / 60} min ${up % 60} s"
        lines += "Firmware ${j.optString("fw")} · running $upText · start #${j.optLong("boots")}"

        lines += "Last restart: " + when (val r = j.optString("reset")) {
            "power-on"   -> "power-on (normal key-on start)"
            "software"   -> "software restart (normal after a firmware update)"
            "brownout"   -> "BROWNOUT — its 12 V supply dipped too low. Check the feed and " +
                            "ground; a dip at key-on restarts it and the gauges drop out."
            "crash"      -> "CRASH — a firmware fault restarted it. Worth reporting."
            "watchdog", "watchdog-int", "watchdog-task" ->
                            "WATCHDOG — the firmware stalled and was restarted."
            "reset-pin"  -> "reset line (EN pin)"
            "deep-sleep" -> "wake from sleep"
            else         -> r.ifEmpty { "unknown" }
        }

        val canUp   = j.optLong("canup")
        val firstTx = j.optLong("firsttx")
        lines += if (j.optString("bus") == "not-started")
            "CAN: FAILED TO START — check the CAN transceiver wiring."
        else "CAN up ${canUp} ms after boot; first gauge frame at " +
             (if (firstTx > 0) "$firstTx ms" else "— (none sent yet)")

        if (j.optString("bus") != "not-started") {
            val bus = when (j.optString("bus")) {
                "running"    -> "running"
                "bus-off"    -> "BUS-OFF (recovering)"
                "recovering" -> "recovering"
                "stopped"    -> "stopped"
                else         -> j.optString("bus", "unknown")
            }
            val errs = buildList {
                if (j.optLong("busoff") > 0) add("bus-off ${j.optLong("busoff")}×")
                if (j.optLong("tec") > 0 || j.optLong("rec") > 0)
                    add("error counters TX ${j.optLong("tec")} / RX ${j.optLong("rec")}")
                if (j.optLong("txfail") > 0) add("${j.optLong("txfail")} failed sends")
                if (j.optLong("rxmiss") > 0) add("${j.optLong("rxmiss")} missed frames")
                if (j.optLong("buserr") > 0) add("${j.optLong("buserr")} bus errors")
            }
            lines += "Bus: $bus" +
                if (errs.isEmpty()) " · no errors" else " · " + errs.joinToString(", ")
        }

        val dups = listOf("0x316 (rpm)" to j.optLong("dup316"),
                          "0x329 (temp)" to j.optLong("dup329"),
                          "0x43F (gear)" to j.optLong("dup43f")).filter { it.second > 0 }
        lines += if (dups.isEmpty()) "Gauge frames: only this interface is sending them."
        else "ANOTHER MODULE is also sending " +
             dups.joinToString(", ") { "${it.first} ×${it.second}" } +
             ". Two senders make the cluster flicker or freeze — find and disable the other one."

        lines += "Fuel needle: " + when (j.optString("fuel")) {
            "live"    -> "following pack voltage"
            "saved"   -> "holding the last saved level until the inverter's DC link is up"
            "default" -> "half-scale default (no level saved yet — it learns on the first drive)"
            else      -> j.optString("fuel", "unknown")
        }

        lines += "SD log: " + (if (j.optBoolean("sd")) "OK" else "not detected") +
                 " · free memory ${j.optLong("heap")} KB"
        return lines.joinToString("\n")
    }

    // ── Start with the car ───────────────────────────────────────────────────

    private fun bindAutoStart() {
        val btn = findViewById<TextView>(R.id.btnAutoStart)
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun render() {
            btn.text = if (autoStartEnabled(this)) "OPEN AUTOMATICALLY: ON" else "OPEN AUTOMATICALLY: OFF"
            renderOverlayState()
        }
        btn.setOnClickListener {
            prefs.edit().putBoolean(PREF_AUTOSTART, !autoStartEnabled(this)).apply()
            render()
        }
        findViewById<TextView>(R.id.btnOverlayPermission).setOnClickListener {
            openOverlaySettings()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system "Display over other apps" screen.
        renderOverlayState()
    }

    private fun renderOverlayState() {
        val tv  = findViewById<TextView>(R.id.tvOverlayState) ?: return
        val btn = findViewById<TextView>(R.id.btnOverlayPermission) ?: return
        val on = autoStartEnabled(this)
        val granted = android.provider.Settings.canDrawOverlays(this)
        tv.text = when {
            !on     -> "The app opens only when you tap it."
            granted -> "Opens when the head unit starts. \"Display over other apps\" is allowed."
            else    -> "Opens when the head unit starts — but Android may block that until " +
                       "\"Display over other apps\" is allowed for this app."
        }
        btn.visibility = if (on && !granted) View.VISIBLE else View.GONE
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            // Some head-unit ROMs drop that screen; the app info page usually
            // still has the switch under "Advanced".
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "This unit has no screen for that permission",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Gets whatever storage access this Android version needs, then browses. */
    private fun openStorageBrowser() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+: a .bin isn't media, so it takes "All files access".
            if (!android.os.Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Allow file access")
                    .setMessage("To read the firmware file from USB or storage, allow " +
                        "\"All files access\" for this app on the next screen, then come back " +
                        "and tap Choose again.")
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            startActivity(Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")))
                        } catch (_: ActivityNotFoundException) {
                            try {
                                startActivity(Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(this, "This unit has no file-access settings screen — " +
                                    "use \"or open with a file manager app\" instead",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            storageBrowser.showRoots()
            return
        }
        // Android 10 (the ATOTO): ordinary read permission is enough.
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            storageBrowser.showRoots()
        } else {
            storagePermLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun onFirmwareFilePicked(file: java.io.File) {
        lifecycleScope.launch {
            val err = FirmwareUpdater.stageFile(file)
            if (err != null) Toast.makeText(this@SettingsActivity, err, Toast.LENGTH_LONG).show()
        }
    }

    private fun onFirmwarePicked(uri: Uri) {
        lifecycleScope.launch {
            val err = FirmwareUpdater.stage(this@SettingsActivity, uri)
            if (err != null) Toast.makeText(this@SettingsActivity, err, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUsable() {
        val cap = etCapacity.text.toString().toFloatOrNull() ?: 0f
        val soh = etSOH.text.toString().toFloatOrNull() ?: 0f
        tvUsable.text = if (cap > 0f && soh > 0f) "%.1f kWh".format(cap * soh) else "-- kWh"
    }

    private fun applyTheme() {
        val rootBg   = Color.parseColor("#000000")
        val labelCol = Color.parseColor("#DDEEDF")
        val valueCol = Color.parseColor("#FFFFFF")

        findViewById<View>(R.id.settingsRoot).setBackgroundColor(rootBg)
        findViewById<View>(R.id.settingsTile).setBackgroundResource(R.drawable.tile_background_dark)
        findViewById<TextView>(R.id.tvUsableCapacity).setTextColor(valueCol)

        // Style EditTexts
        listOf(R.id.etDesignCapacity, R.id.etSOH, R.id.etVoltFull, R.id.etVoltEmpty).forEach {
            findViewById<EditText>(it).setTextColor(valueCol)
        }
    }
}

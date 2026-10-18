package com.landroverdash.app

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

/**
 * Settings — a sub-menu of six sections (Battery, Inverter, Firmware,
 * Diagnostics, Startup, Bluetooth), one shown at a time so nothing needs
 * scrolling on the 600 px screen. Section names are one word each so every
 * entry stays on one line at the head unit's large font size.
 *
 * Everything the old single-page Settings did is still here, with the same
 * preferences and the same firmware and diagnostics flows. State of health is
 * now entered as a percentage (99) and still stored as a fraction (0.99).
 */
class SettingsActivity : WattsonActivity() {

    companion object {
        const val PREF_DESIGN_CAPACITY = "design_capacity_kwh"
        const val PREF_SOH             = "battery_soh"
        const val PREF_VOLT_FULL       = "voltage_at_100"
        const val PREF_VOLT_EMPTY      = "voltage_at_0"
        const val PREF_AUTOSTART       = "autostart_on_boot"

        // The firmware's own SOC table (freelander-esp32.ino PACK_V_FULL/EMPTY). Used
        // whenever no custom voltages are saved: the inverter conversion sends
        // soc = 0 and the old fuel-gauge SOC source no longer exists, so without
        // a default the battery % and range simply read "--" forever.
        const val DEFAULT_VOLT_FULL  = 340f
        const val DEFAULT_VOLT_EMPTY = 270f

        /** Section shown last, so coming back to Settings lands in the same place. */
        private var lastSection = Section.BATTERY

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

        /** Read SOH from prefs — a fraction, defaults to 0.99 */
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

    // ── Sections ─────────────────────────────────────────────────────────────
    private enum class Section(val navId: Int, val panelId: Int, val label: String) {
        BATTERY(R.id.navBattery, R.id.sectionBattery, "Battery"),
        INVERTER(R.id.navInverter, R.id.sectionInverter, "Inverter"),
        FIRMWARE(R.id.navFirmware, R.id.sectionFirmware, "Firmware"),
        DIAG(R.id.navDiag, R.id.sectionDiag, "Diagnostics"),
        STARTUP(R.id.navStartup, R.id.sectionStartup, "Startup"),
        DEVICE(R.id.navDevice, R.id.sectionDevice, "Bluetooth")
    }

    private var section = Section.BATTERY

    /** The short detail on the right of each sub-menu entry. */
    private fun navDetail(s: Section, text: String, colorRes: Int? = null) {
        val tv = findViewById<View>(s.navId).findViewById<TextView>(R.id.tvNavDetail)
        tv.text = text
        if (colorRes == null)
            tv.setTextColor(resources.getColorStateList(R.color.settings_nav_detail, theme))
        else tv.setTextColor(color(colorRes))
    }

    private fun showSection(s: Section) {
        section = s
        lastSection = s
        for (x in Section.values()) {
            findViewById<View>(x.navId).isSelected = x == s
            findViewById<View>(x.panelId).visibility = if (x == s) View.VISIBLE else View.GONE
        }
        findViewById<android.widget.ScrollView>(R.id.settingsTile).scrollTo(0, 0)
        currentFocus?.let { f ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(f.windowToken, 0)
            f.clearFocus()
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

    /** Link state, polled (see [observeLink]); read by several sections. */
    private val linkUp = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindNavRail(Screen.SETTINGS)

        for (s in Section.values()) {
            val item = findViewById<View>(s.navId)
            item.findViewById<TextView>(R.id.tvNavLabel).text = s.label
            item.setOnClickListener { showSection(s) }
        }
        val restored = savedInstanceState?.getString("section")
            ?.let { n -> Section.values().firstOrNull { it.name == n } }
        showSection(restored ?: lastSection)

        observeLink(findViewById(R.id.devLinkDot), findViewById(R.id.tvDevLink)) { up ->
            linkUp.value = up
            navDetail(Section.DEVICE, if (up) "Connected" else "Not connected",
                if (up) null else R.color.text3)
        }

        bindBattery()
        bindInverterStatus()
        bindFirmwareUpdate()
        bindDiagnostics()
        bindAutoStart()
        bindDevice()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("section", section.name)
    }

    // ── Battery ──────────────────────────────────────────────────────────────

    private fun bindBattery() {
        etCapacity  = findViewById(R.id.etDesignCapacity)
        etSOH       = findViewById(R.id.etSOH)
        etVoltFull  = findViewById(R.id.etVoltFull)
        etVoltEmpty = findViewById(R.id.etVoltEmpty)
        tvUsable    = findViewById(R.id.tvUsableCapacity)

        // Load saved values
        val savedCapacity = designCapacity(this)
        val savedSOH      = soh(this)
        if (savedCapacity > 0f) etCapacity.setText(String.format(Locale.US, "%.1f", savedCapacity))
        etSOH.setText(percentText(savedSOH))
        // Only CUSTOM voltages fill the boxes; otherwise they stay blank with the
        // defaults that are actually in use shown as the hint.
        val rawPrefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val customFull  = rawPrefs.getFloat(PREF_VOLT_FULL, 0f)
        val customEmpty = rawPrefs.getFloat(PREF_VOLT_EMPTY, 0f)
        if (customFull  > 0f) etVoltFull.setText(String.format(Locale.US, "%.1f", customFull))
        if (customEmpty > 0f) etVoltEmpty.setText(String.format(Locale.US, "%.1f", customEmpty))
        etVoltFull.hint  = String.format(Locale.US, "%.0f", DEFAULT_VOLT_FULL)
        etVoltEmpty.hint = String.format(Locale.US, "%.0f", DEFAULT_VOLT_EMPTY)
        findViewById<TextView>(R.id.tvVoltHint).text = String.format(Locale.US,
            "Leave both blank to use the built-in %.0f V full and %.0f V empty.",
            DEFAULT_VOLT_FULL, DEFAULT_VOLT_EMPTY)
        updateUsable()
        renderBatteryDetail()

        // Live preview of usable capacity as user types
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int)     {}
            override fun afterTextChanged(s: Editable?)  { updateUsable() }
        }
        etCapacity.addTextChangedListener(watcher)
        etSOH.addTextChangedListener(watcher)

        findViewById<TextView>(R.id.btnSaveSettings).setOnClickListener { saveBattery() }
        etVoltEmpty.setOnEditorActionListener { _, _, _ -> saveBattery(); true }
    }

    /** 0.99 → "99", 0.985 → "98.5". */
    private fun percentText(fraction: Float): String {
        val pct = fraction * 100f
        return if (kotlin.math.abs(pct - Math.round(pct)) < 0.05f)
            String.format(Locale.US, "%.0f", pct) else String.format(Locale.US, "%.1f", pct)
    }

    /**
     * State of health as a percentage. A value below 1 is taken as the old
     * fraction format (0.99 → 99 %) rather than as 0.99 %, which would cut
     * the usable capacity, and the range, a hundredfold.
     */
    private fun sohPercent(text: String): Float? {
        val v = text.trim().toFloatOrNull() ?: return null
        return if (v > 0f && v < 1f) v * 100f else v
    }

    private fun saveBattery() {
        val capacity = etCapacity.text.toString().toFloatOrNull()
        val sohPct   = sohPercent(etSOH.text.toString())

        if (capacity == null || capacity <= 0f) {
            Toast.makeText(this, "Enter the battery's design capacity in kWh", Toast.LENGTH_SHORT).show()
            etCapacity.requestFocus()
            return
        }
        if (sohPct == null || sohPct < 1f || sohPct > 100f) {
            Toast.makeText(this, "State of health is a percentage, 1 to 100", Toast.LENGTH_SHORT).show()
            etSOH.requestFocus()
            return
        }
        val soh = sohPct / 100f
        etSOH.setText(percentText(soh))      // show what was understood

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
                Toast.makeText(this, String.format(Locale.US,
                    "Enter both voltages, or clear both to use the defaults (%.0f V / %.0f V)",
                    DEFAULT_VOLT_FULL, DEFAULT_VOLT_EMPTY), Toast.LENGTH_LONG).show()
                return
            }
            if (vFull <= vEmpty) {
                Toast.makeText(this, "The 100% voltage must be higher than the 0% voltage",
                    Toast.LENGTH_LONG).show()
                return
            }
        }

        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putFloat(PREF_DESIGN_CAPACITY, capacity)
            .putFloat(PREF_SOH, soh)
            .putFloat(PREF_VOLT_FULL, vFull)
            .putFloat(PREF_VOLT_EMPTY, vEmpty)
            .apply()

        // Hide keyboard
        currentFocus?.let { f ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(f.windowToken, 0)
            f.clearFocus()
        }
        renderBatteryDetail(capacity)

        Toast.makeText(this,
            String.format(Locale.US, "Saved — usable capacity %.1f kWh", capacity * soh),
            Toast.LENGTH_LONG).show()
    }

    private fun renderBatteryDetail(capacity: Float = designCapacity(this)) {
        if (capacity > 0f) navDetail(Section.BATTERY, String.format(Locale.US, "%.1f kWh", capacity))
        else navDetail(Section.BATTERY, "Not set", R.color.warn)
    }

    private fun updateUsable() {
        val cap = etCapacity.text.toString().toFloatOrNull() ?: 0f
        val soh = (sohPercent(etSOH.text.toString()) ?: 0f) / 100f
        tvUsable.text = if (cap > 0f && soh > 0f && soh <= 1f)
            String.format(Locale.US, "%.1f kWh", cap * soh) else "${Fmt.NONE} kWh"
    }

    // ── Inverter ─────────────────────────────────────────────────────────────

    /**
     * Live inverter fault display, fed from [LiveVehicleData] so it updates
     * while this screen is open rather than showing a snapshot.
     *
     * Deliberately shows the raw code next to the name. If the parameter id or
     * the enum ever drifts from what this firmware version reports, a bare name
     * would quietly mislead; a visible number makes the mismatch obvious.
     */
    private fun bindInverterStatus() {
        val ivErr    = findViewById<ImageView>(R.id.ivInvError)
        val tvErr    = findViewById<TextView>(R.id.tvInvError)
        val tvNote   = findViewById<TextView>(R.id.tvInvNote)
        val tvStatus = findViewById<TextView>(R.id.tvInvStatus)
        val tvHint   = findViewById<TextView>(R.id.tvInvHint)
        val logRows  = findViewById<LinearLayout>(R.id.faultLogRows)
        val logEmpty = findViewById<TextView>(R.id.tvFaultLogEmpty)
        val btnClear = findViewById<TextView>(R.id.btnClearFaultLog)
        FaultLog.init(this)

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear the error log?")
                .setMessage("Removes every logged inverter error from this phone.")
                .setPositiveButton("Clear") { _, _ -> FaultLog.clear() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val mono = ResourcesCompat.getFont(this, R.font.ibm_plex_mono_regular)
        findViewById<TextView>(R.id.tvInvFootnote).let { tv ->
            val s = tv.text.toString()
            val i = s.indexOf("ERRORS.CSV")
            if (i >= 0 && mono != null) tv.text = SpannableStringBuilder(s).apply {
                setSpan(TypefaceSpan(mono), i, i + 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color(R.color.text2)), i, i + 10,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(LiveVehicleData.errCode, LiveVehicleData.derate, linkUp, FaultLog.events) {
                        code, derate, up, events -> InverterView(code, derate, up, events)
                    }.collect { v ->
                        val code = v.code
                        val name = LiveVehicleData.errName.value
                        val label = "${name.ifBlank { "Code $code" }}  ($code)"
                        // Errors logged since the app started: this drive.
                        val thisDrive = v.events.count { it.lastMs >= FaultLog.sessionStartMs }
                        when {
                            // Connected to the ESP32 but no inverter reply yet: it is
                            // still waking (or its CAN isn't reaching the ESP32).
                            code < 0 && v.up -> {
                                ivErr.visibility = View.GONE
                                tvErr.text = "Waiting for the inverter…"
                                tvErr.setTextColor(color(R.color.text2))
                                navDetail(Section.INVERTER, "Waiting", R.color.text3)
                            }
                            code < 0 -> {
                                ivErr.visibility = View.GONE
                                tvErr.text = "Not connected"
                                tvErr.setTextColor(color(R.color.text2))
                                navDetail(Section.INVERTER, "Not connected", R.color.text3)
                            }
                            code == 0 -> {
                                ivErr.visibility = View.VISIBLE
                                ivErr.setImageResource(R.drawable.ic_check)
                                ivErr.imageTintList = ColorStateList.valueOf(color(R.color.accent))
                                tvErr.text = if (thisDrive > 0) "No fault now" else "No faults"
                                tvErr.setTextColor(color(R.color.text))
                                // The one place a passing error still shows: here, until
                                // the app restarts. The log below has the detail.
                                if (thisDrive > 0) navDetail(Section.INVERTER, "$thisDrive logged", R.color.warn)
                                else navDetail(Section.INVERTER, "No fault now")
                            }
                            v.derate != null -> {
                                ivErr.visibility = View.VISIBLE
                                ivErr.setImageResource(R.drawable.ic_warning)
                                ivErr.imageTintList = ColorStateList.valueOf(color(R.color.crit))
                                tvErr.text = label
                                tvErr.setTextColor(color(R.color.crit))
                                navDetail(Section.INVERTER, "Derating", R.color.crit)
                            }
                            else -> {
                                ivErr.visibility = View.VISIBLE
                                ivErr.setImageResource(R.drawable.ic_warning)
                                ivErr.imageTintList = ColorStateList.valueOf(color(R.color.warn))
                                tvErr.text = label
                                tvErr.setTextColor(color(R.color.warn))
                                navDetail(Section.INVERTER, "Error", R.color.warn)
                            }
                        }
                        tvNote.text = if (code > 0) inverterErrorNote(name, v.derate) else ""
                        tvNote.visibility = if (code > 0) View.VISIBLE else View.GONE
                        showFaultLog(logRows, v.events, code)
                        logEmpty.visibility = if (v.events.isEmpty()) View.VISIBLE else View.GONE
                        btnClear.visibility = if (v.events.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    LiveVehicleData.statusText.collect { s ->
                        tvStatus.text = if (s.isEmpty()) Fmt.NONE else s
                        // "None" means nothing is holding the inverter back; any
                        // flag is amber — something to look at, not necessarily
                        // broken; unknown stays neutral.
                        val flagged = s.isNotEmpty() && s != "None" && s != "?"
                        tvStatus.setBackgroundResource(if (flagged) R.drawable.bg_chip_warn else R.drawable.bg_chip_neutral)
                        tvStatus.setTextColor(color(when {
                            s == "None" -> R.color.accent
                            flagged -> R.color.warn
                            else -> R.color.text2
                        }))
                        // Translate the flags that most often look like a fault but
                        // are the inverter waiting on you, not something broken.
                        tvHint.text = when {
                            s.isEmpty() -> "Shown once the inverter answers."
                            s == "None" -> "Nothing is holding the inverter back."
                            s.contains("BrakeCheck") -> "Waiting for a brake press before it will start."
                            s.contains("WaitStart") -> "Ready, waiting for the start signal."
                            s.contains("UdcBelowUdcSw") -> "Pack voltage is below the contactor switch-on threshold."
                            s.contains("EmcyStop") -> "The emergency stop input is active."
                            else -> ""
                        }
                    }
                }
            }
        }
    }

    private data class InverterView(val code: Int, val derate: Health.Part?, val up: Boolean,
                                    val events: List<FaultLog.Event>)

    /** What the inverter's current error means for the drive. */
    private fun inverterErrorNote(name: String, derate: Health.Part?): String {
        val part = Health.tempFaultPart(name)
        fun temp(p: Health.Part) = Fmt.whole(
            if (p == Health.Part.MOTOR) LiveVehicleData.motorTempC.value else LiveVehicleData.battTempC.value)
        val what = if (part == Health.Part.MOTOR) "motor" else "inverter"
        return when {
            derate != null ->
                "${what.replaceFirstChar { it.uppercase() }} over-temperature, and it has stayed at " +
                "${temp(derate)} °C, at its warning level, so it's a real derate: it shows on the Drive screen."
            part != null ->
                "${what.replaceFirstChar { it.uppercase() }} over-temperature, but the $what reads " +
                "${temp(part)} °C, so the readings don't back it up (likely a sensor glitch). " +
                "Logged, not shown on the Drive screen."
            else ->
                "The inverter keeps its last error until it restarts, so this may already have " +
                "passed. Logged, not shown on the Drive screen."
        }
    }

    /** The newest logged errors (up to 10), one row each. */
    private fun showFaultLog(container: LinearLayout, events: List<FaultLog.Event>, liveCode: Int) {
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val bold = ResourcesCompat.getFont(this, R.font.archivo_semibold)
        events.take(10).forEachIndexed { i, e ->
            val part = Health.tempFaultPart(e.name)
            val title = "${e.name.ifBlank { "Code ${e.code}" }} (${e.code})" +
                (FaultLog.describe(e.name)?.let { " · $it" } ?: "")
            val detail = buildString {
                append(whenLogged(e.firstMs))
                append(" · ")
                append(when {
                    !e.ended && liveCode == e.code -> "still set"
                    e.ended && e.endMs > 0L -> "cleared after ${howLong(e.endMs - e.firstMs)}"
                    else -> "last seen ${whenLogged(e.lastMs)}"
                })
                if (e.peakMotorC != 0f || e.peakInverterC != 0f)
                    append(" · motor ${Fmt.whole(e.peakMotorC)} °C, inverter ${Fmt.whole(e.peakInverterC)} °C at most")
                when {
                    e.confirmed -> append(" · backed up by the readings, shown on Drive")
                    part != null -> append(" · not backed up by the readings")
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                contentDescription = "$title. $detail"
            }
            row.addView(TextView(this).apply {
                text = title
                typeface = bold
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(color(if (e.confirmed) R.color.crit else R.color.text))
            })
            row.addView(TextView(this).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.2f)
                setTextColor(color(R.color.text2))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt() })
            if (i > 0) container.addView(View(this).apply { setBackgroundColor(color(R.color.track)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()))
            container.addView(row)
        }
        if (events.size > 10) container.addView(TextView(this).apply {
            text = "and ${events.size - 10} older"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.text3))
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
    }

    /** "Today 14:32", "Yesterday 09:10" or "Mon 12 Oct 09:10". */
    private fun whenLogged(ms: Long): String {
        val day = java.util.Calendar.getInstance()
        fun dayOf(t: Long) = day.apply { timeInMillis = t }.let {
            it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }
        val today = dayOf(System.currentTimeMillis())
        val then = dayOf(ms)
        val clock = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(ms))
        return when {
            then == today -> "Today $clock"
            then == dayOf(System.currentTimeMillis() - 86_400_000L) -> "Yesterday $clock"
            else -> java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.US).format(java.util.Date(ms))
        }
    }

    private fun howLong(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min < 1 -> "under a minute"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    // ── Firmware update ──────────────────────────────────────────────────────

    private fun bindFirmwareUpdate() {
        val tvVer      = findViewById<TextView>(R.id.tvFwVersion)
        val tvReady    = findViewById<TextView>(R.id.tvFwReady)
        val btnPick    = findViewById<TextView>(R.id.btnPickFirmware)
        val btnPickApp = findViewById<TextView>(R.id.btnPickFirmwareApp)
        val tvFile     = findViewById<TextView>(R.id.tvFwFile)
        val tvFileInfo = findViewById<TextView>(R.id.tvFwFileInfo)
        val btnInstall = findViewById<TextView>(R.id.btnInstallFirmware)
        val pb         = findViewById<ProgressBar>(R.id.pbFirmware)
        val tvStatus   = findViewById<TextView>(R.id.tvFwStatus)
        val btnCancel  = findViewById<TextView>(R.id.btnCancelFirmware)
        val note       = findViewById<View>(R.id.fwNote)
        val progress   = findViewById<View>(R.id.fwProgress)

        // Primary: the built-in browser (internal storage, USB, SD) — works on
        // any ROM, including ones with no system picker.
        btnPick.setOnClickListener {
            if (FirmwareUpdater.state.value.busy) return@setOnClickListener
            openStorageBrowser()
        }

        // Secondary: hand off to the system picker or a file-manager app.
        btnPickApp.setOnClickListener {
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
                        "No app on this unit accepts file requests — use Choose file instead",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        btnInstall.setOnClickListener {
            val img = FirmwareUpdater.staged.value ?: return@setOnClickListener
            if (FirmwareUpdater.state.value.busy) return@setOnClickListener
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
            var wasBusy = false
            FirmwareUpdater.state.combine(FirmwareUpdater.staged) { s, img -> s to img }
                .collect { (s, img) ->
                    // Re-read the version once when an install ends (done,
                    // failed or cancelled) — not on every later emission.
                    if (wasBusy && !s.busy) startVersionRefresh(tvVer, tvReady)
                    wasBusy = s.busy

                    tvFile.typeface = ResourcesCompat.getFont(this@SettingsActivity,
                        if (img != null) R.font.ibm_plex_mono_medium else R.font.archivo_semibold)
                    if (img != null) {
                        tvFile.text = img.name
                        tvFileInfo.text = String.format(Locale.US, "%s · %,d KB · md5 %s…",
                            img.version, img.bytes.size / 1024, img.md5.take(8))
                        btnPick.text = "Choose another…"
                    } else {
                        tvFile.text = "No file chosen"
                        tvFileInfo.text = "Use the .ino.bin from Sketch → Export Compiled Binary."
                        btnPick.text = "Choose file…"
                    }

                    val canInstall = img != null && !s.busy
                    btnInstall.isEnabled = canInstall
                    btnInstall.alpha = if (canInstall) 1f else 0.4f
                    btnPick.isEnabled = !s.busy
                    btnPick.alpha = if (s.busy) 0.4f else 1f
                    btnPickApp.isEnabled = !s.busy
                    btnPickApp.alpha = if (s.busy) 0.4f else 1f

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
                    tvStatus.setTextColor(color(R.color.text))
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
                            tvStatus.setTextColor(color(R.color.accent))
                            s.message
                        }
                        is OtaState.Failed -> { tvStatus.setTextColor(color(R.color.crit)); s.message }
                    }
                    val showStatus = tvStatus.text.isNotEmpty()
                    progress.visibility = if (showStatus) View.VISIBLE else View.GONE
                    note.visibility = if (showStatus) View.GONE else View.VISIBLE
                    if (s.busy) navDetail(Section.FIRMWARE, "Updating…", R.color.warn)
                }
        }

        // Tap the version row to ask again (e.g. after "No reply").
        findViewById<View>(R.id.rowFwVersion).setOnClickListener { startVersionRefresh(tvVer, tvReady) }
        startVersionRefresh(tvVer, tvReady)
    }

    private var versionJob: Job? = null

    /** At most one version check at a time. */
    private fun startVersionRefresh(tv: TextView, ready: TextView) {
        if (versionJob?.isActive != true)
            versionJob = lifecycleScope.launch { refreshFirmwareVersion(tv, ready) }
    }

    private suspend fun refreshFirmwareVersion(tv: TextView, ready: TextView) {
        val monoFace = ResourcesCompat.getFont(this, R.font.ibm_plex_mono_medium)
        val textFace = ResourcesCompat.getFont(this, R.font.archivo_regular)
        // [version] is a version number (shown in mono) when the car answered,
        // otherwise a short message in the normal font.
        fun say(version: String, status: String = "", ok: Boolean = false, warn: Boolean = false,
                detail: String = version, isVersion: Boolean = ok || warn) {
            tv.text = version
            tv.typeface = if (isVersion) monoFace else textFace
            tv.textSize = if (isVersion) 18f else 16f
            tv.setTextColor(color(if (ok || warn) R.color.text else R.color.text2))
            ready.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
            ready.text = status
            ready.setTextColor(color(if (warn) R.color.warn else R.color.accent))
            ready.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (ok) R.drawable.ic_check else if (warn) R.drawable.ic_warning else 0, 0, 0, 0)
            ready.compoundDrawableTintList = ColorStateList.valueOf(
                color(if (warn) R.color.warn else R.color.accent))
            navDetail(Section.FIRMWARE, detail, if (ok || warn) null else R.color.text3)
        }
        if (FirmwareUpdater.state.value.busy) { say("Update in progress…", detail = "Updating…"); return }
        say("Checking…", detail = "…")
        when (val r = FirmwareUpdater.queryVersion()) {
            FwQuery.Busy -> say("Update in progress…", detail = "Updating…")
            FwQuery.NotConnected -> say("Not connected to the car", detail = Fmt.NONE)
            FwQuery.NoAnswer -> say("No reply — tap to try again", detail = Fmt.NONE)
            FwQuery.TooOld -> say("Older than v7.1", "Flash v7.1 once over USB", warn = true,
                detail = "< v7.1", isVersion = false)
            is FwQuery.Info -> {
                val info = r.json
                val v = info.optString("version").ifEmpty { "?" }
                when {
                    !info.optBoolean("ota") ->
                        say(v, "No OTA partition — reflash once over USB", warn = true)
                    !info.optBoolean("rollback") ->
                        say(v, "Ready (no automatic rollback)", ok = true)
                    else -> say(v, "Ready for updates", ok = true)
                }
            }
        }
    }

    // ── Car interface diagnostics (firmware v7.3+) ───────────────────────────

    private var diagJob: Job? = null

    /** One line of the report: what, the finding, and whether to look at it. */
    private data class DiagLine(val key: String, val value: String, val problem: Boolean = false)

    private fun bindDiagnostics() {
        val tv   = findViewById<TextView>(R.id.tvDiag)
        val rows = findViewById<LinearLayout>(R.id.diagRows)
        val btn  = findViewById<TextView>(R.id.btnRunDiag)
        navDetail(Section.DIAG, "Not run", R.color.text3)
        btn.setOnClickListener {
            if (diagJob?.isActive == true) return@setOnClickListener
            diagJob = lifecycleScope.launch {
                btn.alpha = 0.4f
                btn.text = "Checking…"
                try {
                    when (val r = runDiag()) {
                        is String -> {
                            // The car didn't answer, but the app's own checks on
                            // this trip still stand.
                            tv.visibility = View.VISIBLE
                            tv.text = r
                            showDiagRows(rows, tripCheckLines())
                        }
                        is List<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val lines = (r as List<DiagLine>) + tripCheckLines()
                            tv.visibility = View.GONE
                            showDiagRows(rows, lines)
                            val issues = lines.count { it.problem }
                            navDetail(Section.DIAG,
                                if (issues == 0) "All OK" else if (issues == 1) "1 to check" else "$issues to check",
                                if (issues == 0) null else R.color.warn)
                        }
                    }
                } finally {
                    btn.alpha = 1f
                    btn.text = "Run check"
                }
            }
        }
    }

    /**
     * The app's own checks on this trip ([TripCheck]): is the distance right
     * (the car's speed signal against GPS), is the energy right (against the
     * battery-% drop), and did readings arrive without a value. Each figure the
     * km/kWh is built from, next to something independent of it.
     */
    private fun tripCheckLines(): List<DiagLine> {
        val c = LiveTripData.check.value
            ?: return listOf(DiagLine("This trip", "No readings yet — drive a little first."))
        fun one(v: Float) = String.format(Locale.US, "%.1f", v)
        fun two(v: Float) = String.format(Locale.US, "%.2f", v)
        fun pct(n: Int, of: Int) = if (of > 0) Math.round(n * 100f / of) else 0
        val lines = mutableListOf<DiagLine>()

        val off = if (c.gpsKm > 0f) c.liveKm / c.gpsKm - 1f else 0f
        val distBad = c.gpsKm >= 2f && Math.abs(off) > 0.08f
        lines += DiagLine("Distance", buildString {
            append("${one(c.liveKm)} km from the car's speed")
            if (c.gpsKm >= 0.5f) {
                append(", ${one(c.gpsKm)} km by GPS")
                if (distBad) append(" — the speed reads ${Math.round(Math.abs(off) * 100)} % " +
                                    (if (off > 0f) "high" else "low"))
            } else append(" · no GPS distance yet")
            if (c.replayedKm >= 0.05f)
                append(" · plus ${one(c.replayedKm)} km the car replayed from before the link was up")
        }, problem = distBad)

        // A lone 0 A between two drawing readings is a current that didn't
        // arrive (a coast eases down first); more than 2 % of them is worth a look.
        val blipsBad = c.movingReadings >= 20 && c.zeroBlips * 50 > c.movingReadings
        lines += DiagLine("Readings",
            "${c.movingReadings} while moving · no pack voltage on ${pct(c.noVoltage, c.movingReadings)} % · " +
            "exactly 0 A on ${pct(c.zeroCurrent, c.movingReadings)} % · " +
            "a lone 0 A mid-pull on ${pct(c.zeroBlips, c.movingReadings)} %",
            problem = (c.movingReadings >= 20 && c.noVoltage * 50 > c.movingReadings) || blipsBad)

        val share = if (c.drawnKwh > 0.05f) " (${Math.round(c.regenKwh / c.drawnKwh * 100)} %)" else ""
        lines += DiagLine("Energy",
            "${two(c.drawnKwh)} kWh drawn · ${two(c.regenKwh)} back from regen$share · ${two(c.netKwh)} kWh net" +
            // No negative current at all over a real drive: regen is off in the
            // inverter, or the current is sent without its sign.
            if (c.drawnKwh >= 0.3f && c.regenKwh < 0.005f) " · no reading below 0 A: regen off, or not sent" else "")

        // Pack sag from the same readings the energy uses. About PACK_R_OHM is
        // what an 84-cell pack shows; well above means the current reads low
        // (or the pack and its cabling have high resistance).
        if (c.sagPairs >= 20 && !c.sagOhm.isNaN()) {
            val ratio = c.sagOhm / PACK_R_OHM
            lines += DiagLine("Pack sag",
                "${one(c.sagOhm * 100f)} V lost per 100 A drawn, from ${c.sagPairs} quick load changes " +
                "(about ${one(PACK_R_OHM * 100f)} expected)" + when {
                    ratio > 1.5f -> " — ${one(ratio)}× too much for the current shown: the current likely " +
                                    "reads low by about that, or the pack wiring has high resistance"
                    ratio < 0.67f -> " — too little for the current shown: the current may read high"
                    else -> " — matches the current readings"
                },
                problem = ratio > 1.5f || ratio < 0.67f)
        } else lines += DiagLine("Pack sag",
            "Not measured yet — needs a few quick pulls away (${c.sagPairs} of 20 load changes so far).")

        if (c.socStart >= 0 && c.socNow >= 0) {
            val drop = c.socStart - c.socNow
            val socKwh = drop / 100f * designCapacity(this) * soh(this)
            val ratio = if (socKwh > 0.05f) c.socSpanKwh / socKwh else 1f
            lines += DiagLine("Battery %",
                "${c.socStart} → ${c.socNow} %, about ${two(socKwh)} kWh by pack voltage; " +
                "${two(c.socSpanKwh)} kWh measured over the same stretch",
                problem = drop >= 8 && (ratio < 0.6f || ratio > 1.5f))
        }
        return lines
    }

    private fun showDiagRows(container: LinearLayout, lines: List<DiagLine>) {
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val mono = ResourcesCompat.getFont(this, R.font.ibm_plex_mono_regular)
        lines.forEachIndexed { i, l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (42 * dp).toInt()
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                contentDescription = "${l.key}: ${l.value}"
            }
            row.addView(ImageView(this).apply {
                setImageResource(if (l.problem) R.drawable.ic_warning else R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(color(if (l.problem) R.color.warn else R.color.accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()))
            row.addView(TextView(this).apply {
                text = l.key
                typeface = mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(color(R.color.text3))
            }, LinearLayout.LayoutParams((112 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = (12 * dp).toInt() })
            row.addView(TextView(this).apply {
                text = l.value
                typeface = mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(0f, 1.25f)
                setTextColor(color(if (l.problem) R.color.warn_soft else R.color.text))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                // A long key ("Fuel needle") ran into its value at large font sizes.
                .apply { marginStart = (8 * dp).toInt() })
            container.addView(row)
            if (i < lines.lastIndex) container.addView(View(this).apply {
                setBackgroundColor(color(R.color.line))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()))
        }
        container.visibility = View.VISIBLE
    }

    /**
     * Sends DIAG and turns the reply into plain lines. Returns a String when
     * there is no report to show (not connected, no reply, firmware too old),
     * otherwise a list of [DiagLine].
     */
    private suspend fun runDiag(): Any {
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
                        "this report. Install v7.3 under Firmware."
                if (!svc.isConnected()) return@withLock "The link dropped — try again."
                delay(50)
            }
            "No reply — the link may be busy syncing history. Try again in a minute."
        }
    }

    private fun formatDiag(j: JSONObject): List<DiagLine> {
        val lines = mutableListOf<DiagLine>()

        val up = j.optLong("up")
        val upText = if (up >= 3600) "${up / 3600} h ${(up % 3600) / 60} min"
                     else "${up / 60} min ${up % 60} s"
        lines += DiagLine("Firmware",
            "${j.optString("fw")} · running $upText · start #${j.optLong("boots")}")

        val reset = j.optString("reset")
        lines += DiagLine("Last restart", when (reset) {
            "power-on"   -> "power-on (normal key-on start)"
            "software"   -> "software restart (normal after a firmware update)"
            "brownout"   -> "BROWNOUT — its 12 V supply dipped too low. Check the feed and " +
                            "ground; a dip at key-on restarts it and the gauges drop out."
            "crash"      -> "CRASH — a firmware fault restarted it. Worth reporting."
            "watchdog", "watchdog-int", "watchdog-task" ->
                            "WATCHDOG — the firmware stalled and was restarted."
            "reset-pin"  -> "reset line (EN pin)"
            "deep-sleep" -> "wake from sleep"
            else         -> reset.ifEmpty { "unknown" }
        }, problem = reset == "brownout" || reset == "crash" || reset.startsWith("watchdog"))

        val canUp   = j.optLong("canup")
        val firstTx = j.optLong("firsttx")
        val busState = j.optString("bus")
        lines += if (busState == "not-started")
            DiagLine("CAN", "FAILED TO START — check the CAN transceiver wiring.", problem = true)
        else DiagLine("CAN", "up in $canUp ms · first gauge frame " +
             (if (firstTx > 0) "$firstTx ms" else "— (none sent yet)"))

        if (busState != "not-started") {
            val bus = when (busState) {
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
            lines += DiagLine("Bus", bus +
                if (errs.isEmpty()) " · no errors" else " · " + errs.joinToString(", "),
                problem = busState != "running" || errs.isNotEmpty())
        }

        val dups = listOf("0x316 (rpm)" to j.optLong("dup316"),
                          "0x329 (temp)" to j.optLong("dup329"),
                          "0x43F (gear)" to j.optLong("dup43f")).filter { it.second > 0 }
        lines += if (dups.isEmpty()) DiagLine("Gauge frames", "only this interface is sending them")
        else DiagLine("Gauge frames", "ANOTHER MODULE is also sending " +
             dups.joinToString(", ") { "${it.first} ×${it.second}" } +
             ". Two senders make the cluster flicker or freeze — find and disable the other one.",
             problem = true)

        lines += DiagLine("Fuel needle", when (j.optString("fuel")) {
            "live"    -> "following pack voltage"
            "saved"   -> "holding the last saved level until the inverter's DC link is up"
            "default" -> "half-scale default (no level saved yet — it learns on the first drive)"
            else      -> j.optString("fuel", "unknown")
        })

        // From v7.6 the car says why the card isn't logging; before, only yes/no.
        val sd = j.optBoolean("sd")
        val sdGb = j.optLong("sdmb") * 1_048_576.0 / 1e9
        val sdText = when (j.optString("sdst")) {
            "ok"       -> "OK" + if (sdGb >= 0.5) String.format(Locale.US, " · %.0f GB card", sdGb) else ""
            "noanswer" -> "no card answering — push it in until it clicks; if it is in, try another card"
            "notfat"   -> "card found but can't be read — format it FAT32 with a Master Boot Record"
            "nowrite"  -> "card read, but the log file can't be created — card full or damaged"
            "lost"     -> "stopped after a failed write — card loose or failing; restart the car to retry"
            "flaky"    -> "card answers only some of the time — reseat it or try another card"
            else       -> if (sd) "OK" else "not detected"
        }
        lines += DiagLine("SD log", "$sdText · free memory ${j.optLong("heap")} KB", problem = !sd)
        return lines
    }

    // ── Startup ──────────────────────────────────────────────────────────────

    private fun bindAutoStart() {
        val sw = findViewById<SwitchCompat>(R.id.btnAutoStart)
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sw.isChecked = autoStartEnabled(this)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(PREF_AUTOSTART, on).apply()
            renderOverlayState()
        }
        // The whole row toggles, not just the small switch.
        findViewById<View>(R.id.rowAutoStart).setOnClickListener { sw.toggle() }
        findViewById<TextView>(R.id.btnOverlayPermission).setOnClickListener {
            openOverlaySettings()
        }
        renderOverlayState()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system "Display over other apps" screen.
        renderOverlayState()
    }

    private fun renderOverlayState() {
        val tv  = findViewById<TextView>(R.id.tvOverlayState) ?: return
        val iv  = findViewById<ImageView>(R.id.ivOverlayState) ?: return
        val btn = findViewById<TextView>(R.id.btnOverlayPermission) ?: return
        val on = autoStartEnabled(this)
        val granted = android.provider.Settings.canDrawOverlays(this)
        tv.text = when {
            !on     -> "The app opens only when you tap it."
            granted -> "Opens when the head unit starts. “Display over other apps” is allowed."
            else    -> "Opens when the head unit starts — but Android may block that until " +
                       "“Display over other apps” is allowed for this app."
        }
        when {
            !on -> { iv.setImageResource(R.drawable.ic_info); iv.imageTintList = ColorStateList.valueOf(color(R.color.text3)) }
            granted -> { iv.setImageResource(R.drawable.ic_check); iv.imageTintList = ColorStateList.valueOf(color(R.color.accent)) }
            else -> { iv.setImageResource(R.drawable.ic_warning); iv.imageTintList = ColorStateList.valueOf(color(R.color.warn)) }
        }
        btn.visibility = if (on && !granted) View.VISIBLE else View.GONE
        navDetail(Section.STARTUP, if (on) "On" else "Off",
            if (on && !granted) R.color.warn else if (on) null else R.color.text3)
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

    // ── Bluetooth ────────────────────────────────────────────────────────────

    private fun bindDevice() {
        val addr = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(MainActivity.PREF_LAST_DEVICE, null)
        val name = addr?.let { a ->
            runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(a)?.name
            }.getOrNull()
        }
        findViewById<TextView>(R.id.tvDevName).text = name ?: "Car interface"
        findViewById<TextView>(R.id.tvDevAddress).text = addr ?: "No device saved"

        findViewById<TextView>(R.id.btnChangeDevice).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Change Bluetooth device?")
                .setMessage("Disconnects from ${name ?: "the car interface"} and opens the device list.")
                .setPositiveButton("Change") { _, _ ->
                    // Clear saved device address → breaks retry loop → returns to picker
                    getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                        .remove(MainActivity.PREF_LAST_DEVICE).apply()
                    // Restart app from MainActivity
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── File access ──────────────────────────────────────────────────────────

    /** Gets whatever storage access this Android version needs, then browses. */
    private fun openStorageBrowser() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+: a .bin isn't media, so it takes "All files access".
            if (!android.os.Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Allow file access")
                    .setMessage("To read the firmware file from USB or storage, allow " +
                        "\"All files access\" for this app on the next screen, then come back " +
                        "and tap Choose file again.")
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
                                    "use \"Or open it with a file manager app\" instead",
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
}

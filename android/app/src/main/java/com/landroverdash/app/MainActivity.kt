package com.landroverdash.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

/**
 * Connect — picks the car interface from the head unit's paired Bluetooth
 * devices, or goes straight to the dashboard when one is saved.
 *
 * Extends WattsonActivity for the full-screen mode only; there's no side rail
 * here, because nothing else works until a device is chosen.
 */
class MainActivity : WattsonActivity() {

    companion object {
        const val PREF_LAST_DEVICE = "last_device_address"
        /**
         * The last device the dashboard actually connected to. Unlike
         * PREF_LAST_DEVICE it survives "Change Bluetooth device", so the
         * picker can still put that device first and mark it "last used".
         */
        const val PREF_LAST_USED   = "last_used_device_address"
        /** Set by BootReceiver on the intent it launches us with. */
        const val EXTRA_FROM_BOOT  = "from_boot"

        /** Names the ESP32 car interface is known to advertise. */
        private val CAR_NAMES = listOf("freelander", "wattson", "landrover", "land rover")

        fun looksLikeCar(name: String?): Boolean =
            name != null && CAR_NAMES.any { name.contains(it, ignoreCase = true) }
    }

    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var listView:   ListView
    private lateinit var statusText: TextView
    private lateinit var statusDot:  View
    private val deviceList = mutableListOf<BluetoothDevice>()
    // Lazy: the adapter needs a LayoutInflater, which isn't available until
    // the activity is created.
    private val deviceAdapter by lazy { DeviceAdapter() }

    /** Status line under the title; [problem] turns the dot amber. */
    private fun status(text: String, problem: Boolean = false) {
        statusText.text = text
        statusDot.setBackgroundResource(if (problem) R.drawable.dot_halo_warn else R.drawable.dot_halo)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) tryAutoConnectOrShowPicker()
        else status("Bluetooth permission denied — allow it for Wattson in Android Settings", problem = true)
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The result can arrive while the adapter is still STATE_TURNING_ON, so a
        // single isEnabled read here is a race: at a cold car start it reads false
        // a fraction of a second before Bluetooth finishes coming up, and the app
        // used to latch "must be enabled" forever, needing a restart. Wait for
        // STATE_ON instead of failing on that one read.
        waitForBluetoothOn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened again (launcher tap, or the boot receiver) while the app is
        // already running: don't build a second picker → second dashboard that
        // would fight the first for the ESP32's single Bluetooth connection.
        // Finishing here lets Android bring the running task to the front.
        // Only for those two entry points — the dashboard's own "choose device"
        // path opens this screen on top of itself on purpose.
        val fromLauncherOrBoot = intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true ||
                                 intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        if (!isTaskRoot && fromLauncherOrBoot) {
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        listView   = findViewById(R.id.deviceList)
        statusText = findViewById(R.id.statusText)
        statusDot  = findViewById(R.id.statusDot)
        listView.adapter = deviceAdapter
        findViewById<View>(R.id.btnBluetoothSettings).setOnClickListener { openBluetoothSettings() }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            status("This device has no Bluetooth, so it can't connect to the car", problem = true)
            return
        }
        btAdapter = adapter

        // Permission FIRST, then the enable prompt. On API 31+ the
        // ACTION_REQUEST_ENABLE intent itself requires BLUETOOTH_CONNECT, so
        // asking to turn Bluetooth on before holding it was silently denied —
        // and because the permission request only lived on the other branch,
        // the dialog never appeared at all. First run with Bluetooth off was a
        // dead end reading "Bluetooth must be enabled" forever.
        checkPermissionsAndLoad()

        listView.setOnItemClickListener { _, _, pos, _ ->
            connectTo(deviceList[pos])
        }
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Only CONNECT is needed — the app talks to already-paired devices,
            // it never scans (SCAN was also dropped from the manifest).
            val missing = listOf(
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                status("Asking for Bluetooth permission…")
                permissionLauncher.launch(missing.toTypedArray())
                return
            }
        }
        // Permission is held by the time we get here, so the enable prompt will
        // actually be shown rather than denied.
        when (btAdapter.state) {
            BluetoothAdapter.STATE_ON -> tryAutoConnectOrShowPicker()
            // Already coming up — the usual case at a car start, where the head
            // unit powers Bluetooth on as the app launches. Don't prompt; wait.
            BluetoothAdapter.STATE_TURNING_ON -> waitForBluetoothOn()
            else -> {
                // Early in boot Android 10 can report OFF before it starts the
                // radio itself. A system "turn on Bluetooth?" dialog then sat
                // waiting for a tap. Up to Android 12 the app may simply switch
                // it on (BLUETOOTH_ADMIN / CONNECT, both declared), so do that
                // and wait; only fall back to the dialog where it's not allowed.
                @Suppress("DEPRECATION", "MissingPermission")
                val switching = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
                    runCatching { btAdapter.enable() }.getOrDefault(false)
                if (switching) {
                    waitForBluetoothOn()
                } else {
                    status("Turning Bluetooth on…")
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
    }

    // ── Wait for the adapter to reach STATE_ON rather than failing on a single
    //    isEnabled read. On a head unit, Bluetooth finishes turning on a moment
    //    after boot, so we listen for that transition instead of giving up. ────
    private var waitingForBt = false
    private val btWait = Handler(Looper.getMainLooper())

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                btAdapter.state == BluetoothAdapter.STATE_ON) {
                stopWaitingForBluetooth()
                tryAutoConnectOrShowPicker()
            }
        }
    }

    // Never a dead end any more. After 12 s the message changes, but the
    // listener stays registered, so Bluetooth coming on at 13 s — or at 2 min,
    // or after the driver switches it on by hand — still carries straight on.
    private val btWaitTimeout = Runnable {
        if (btAdapter.isEnabled) {
            stopWaitingForBluetooth()
            tryAutoConnectOrShowPicker()
        } else {
            status("Still waiting for Bluetooth. It connects as soon as Bluetooth is on " +
                "(Open Bluetooth settings if it stays off).", problem = true)
        }
    }

    private fun waitForBluetoothOn() {
        if (btAdapter.isEnabled) { tryAutoConnectOrShowPicker(); return }
        if (!waitingForBt) {
            waitingForBt = true
            registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        status("Waiting for Bluetooth…")
        btWait.removeCallbacks(btWaitTimeout)
        btWait.postDelayed(btWaitTimeout, 12_000)
    }

    private fun stopWaitingForBluetooth() {
        btWait.removeCallbacks(btWaitTimeout)
        if (waitingForBt) {
            runCatching { unregisterReceiver(btStateReceiver) }
            waitingForBt = false
        }
    }

    private fun tryAutoConnectOrShowPicker() {
        // Try to auto-connect to the last known device.
        val savedAddress = getSharedPreferences("prefs", MODE_PRIVATE)
            .getString(PREF_LAST_DEVICE, null)

        // Resolve the MAC directly instead of searching bondedDevices. That set
        // comes back EMPTY for a second or two while the Bluetooth stack is
        // still coming up — which is exactly what happens when the car starts
        // and the phone is busy connecting to the car's own audio. Searching it
        // then missed the saved device and dumped the user into the picker,
        // fixed only by restarting the app once the stack had settled.
        // getRemoteDevice() is a pure address lookup and never has that gap.
        if (savedAddress != null && BluetoothAdapter.checkBluetoothAddress(savedAddress)) {
            val device = runCatching { btAdapter.getRemoteDevice(savedAddress) }.getOrNull()
            if (device != null) {
                status("Connecting…")
                launchDashboard(device)
                return
            }
        }

        // No saved device — show the picker.
        loadPairedDevices()
    }

    /** Re-poll while the paired list is empty; see the note above about the
     *  Bluetooth stack reporting nothing for the first moment after it starts. */
    private val pairedRetry = Handler(Looper.getMainLooper())
    private var pairedTries = 0

    private fun loadPairedDevices() {
        deviceList.clear()
        // The car interface first (the one last used before anything else),
        // then everything else by name.
        val lastUsed = lastUsedAddress()
        deviceList.addAll(btAdapter.bondedDevices.sortedWith(
            compareByDescending<BluetoothDevice> { it.address == lastUsed }
                .thenByDescending { looksLikeCar(safeName(it)) }
                .thenBy { (safeName(it) ?: it.address).lowercase() }))
        deviceAdapter.notifyDataSetChanged()
        if (deviceList.isNotEmpty()) {
            pairedTries = 0
            val car = deviceList.firstOrNull { isCar(it) }
            status(if (car != null) "Tap ${safeName(car) ?: "the car interface"} to connect"
                   else "Tap the car interface to connect")
            return
        }
        if (pairedTries < 10) {                    // ~10 s of grace
            pairedTries++
            status("Looking for paired devices…")
            pairedRetry.postDelayed({ loadPairedDevices() }, 1_000)
        } else {
            status("No paired devices. Pair Freelander EV in Bluetooth settings first.",
                problem = true)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from pairing the car in Bluetooth settings: look again, instead
        // of leaving "No paired devices" up until the app is restarted.
        if (::btAdapter.isInitialized && deviceList.isEmpty() && pairedTries >= 10 &&
            btAdapter.isEnabled) {
            pairedTries = 0
            loadPairedDevices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairedRetry.removeCallbacksAndMessages(null)
        stopWaitingForBluetooth()
    }

    private fun connectTo(device: BluetoothDevice) {
        status("Connecting to ${safeName(device) ?: device.address}…")
        launchDashboard(device)
    }

    private fun launchDashboard(device: BluetoothDevice) {
        val intent = Intent(this, DashboardActivity::class.java)
        intent.putExtra("device_address", device.address)
        startActivity(intent)
        finish() // close picker — DashboardActivity handles all retries itself
    }

    private fun lastUsedAddress(): String? =
        getSharedPreferences("prefs", MODE_PRIVATE).getString(PREF_LAST_USED, null)

    /** The car interface: the device last used, or one named like it. */
    private fun isCar(d: BluetoothDevice): Boolean =
        d.address == lastUsedAddress() || looksLikeCar(safeName(d))

    /** BluetoothDevice.name can throw without permission; never crash the list on it. */
    private fun safeName(d: BluetoothDevice): String? =
        runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "This unit has no Bluetooth settings screen", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Paired devices, car interface first and accented. */
    private inner class DeviceAdapter : BaseAdapter() {
        private val inflater: LayoutInflater by lazy { LayoutInflater.from(this@MainActivity) }
        private val density by lazy { resources.displayMetrics.density }

        override fun getCount() = deviceList.size
        override fun getItem(position: Int) = deviceList[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: inflater.inflate(R.layout.item_device, parent, false)
            val d = deviceList[position]
            val car = isCar(d)
            val name = safeName(d) ?: "Unnamed device"
            val lastUsed = d.address == lastUsedAddress()

            v.layoutParams = v.layoutParams.apply { height = ((if (car) 96 else 80) * density).toInt() }
            v.setBackgroundResource(if (car) R.drawable.bg_device_row_car else R.drawable.bg_device_row)

            val box = v.findViewById<View>(R.id.deviceIconBox)
            val boxSize = ((if (car) 56 else 48) * density).toInt()
            box.layoutParams = box.layoutParams.apply { width = boxSize; height = boxSize }
            box.setBackgroundResource(if (car) R.drawable.bg_icon_box else R.drawable.bg_icon_box_muted)
            v.findViewById<ImageView>(R.id.deviceIcon).imageTintList =
                android.content.res.ColorStateList.valueOf(color(if (car) R.color.accent else R.color.text3))
            v.findViewById<ImageView>(R.id.deviceChevron).imageTintList =
                android.content.res.ColorStateList.valueOf(color(if (car) R.color.accent else R.color.text3))

            v.findViewById<TextView>(R.id.deviceName).apply {
                text = name
                textSize = if (car) 21f else 19f
                setTextColor(if (car) color(R.color.text) else 0xFFD5DCD9.toInt())
            }
            v.findViewById<View>(R.id.deviceTag).visibility = if (car) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.deviceAddress).text =
                if (lastUsed) "${d.address} · last used" else d.address
            v.contentDescription = if (car) "$name, car interface. Tap to connect" else "$name. Tap to connect"
            return v
        }
    }
}

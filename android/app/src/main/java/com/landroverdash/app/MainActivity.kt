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
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_LAST_DEVICE = "last_device_address"
        /** Set by BootReceiver on the intent it launches us with. */
        const val EXTRA_FROM_BOOT  = "from_boot"
    }

    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var listView:   ListView
    private lateinit var statusText: TextView
    private val deviceList = mutableListOf<BluetoothDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) tryAutoConnectOrShowPicker()
        else statusText.text = "Bluetooth permission denied — please grant in phone Settings"
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

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            statusText.text = "This device has no Bluetooth — cannot connect to the car"
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
                statusText.text = "Requesting Bluetooth permission…"
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
                    statusText.text = "Turning Bluetooth on…"
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
            statusText.text = "Still waiting for Bluetooth… it will connect as soon as " +
                "Bluetooth is on (Settings → Bluetooth if it stays off)"
        }
    }

    private fun waitForBluetoothOn() {
        if (btAdapter.isEnabled) { tryAutoConnectOrShowPicker(); return }
        if (!waitingForBt) {
            waitingForBt = true
            registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        statusText.text = "Waiting for Bluetooth…"
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
                statusText.text = "Auto-connecting…"
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
        deviceList.addAll(btAdapter.bondedDevices)
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            deviceList.map { it.name ?: it.address }
        )
        if (deviceList.isNotEmpty()) {
            pairedTries = 0
            statusText.text = "Tap 'Freelander EV' to connect"
            return
        }
        if (pairedTries < 10) {                    // ~10 s of grace
            pairedTries++
            statusText.text = "Looking for paired devices…"
            pairedRetry.postDelayed({ loadPairedDevices() }, 1_000)
        } else {
            statusText.text =
                "No paired devices — pair 'Freelander EV' in Bluetooth settings first"
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
        statusText.text = "Connecting to ${device.name}…"
        launchDashboard(device)
    }

    private fun launchDashboard(device: BluetoothDevice) {
        val intent = Intent(this, DashboardActivity::class.java)
        intent.putExtra("device_address", device.address)
        startActivity(intent)
        finish() // close picker — DashboardActivity handles all retries itself
    }
}

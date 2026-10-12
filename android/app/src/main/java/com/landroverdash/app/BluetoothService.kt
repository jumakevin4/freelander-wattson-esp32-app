package com.landroverdash.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

/**
 * Manages BT SPP connection to the ESP32.
 *
 * Sync protocol (received on connect):
 *   {"type":"sync_start","count":N}   — N buffered records follow
 *   {"type":"data","ts":ms,...}        — buffered record with ESP32 uptime timestamp
 *   {"type":"sync_end"}                — sync complete
 *   {no "type" field}                  — normal real-time packet
 *
 * Buffered records are emitted as CarData with isSynced=true. From firmware
 * v7.3 live packets keep arriving DURING a sync (interleaved), so consumers
 * must keep the two timelines apart — the ViewModel does.
 */
private const val TAG = "EvBluetooth"

class BluetoothService {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

    /** True while a connect attempt is in flight or a connection is live. Guards
     *  against overlapping reconnects stacking up sockets when the link flaps. */
    @Volatile private var active = false

    // DROP_OLDEST, not the default SUSPEND. The socket reader runs inside
    // connect(), so a slow collector used to suspend emit(), which stopped the
    // reader, which left `active` stuck true — and with `active` stuck no
    // reconnect could ever be attempted. A wedged app that had to be force-quit
    // was the visible result.
    //
    // Dropping a replayed record is a far smaller price than deadlocking the
    // link. Live packets arrive at 2 Hz against a 4096 buffer, so in normal
    // running nothing is ever dropped.
    private val _dataFlow = MutableSharedFlow<CarData>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val dataFlow: SharedFlow<CarData> = _dataFlow

    // extraBufferCapacity + DROP_OLDEST so these NEVER block or fail to emit.
    // With the default one-slot buffer, emit() suspended whenever a collector
    // was slow, and disconnect()'s tryEmit() silently returned false — the
    // state change simply vanished. Connection state is the one thing that
    // must not go missing, and a stalled emit inside connect() also held the
    // `active` guard set, blocking the retry that would have recovered it.
    private val _connectionState = MutableSharedFlow<ConnectionState>(
        replay = 1, extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val connectionState: SharedFlow<ConnectionState> = _connectionState

    private val _syncState = MutableSharedFlow<SyncState>(
        replay = 1, extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val syncState: SharedFlow<SyncState> = _syncState

    /**
     * Reasons the ESP32 refused a drive-mode change (e.g. vehicle moving).
     *
     * replay = 1 and DROP_OLDEST, both deliberate. This is emitted from inside
     * the socket reader, so with the default SUSPEND a handful of refusals
     * against a slow collector would stall the reader and wedge the link the
     * same way dataFlow used to. replay carries a refusal across a trip to
     * Settings or Details, which with replay = 0 was dropped and never shown.
     */
    private val _modeRefusals = MutableSharedFlow<String>(
        replay = 1, extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val modeRefusals: SharedFlow<String> = _modeRefusals

    /**
     * Firmware-update replies ("ota") and command refusals ("error").
     *
     * A Channel, not a SharedFlow: the updater sends a chunk and THEN waits for
     * its ack, and a SharedFlow with no collector at the instant the ack arrives
     * would drop it. The channel holds it until received. trySend from the
     * reader never blocks; with one chunk in flight it can't fill.
     */
    val otaReplies = Channel<JSONObject>(capacity = 64)

    /** Throw away anything left over from an earlier attempt. */
    fun drainOtaReplies() { while (otaReplies.tryReceive().isSuccess) { } }

    /** Last {"type":"fw"} reply — firmware version and OTA capability. */
    private val _firmwareInfo = MutableStateFlow<JSONObject?>(null)
    val firmwareInfo: StateFlow<JSONObject?> = _firmwareInfo

    /**
     * Last {"type":"error"} reply — a command the firmware refused or doesn't
     * know. Watched by identity, so VERSION/DIAG can recognise "too old" without
     * taking anything off [otaReplies], which belongs to the updater.
     */
    private val _lastRefusal = MutableStateFlow<JSONObject?>(null)
    val lastRefusal: StateFlow<JSONObject?> = _lastRefusal

    /** Last {"type":"diag"} reply (firmware v7.3+): CAN health, reset reason… */
    private val _diagInfo = MutableStateFlow<JSONObject?>(null)
    val diagInfo: StateFlow<JSONObject?> = _diagInfo

    /** History replay progress: (records received, records announced). */
    private val _syncProgress = MutableStateFlow(0 to 0)
    val syncProgress: StateFlow<Pair<Int, Int>> = _syncProgress

    fun isConnected(): Boolean = synchronized(this) { socket?.isConnected == true }

    /** True while raw firmware bytes are streaming; blocks [sendCommand]. */
    @Volatile var rawMode = false

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    enum class SyncState       { IDLE, SYNCING, COMPLETE }

    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        // Ignore a new attempt while one is already in progress or live.
        synchronized(this@BluetoothService) {
            if (active) return@withContext
            active = true
        }
        _connectionState.emit(ConnectionState.CONNECTING)

        // Owned locally, and only published to the shared field once it belongs
        // to this attempt. Two overlapping attempts both writing `socket` meant
        // a stale attempt's cleanup could close the live one's socket.
        var local: BluetoothSocket? = null
        try {
            // A discovery scan in progress (common while a head unit boots and
            // looks for the phone) slows an RFCOMM connect badly or fails it.
            // Cancel it where that is allowed: up to API 30 it needs only
            // BLUETOOTH_ADMIN, which is declared. From 31 it needs BLUETOOTH_SCAN,
            // which this app deliberately doesn't request, so skip it there.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION", "MissingPermission")
                runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
            }
            local = device.createRfcommSocketToServiceRecord(SPP_UUID)
            synchronized(this@BluetoothService) { socket = local }
            local.connect()

            lastError = null
            Log.i(TAG, "connected")
            _connectionState.emit(ConnectionState.CONNECTED)
            _syncState.emit(SyncState.IDLE)

            val reader = BufferedReader(InputStreamReader(local.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { parseLine(it) }
            }
        } catch (e: CancellationException) {
            throw e                       // cancellation is not a link failure
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT revoked (or never granted on this launch path).
            // Retrying cannot help, so say so rather than failing every 5 s in
            // silence — which was indistinguishable from a parked car.
            lastError = "Bluetooth permission denied"
            Log.e(TAG, "connect: missing BLUETOOTH_CONNECT", e)
            _connectionState.emit(ConnectionState.ERROR)
        } catch (e: IOException) {
            lastError = e.message ?: "connect failed"
            Log.w(TAG, "connect: ${e.message}")
            _connectionState.emit(ConnectionState.ERROR)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "connect: unexpected", e)
            _connectionState.emit(ConnectionState.ERROR)
        } finally {
            closeSocket(local, ownsGuard = true)
        }
    }

    /** Last failure reason, for the status line. Null once connected. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Release this attempt's socket and clear the guard.
     *
     * Only clears the shared field if it still points at [mine], so a late
     * cleanup from an abandoned attempt cannot tear down a newer connection.
     *
     * [ownsGuard] must be false for an external disconnect(). An attempt in
     * flight has not yet published its socket, so `socket` is null and a naive
     * identity test would match — clearing `active` underneath a live attempt
     * and letting the supervisor start a second, overlapping RFCOMM connect.
     */
    private fun closeSocket(mine: BluetoothSocket?, ownsGuard: Boolean) {
        try { mine?.close() } catch (_: Exception) {}
        synchronized(this@BluetoothService) {
            if (mine != null && socket === mine) socket = null
            if (ownsGuard) active = false
        }
        // A link that drops mid-replay never sends sync_end. Without this the
        // status line stayed on "Syncing trip history…" while disconnected.
        if (isSyncing) {
            isSyncing = false
            _syncState.tryEmit(SyncState.IDLE)
        }
        _connectionState.tryEmit(ConnectionState.DISCONNECTED)
    }

    /** Tear down from outside the connect coroutine (trip reset, teardown). */
    fun disconnect() {
        val s = synchronized(this@BluetoothService) { socket }
        Log.i(TAG, "disconnect requested")
        closeSocket(s, ownsGuard = false)
    }

    private var syncExpected = 0
    private var syncReceived = 0
    @Volatile private var isSyncing = false

    private suspend fun parseLine(line: String) {
        try {
            val j = JSONObject(line.trim())
            val type = j.optString("type", "")

            when (type) {
                "sync_start" -> {
                    syncExpected = j.optInt("count", 0)
                    syncReceived = 0
                    isSyncing    = true
                    _syncProgress.value = 0 to syncExpected
                    _syncState.emit(SyncState.SYNCING)
                }

                "data" -> if (isSyncing) {
                    // Buffered record — parse same fields as real-time,
                    // tagged synced and carrying the ESP32 uptime timestamp.
                    _dataFlow.emit(parseCarData(j, synced = true))
                    syncReceived++
                    // The count from the ESP32 is an estimate (bytes ÷ 48), so
                    // never show more received than announced.
                    if (syncReceived % 25 == 0)
                        _syncProgress.value = syncReceived to maxOf(syncExpected, syncReceived)
                }

                "sync_end" -> {
                    isSyncing = false
                    _syncProgress.value = syncReceived to syncReceived
                    _syncState.emit(SyncState.COMPLETE)
                }

                "diag" -> _diagInfo.value = j

                // Ack/refusal for a drive-mode request we sent
                "mode" -> {
                    if (!j.optBoolean("ok", false))
                        _modeRefusals.emit(j.optString("reason", "mode change refused"))
                }

                // Firmware update progress, and command refusals (an older
                // firmware answers "OTA"/"VERSION" with type "error").
                "ota", "error" -> {
                    if (type == "error") _lastRefusal.value = j
                    otaReplies.trySend(j)
                }

                "fw" -> _firmwareInfo.value = j

                // Normal real-time packet — no "type" field at all.
                "" -> _dataFlow.emit(parseCarData(j))

                // Unknown type from a newer firmware: ignore it. This used to
                // be the catch-all, so an {"type":"error"} reply was parsed as
                // an all-zero telemetry packet and pushed onto the dashboard.
                else -> Log.d(TAG, "ignored message type '$type'")
            }
        } catch (_: Exception) { }
    }

    /**
     * Send a newline command to the ESP32 (e.g. drive mode "SPORT").
     *
     * suspend + Dispatchers.IO: this used to run on whichever thread tapped the
     * chip, i.e. the main thread. A congested RFCOMM link blocks write() for
     * seconds, which is an ANR on the dashboard while driving.
     */
    suspend fun sendCommand(cmd: String): Boolean = withContext(Dispatchers.IO) {
        if (rawMode) {
            // A firmware image is streaming: the ESP32 would write these bytes
            // into flash. The MD5 check would catch it, but only by failing the
            // whole update — refuse instead.
            Log.w(TAG, "sendCommand('$cmd') refused — firmware update in progress")
            return@withContext false
        }
        val s = synchronized(this@BluetoothService) { socket }
        if (s == null) {
            Log.w(TAG, "sendCommand('$cmd') dropped — not connected")
            return@withContext false
        }
        try {
            synchronized(writeLock) {
                s.outputStream.write((cmd + "\n").toByteArray())
                s.outputStream.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendCommand('$cmd') failed: ${e.message}")
            false          // link dropped — the supervisor reconnects
        }
    }

    /** Serialises every write, so a command can't land in the middle of an
     *  image chunk (the ESP32 would flash those bytes as firmware). */
    private val writeLock = Any()

    /** Raw bytes for a firmware update. Blocks until RFCOMM accepts them. */
    suspend fun sendRaw(data: ByteArray, off: Int, len: Int): Boolean =
        withContext(Dispatchers.IO) {
            val s = synchronized(this@BluetoothService) { socket } ?: return@withContext false
            try {
                synchronized(writeLock) {
                    s.outputStream.write(data, off, len)
                    s.outputStream.flush()
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "sendRaw failed: ${e.message}")
                false
            }
        }

    private fun parseCarData(j: JSONObject, synced: Boolean = false) = CarData(
        speedKmh    = j.optDouble("spd",  0.0).toFloat(),
        battSOC     = j.optInt("soc",     0),
        battVoltage = j.optDouble("batt", 0.0).toFloat(),
        battCurrent = j.optDouble("curr", 0.0).toFloat(),
        battTempC   = j.optDouble("btemp", 0.0).toFloat(),
        motorTempC  = j.optDouble("mtemp", 0.0).toFloat(),
        auxVoltage  = j.optDouble("aux",  0.0).toFloat(),
        errCode     = j.optInt("err",    -1),
        errName     = j.optString("errname", ""),
        statusBits  = j.optInt("status", -1),
        statusText  = j.optString("statustext", ""),
        brakeOn     = j.optBoolean("brake", false),
        acOn        = j.optBoolean("ac",    false),
        tsMs        = j.optLong("ts", 0L),
        isSynced    = synced,
        // Blank if absent — an old firmware that omits "mode" must not be
        // reported as NORMAL when the inverter could be in any mode.
        driveMode   = j.optString("mode", ""),
        dcLinkUp    = if (j.has("dc")) j.optBoolean("dc") else null
    )
}

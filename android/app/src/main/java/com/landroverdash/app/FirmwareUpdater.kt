package com.landroverdash.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest

private const val TAG_OTA = "EvOta"

/**
 * The live Bluetooth link, published by DashboardViewModel (which owns it) so
 * screens that don't own the connection — Settings — can use the same socket.
 */
object VehicleLink {
    @Volatile var service: BluetoothService? = null
        private set

    fun register(svc: BluetoothService) { service = svc }

    /** Only clears if it's still ours, so a newer dashboard's link survives. */
    fun release(svc: BluetoothService) { if (service === svc) service = null }
}

/** A firmware image picked by the user, read fully into memory and validated.
 *  [version] is read from the image itself (its boot banner), e.g. "v7.3". */
data class StagedImage(val name: String, val bytes: ByteArray, val md5: String,
                       val version: String)

/** Result of asking the car which firmware it runs. */
sealed class FwQuery {
    data class Info(val json: JSONObject) : FwQuery()
    /** The car answered but doesn't know the command: firmware v7.0 or older. */
    object TooOld : FwQuery()
    /** No reply in time: the link is busy (e.g. a history sync) or the car is restarting. */
    object NoAnswer : FwQuery()
    object NotConnected : FwQuery()
    /** An update is running; don't put anything else on the link. */
    object Busy : FwQuery()
}

sealed class OtaState {
    object Idle : OtaState()
    object Starting : OtaState()
    data class Transferring(val sent: Int, val total: Int) : OtaState()
    object Verifying : OtaState()
    object Rebooting : OtaState()
    object Confirming : OtaState()
    data class Done(val version: String?, val message: String) : OtaState()
    data class Failed(val message: String) : OtaState()

    val busy: Boolean
        get() = this is Starting || this is Transferring || this is Verifying ||
                this is Rebooting || this is Confirming
}

/**
 * Pushes a new ESP32 firmware image over the existing SPP link.
 *
 * Process-scoped on purpose: leaving Settings mid-transfer must not kill it.
 * The protocol is stop-and-wait (see the OTA section of freelander-esp32.ino): send
 * one chunk, wait for its ack, repeat. Nothing becomes bootable on the ESP32
 * until the whole image has arrived and its MD5 matches, so every failure path
 * here leaves the car on the firmware it already had.
 */
object FirmwareUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state

    private val _staged = MutableStateFlow<StagedImage?>(null)
    val staged: StateFlow<StagedImage?> = _staged

    // ESP32 app image sanity limits. The real slot size is enforced by the car.
    private const val MIN_IMAGE = 100 * 1024        // anything smaller is a bootloader/partition table
    private const val MAX_IMAGE = 4 * 1024 * 1024
    private const val ESP_IMAGE_MAGIC = 0xE9

    /**
     * Reads the picked file into memory and checks it really is an ESP32
     * application image. Reading it now (rather than at install time) means a
     * USB stick can be pulled straight after picking, and the picker's
     * temporary read grant can lapse, without affecting the update.
     *
     * Returns an error message, or null on success.
     */
    suspend fun stage(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        if (_state.value.busy) return@withContext "An update is already in progress"
        val name = displayName(ctx, uri) ?: "firmware.bin"
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG_OTA, "read failed", e)
            null
        } ?: return@withContext "Couldn't read that file"
        stageBytes(name, bytes)
    }

    /** Same as [stage], for a file picked with the built-in [StorageBrowser]. */
    suspend fun stageFile(file: java.io.File): String? = withContext(Dispatchers.IO) {
        if (_state.value.busy) return@withContext "An update is already in progress"
        val bytes = try { file.readBytes() } catch (e: Exception) {
            Log.w(TAG_OTA, "read failed: ${file.path}", e)
            null
        } ?: return@withContext "Couldn't read ${file.name}"
        stageBytes(file.name, bytes)
    }

    private fun stageBytes(name: String, bytes: ByteArray): String? {
        when {
            bytes.size < MIN_IMAGE ->
                return "Too small to be the firmware (${bytes.size / 1024} KB). " +
                    "Pick the sketch's .ino.bin — not bootloader.bin or partitions.bin."
            bytes.size > MAX_IMAGE ->
                return "Too large to be ESP32 firmware (${bytes.size / 1024} KB)"
            (bytes[0].toInt() and 0xFF) != ESP_IMAGE_MAGIC ->
                return "Not an ESP32 app image. Pick the .ino.bin from " +
                    "Sketch → Export Compiled Binary — not the .merged.bin."
            // esp_image_header_t.chip_id (offset 12, little-endian): 0 = ESP32.
            // Catches an image built for an S3/C3 by mistake.
            ((bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)) != 0 ->
                return "This image was built for a different ESP32 chip " +
                    "(select board 'ESP32 Dev Module')."
        }

        // Must be THIS car's firmware, and a version that can itself be updated
        // over Bluetooth (v7.1+). Anything else would install fine and then
        // leave the sealed unit unreachable — no app link, no further updates.
        // The car (v7.3+) runs the same check, but catching it here saves a
        // two-minute transfer and explains why.
        val ver = carFirmwareVersion(bytes)
            ?: return "This isn't the car's firmware — it doesn't contain the " +
                "\"Freelander EV Vehicle Interface\" banner. Export freelander-esp32 " +
                "with Sketch → Export Compiled Binary and pick its .ino.bin."
        if (ver.first < 7 || (ver.first == 7 && ver.second < 1))
            return "That file is firmware v${ver.first}.${ver.second}, which can't be " +
                "updated over Bluetooth afterwards. Pick v7.1 or newer."

        _staged.value = StagedImage(name, bytes, md5Hex(bytes), "v${ver.first}.${ver.second}")
        if (!_state.value.busy) _state.value = OtaState.Idle
        return null
    }

    private val ID_PREFIX = "Freelander EV Vehicle Interface v".toByteArray(Charsets.US_ASCII)

    /**
     * Finds the firmware's boot banner ("Freelander EV Vehicle Interface v7.3")
     * inside the image and returns (major, minor), or null if it isn't there.
     * The banner is one string literal, so it sits contiguously in flash.
     */
    private fun carFirmwareVersion(b: ByteArray): Pair<Int, Int>? {
        val p = ID_PREFIX
        var i = 0
        while (i <= b.size - p.size) {
            var k = 0
            while (k < p.size && b[i + k] == p[k]) k++
            if (k == p.size) {
                var j = i + p.size
                var major = 0; var nMaj = 0
                while (j < b.size && nMaj < 3 && b[j].toInt() in 48..57) {
                    major = major * 10 + (b[j] - 48); j++; nMaj++
                }
                if (nMaj > 0 && j < b.size && b[j].toInt() == '.'.code) {
                    j++
                    var minor = 0; var nMin = 0
                    while (j < b.size && nMin < 3 && b[j].toInt() in 48..57) {
                        minor = minor * 10 + (b[j] - 48); j++; nMin++
                    }
                    if (nMin > 0) return major to minor
                }
            }
            i++
        }
        return null
    }

    /** "v7.3" → (7, 3); null if it doesn't look like a version. */
    fun parseVersion(v: String?): Pair<Int, Int>? {
        val m = Regex("""v?(\d+)\.(\d+)""").find(v ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun clearStaged() { if (!_state.value.busy) _staged.value = null }

    /**
     * One command/reply exchange at a time on the link — VERSION, DIAG and the
     * start of an update — so the "error" reply to one can never be taken as
     * the answer to another (e.g. a DIAG refusal read as the car refusing OTA).
     */
    val queryLock = Mutex()

    /**
     * Asks the car which firmware it runs. Tells "the car said it doesn't know
     * VERSION" (v7.0 or older) apart from "no reply" (link busy, e.g. during a
     * history sync) — they used to read the same, and the second one would
     * wrongly tell you to reflash over USB.
     */
    suspend fun queryVersion(): FwQuery {
        if (_state.value.busy) return FwQuery.Busy
        val svc = VehicleLink.service ?: return FwQuery.NotConnected
        if (!svc.isConnected()) return FwQuery.NotConnected
        return queryLock.withLock {
            if (_state.value.busy) return@withLock FwQuery.Busy
            val prevFw  = svc.firmwareInfo.value
            val prevErr = svc.lastRefusal.value
            if (!svc.sendCommand("VERSION")) return@withLock FwQuery.NotConnected
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val fw = svc.firmwareInfo.value
                if (fw != null && fw !== prevFw) return@withLock FwQuery.Info(fw)
                val err = svc.lastRefusal.value
                if (err != null && err !== prevErr) return@withLock FwQuery.TooOld
                if (!svc.isConnected()) return@withLock FwQuery.NotConnected
                delay(50)
            }
            FwQuery.NoAnswer
        }
    }

    fun install() {
        val img = _staged.value ?: return
        if (job?.isActive == true) return
        job = scope.launch { runInstall(img) }
    }

    fun cancel() { job?.cancel() }

    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runInstall(img: StagedImage) {
        val svc = VehicleLink.service
        if (svc == null || !svc.isConnected()) {
            fail("Not connected to the car. Go back to the dashboard, wait for it to " +
                 "connect, then try again.")
            return
        }
        // Busy from here, so no new VERSION/DIAG starts; then wait (at most a
        // few seconds) for one already in flight to get its answer.
        _state.value = OtaState.Starting
        try {
            queryLock.withLock { runInstallLocked(svc, img) }
        } catch (e: CancellationException) {
            if (_state.value.busy) _state.value = OtaState.Failed("Cancelled. $KEPT")
            throw e
        }
    }

    private suspend fun runInstallLocked(svc: BluetoothService, img: StagedImage) {
        var transferring = false
        try {
            _state.value = OtaState.Starting
            svc.drainOtaReplies()
            if (!svc.sendCommand("OTA ${img.bytes.size} ${img.md5}")) {
                fail("Couldn't reach the car — the Bluetooth link dropped.")
                return
            }
            val ready = awaitReply(svc, 8_000)
            when {
                ready == null ->
                    return fail("The car didn't answer. It needs firmware v7.1 or newer to " +
                                "update over Bluetooth.")
                ready.optString("type") == "error" ->
                    return fail("The car's current firmware can't update over Bluetooth. " +
                                "Flash v7.1 once over USB — after that, updates work from here.")
                ready.optString("state") == "error" ->
                    return fail("Car refused: ${ready.optString("msg", "unknown reason")}")
                ready.optString("state") != "ready" ->
                    return fail("Unexpected reply from the car — nothing was changed.")
            }

            // From here every byte on the link is firmware, so block commands.
            transferring = true
            svc.rawMode = true
            val chunk = ready!!.optInt("chunk", 256).coerceIn(64, 4096)
            val total = img.bytes.size
            var off = 0
            _state.value = OtaState.Transferring(0, total)
            while (off < total) {
                val n = minOf(chunk, total - off)
                if (!svc.sendRaw(img.bytes, off, n))
                    return fail("Link dropped during the transfer. $KEPT")
                val ack = awaitReply(svc, 10_000)
                    ?: return abortLink(svc, "Transfer timed out. $KEPT")
                if (ack.optString("state") == "error")
                    return abortLink(svc, "${ack.optString("msg", "Car reported an error")}. $KEPT")
                if (ack.optString("state") != "ack" || ack.optLong("off", -1) != (off + n).toLong())
                    return abortLink(svc, "Transfer fell out of step. $KEPT")
                off += n
                _state.value = OtaState.Transferring(off, total)
            }

            _state.value = OtaState.Verifying
            val fin = awaitReply(svc, 20_000)
                ?: return abortLink(svc, "The car didn't confirm the image. $KEPT")
            if (fin.optString("state") != "done")
                return abortLink(svc, "Image rejected: ${fin.optString("msg", "verification failed")}. $KEPT")

            transferring = false
            svc.rawMode = false

            // The ESP32 reboots now. The dashboard's reconnect supervisor keeps
            // retrying even with Settings on top, so wait for the link to drop
            // and come back. That reconnection is also what makes the new
            // firmware mark itself good — until then it would roll back.
            _state.value = OtaState.Rebooting
            val back = withTimeoutOrNull(90_000) {
                svc.connectionState
                    .dropWhile { it == BluetoothService.ConnectionState.CONNECTED }
                    .first { it == BluetoothService.ConnectionState.CONNECTED }
            }
            if (back == null) {
                _state.value = OtaState.Done(null,
                    "Firmware installed and the car is rebooting. Keep the app open until " +
                    "it reconnects — that confirms the update. If the car is switched off " +
                    "first, it will safely go back to the previous firmware.")
                return
            }

            _state.value = OtaState.Confirming
            delay(1_500)                              // let the reconnect settle
            val info = queryVersionRaw(svc)
            val version = info?.optString("version")?.takeIf { it.isNotEmpty() }
            _state.value = OtaState.Done(version,
                if (version != null) "Updated to $version — confirmed by the car."
                else "Firmware installed and the car reconnected.")
            _staged.value = null
        } catch (e: CancellationException) {
            if (transferring) svc.disconnect()        // makes the ESP32 abort at once
            _state.value = OtaState.Failed("Cancelled. $KEPT")
        } catch (e: Exception) {
            Log.e(TAG_OTA, "install failed", e)
            if (transferring) svc.disconnect()
            _state.value = OtaState.Failed("Update failed: ${e.message}. $KEPT")
        } finally {
            svc.rawMode = false
        }
    }

    private const val KEPT = "The car kept its current firmware."

    private fun fail(msg: String) {
        Log.w(TAG_OTA, msg)
        _state.value = OtaState.Failed(msg)
    }

    /** Fails and drops the link, so the ESP32 aborts immediately rather than
     *  waiting out its 15 s idle timeout. The dashboard reconnects on its own. */
    private fun abortLink(svc: BluetoothService, msg: String) {
        svc.rawMode = false
        svc.disconnect()
        fail(msg)
    }

    private suspend fun awaitReply(svc: BluetoothService, ms: Long): JSONObject? =
        withTimeoutOrNull(ms) { svc.otaReplies.receive() }

    private suspend fun queryVersionRaw(svc: BluetoothService): JSONObject? {
        val prev = svc.firmwareInfo.value
        if (!svc.sendCommand("VERSION")) return null
        return withTimeoutOrNull(5_000) {
            svc.firmwareInfo.first { it != null && it !== prev }
        }
    }

    private fun md5Hex(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }
}

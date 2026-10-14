package com.landroverdash.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One reading of every cell and module, from the BMS boards or simulated.
 *
 * Cells are in pack order: module 1 cell 1, module 1 cell 2 … the last cell of
 * the last module. NaN means that cell or sensor gave no reading.
 */
class BatterySnapshot(
    /** Cell voltages, V. */
    val cellV: FloatArray,
    /** One temperature per module, °C. */
    val moduleTempC: FloatArray,
    /** True while the ESP32 sends no BMS data and the app shows a simulation. */
    val simulated: Boolean,
    val timeMs: Long
) {
    /**
     * Cells in each module: the cells shared out over the temperatures (one per
     * module), or 8 a module if none came. When they don't divide evenly the
     * last module is short, so every cell still belongs to a module.
     */
    val cellsPerModule: Int = if (moduleTempC.isEmpty()) 8
        else maxOf(1, (cellV.size + moduleTempC.size - 1) / moduleTempC.size)
    /** Modules in the pack. */
    val modules: Int = maxOf(1, (cellV.size + cellsPerModule - 1) / cellsPerModule)

    /** Index of the highest / lowest cell with a reading; -1 when there are none. */
    val highestCell = extreme(cellV, highest = true)
    val lowestCell = extreme(cellV, highest = false)
    /** Index of the hottest / coolest module with a reading; -1 when there are none. */
    val hottestModule = extreme(moduleTempC, highest = true)
    val coolestModule = extreme(moduleTempC, highest = false)

    val highestV: Float get() = cellV.getOrElse(highestCell) { Float.NaN }
    val lowestV: Float get() = cellV.getOrElse(lowestCell) { Float.NaN }
    /** Highest minus lowest cell, mV. */
    val deltaMv: Float get() = (highestV - lowestV) * 1000f
    val averageV: Float = cellV.filter { !it.isNaN() }
        .let { if (it.isEmpty()) Float.NaN else it.average().toFloat() }

    val maxTempC: Float get() = moduleTempC.getOrElse(hottestModule) { Float.NaN }
    val minTempC: Float get() = moduleTempC.getOrElse(coolestModule) { Float.NaN }
    val tempDeltaC: Float get() = maxTempC - minTempC

    /** The module (0-based) a cell (0-based) belongs to. */
    fun moduleOf(cell: Int) = cell / cellsPerModule

    private companion object {
        fun extreme(a: FloatArray, highest: Boolean): Int {
            var best = -1
            for (i in a.indices) {
                val v = a[i]
                if (v.isNaN()) continue
                if (best < 0 || (if (highest) v > a[best] else v < a[best])) best = i
            }
            return best
        }
    }
}

/**
 * Cell voltages and module temperatures from the BMS boards, which the ESP32
 * reads over CAN and forwards once a second as
 *
 *   {"type":"bms","cells":[4012,4008, … 96 values],"temps":[27.5,28.0, … 12 values]}
 *
 * cells: millivolts, in pack order (module 1 cell 1 first). temps: °C, one per
 * module, in module order. A cell with no reading can be sent as 0 or null; a
 * sensor with no reading as null (0 is a real 0 °C). The module count is taken
 * from the number of temperatures, so a different pack layout needs no app
 * change.
 *
 * Until those packets arrive, [current] returns a simulation built from the
 * live pack voltage and current, marked [BatterySnapshot.simulated].
 */
object LiveBatteryData {

    private val _real = MutableStateFlow<BatterySnapshot?>(null)
    /** The last reading the ESP32 sent; null until the first one. */
    val real: StateFlow<BatterySnapshot?> = _real

    /** A real reading older than this is stale, and the screen goes back to the simulation. */
    const val STALE_MS = 5_000L

    /** Called by [BluetoothService] for every {"type":"bms"} packet. */
    fun onPacket(j: JSONObject) {
        val cells = j.optJSONArray("cells") ?: return
        if (cells.length() == 0) return
        val temps = j.optJSONArray("temps")
        val cellV = FloatArray(cells.length()) { i ->
            val mv = cells.optDouble(i, Double.NaN)
            if (mv.isNaN() || mv < 1000.0 || mv > 5000.0) Float.NaN else (mv / 1000.0).toFloat()
        }
        val tempC = FloatArray(temps?.length() ?: 0) { i ->
            val c = temps?.optDouble(i, Double.NaN) ?: Double.NaN
            if (c.isNaN() || c < -40.0 || c > 125.0) Float.NaN else c.toFloat()
        }
        _real.value = BatterySnapshot(cellV, tempC, simulated = false,
            timeMs = System.currentTimeMillis())
    }

    /** The newest real reading while it's fresh; otherwise the simulation. */
    fun current(nowMs: Long = System.currentTimeMillis()): BatterySnapshot {
        val r = _real.value
        if (r != null && nowMs - r.timeMs < STALE_MS) return r
        return BatteryMock.snapshot(nowMs)
    }
}

/**
 * Stand-in for the BMS data until the ESP32 sends it: the pack fitted today,
 * 84 cells in series in 7 modules of 12, built from what the car reports now.
 *
 * The average cell is the live pack voltage divided by the 84 cells, so it
 * rises with charge and sags under load. Each cell keeps its own offset and
 * internal resistance, so the same cells are always the high and low ones and
 * the spread opens up under load, as in a real pack. Module temperatures warm
 * slowly with current, the middle modules most. With no live data it shows a
 * resting pack at 3.85 V a cell.
 *
 * At rest it holds still, as a real pack does: the pack voltage is followed
 * slowly and only in 2 mV steps a cell, so jitter in the reading never moves
 * the columns, and there's no reading noise. Cells are whole millivolts, as
 * the BMS boards report them.
 */
internal object BatteryMock {
    const val CELLS = 84
    const val MODULES = 7
    private const val AMBIENT_C = 24f
    /** The pack counts as at rest below 8 A, and stays so until 12 A (no flicker between). */
    private const val REST_ENTER_A = 8f
    private const val REST_LEAVE_A = 12f

    private val rnd = Random(96)
    /** Fixed per-cell offsets, mV: cell 37 is the weak one, cell 60 sits high. */
    private val offsetMv = FloatArray(CELLS) { (rnd.nextFloat() - 0.5f) * 14f }.also {
        it[36] = -17f
        it[59] = 11f
    }
    /** Per-cell internal resistance, mΩ: the weak cell sags a little more under load. */
    private val resistanceMohm = FloatArray(CELLS) { 1.3f + (rnd.nextFloat() - 0.5f) * 0.3f }
        .also { it[36] = 1.6f }
    private val averageMohm = resistanceMohm.average().toFloat()
    /** Middle modules run warmer, as they shed heat least. */
    private val moduleWarmth = FloatArray(MODULES) { m ->
        2.5f * (1f - abs(m - (MODULES - 1) / 2f) / ((MODULES - 1) / 2f)) + rnd.nextFloat() * 0.6f
    }
    /** °C above ambient from current, following it with a five-minute lag. */
    private val heat = FloatArray(MODULES)
    private var lastMs = 0L
    /** Average cell, V: following the pack, and the value the cells are built on. */
    private var followV = 0f
    private var shownV = 0f
    private var wasLive = false
    private var atRest = true

    @Synchronized
    fun snapshot(nowMs: Long): BatterySnapshot {
        val packV = LiveVehicleData.voltageV.value
        val live = packV > 0f
        val currentA = if (live) LiveVehicleData.powerKw.value * 1000f / packV else 0f
        atRest = abs(currentA) < if (atRest) REST_LEAVE_A else REST_ENTER_A
        val dtS = if (lastMs > 0L) ((nowMs - lastMs) / 1000f).coerceIn(0f, 10f) else 0f
        lastMs = nowMs

        // Average cell: at rest, follow the pack slowly (about 30 s) and only
        // move once it has drifted 2 mV, so reading jitter never reaches the
        // cells; under load, follow within a couple of seconds so the sag shows.
        val target = if (live) packV / CELLS else 3.85f
        if (followV <= 0f || live != wasLive) {
            followV = target                    // first reading, or the link came or went
            shownV = target
        } else {
            followV += (target - followV) * (dtS / if (atRest) 30f else 2f).coerceAtMost(1f)
            if (!atRest || abs(followV - shownV) >= 0.002f) shownV = followV
        }
        wasLive = live

        val rise = currentA * currentA * 0.0006f              // 100 A held: about +6 °C
        for (m in 0 until MODULES) {
            val goal = rise * (1f + moduleWarmth[m] / 5f)
            heat[m] += (goal - heat[m]) * (dtS / 300f)
        }

        // Under load the weaker cells sag more, with a millivolt of reading
        // noise; at rest only the fixed offsets remain (a few amps of accessory
        // draw moves a cell by well under a millivolt).
        val loadA = if (atRest) 0f else currentA
        val cells = FloatArray(CELLS) { i ->
            val sag = loadA * (resistanceMohm[i] - averageMohm) / 1000f
            val noise = if (atRest) 0f else (rnd.nextFloat() - 0.5f) * 0.002f
            val v = shownV + offsetMv[i] / 1000f - sag + noise
            (v * 1000f).roundToInt() / 1000f
        }
        val temps = FloatArray(MODULES) { m -> AMBIENT_C + moduleWarmth[m] + heat[m] }
        return BatterySnapshot(cells, temps, simulated = true, timeMs = nowMs)
    }
}

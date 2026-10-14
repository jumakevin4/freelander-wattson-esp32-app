/**
 * Freelander EV Vehicle Interface — CAN + OpenInverter SDO Gateway  (v7)
 * WeAct CAN485 DevBoard V1 (or bare ESP32 + SN65HVD230), 500 kbps bus
 *
 * WHAT THIS BOARD DOES
 * --------------------
 *  READS   Land Rover bus : speed (0x153), odometer (0x613)
 *          OpenInverter   : udc, idc, tmphs, tmpm, seldir, opmode, fweak,
 *                           uaux, lasterr, status — polled over CANopen SDO
 *  DRIVES  cluster tacho   : 0x316, needle = |idc| (a live power gauge)
 *          cluster temp    : 0x329 byte 1, remapped motor temp
 *          gear display    : 0x43F from seldir (replaces a standalone Arduino)
 *          fuel gauge      : SOC from udc → PWM → VTL5C4 vactrol → sender wires
 *          drive modes     : ECO / NORMAL / SPORT written back over SDO
 *          Android app     : telemetry over Bluetooth Classic SPP
 *  LOGS    SD card         : every sample as CSV, plus a fault history
 *
 * v7.4 changes
 * ------------
 *  - CURRENT CALIBRATION: IDC_SCALE 1.0 → 1.85. On a test drive the battery
 *    went 100 → 74 % (≈ 2.99 kWh of the ~11.5 kWh usable) while the app
 *    integrated only 1.58 kWh from udc × idc, a ratio of 1.89. 1.85 is the
 *    middle of the 1.8–1.9 estimate. Energy used, kW, peak current, the SD log
 *    and the history sync all carry the corrected current.
 *  - REV COUNTER keeps its feel: RPM_PER_AMP is now 35 / IDC_SCALE, so the
 *    needle sits where it did for the same pedal.
 *  - FUEL NEEDLE: i × PACK_R now undoes the whole load sag. PACK_R (0.12 Ω) is
 *    in true amps; with idc reading low it undid only about half of it.
 *
 * v7.3 changes
 * ------------
 *  - BOOT ORDER: the cluster-facing outputs come up first — fuel needle, then
 *    CAN with the 0x329/0x316/0x43F timer — before the SD card or Bluetooth,
 *    which together took seconds. The cluster no longer wakes to a silent bus
 *    and an open-circuit fuel sender. The 300 ms startup delay is gone.
 *  - VEHICLE TASK: CAN receive, inverter SDO polling, drive-mode writes, gauges
 *    and the fuel needle run in their own FreeRTOS task (vehicleTask). loop()
 *    keeps Bluetooth, the SD card and the USB console, any of which can block
 *    (a congested SPP link blocks BT.print) — and used to freeze the gear
 *    frame and let the inverter values go stale, flipping the gear display to P.
 *    The 0x43F gear frame now goes out from the 10 ms timer.
 *  - FUEL NEEDLE: driven from the last known level (saved in NVS) from power-on,
 *    and only re-measured once the inverter has left Off — this car precharges
 *    on the start signal (bootprec = 0), so before that udc is 0 or a capacitor
 *    bank bleeding down, never the pack. Never left dark (open circuit = below E).
 *  - SYNC: the cursor now moves past rows the app already received live, so a
 *    connect only replays the time the app was actually away; live packets keep
 *    flowing during a sync.
 *  - DIAG over Bluetooth (read-only): reset reason, boot-to-first-CAN-frame
 *    time, bus state and error counters, bus-off count, and any OTHER node seen
 *    transmitting our own ids 0x316/0x329/0x43F (a duplicate sender corrupts
 *    frames and can drive the bus off at power-up).
 *  - OTA refuses any image that is not this firmware at v7.1 or later, which
 *    would otherwise end Bluetooth updates for good. Drive mode reads "" until
 *    the inverter has reported it, instead of claiming NORMAL.
 *  - Live packets carry "dc" (inverter out of Off) so the app's battery % uses
 *    the same gate as the fuel needle and the two agree from key-on.
 *
 * v7 changes
 * ----------
 *  - SD CARD is now the primary offline store. Every sample is appended as CSV
 *    and nothing is deleted; a persistent (file, offset) cursor tracks how far
 *    the app has been caught up. The RAM ring buffer is the no-card fallback.
 *  - FAULTS: lasterr + status polled and decoded; every change of lasterr is
 *    appended to /ERRORS.CSV with the odometer as its reference.
 *  - 12 V rail (uaux) reported, scaled — see AUX_SCALE.
 *  - Drive mode is READ BACK from fweak rather than assumed, so the app shows
 *    the inverter's real state after an ignition cycle.
 *  - SOC from a single pack-volt table (270 V = 0 %, 340 V = 100 %).
 *  - Fuel gauge: 12-bit PWM. A pre-settle LED pulse was tried to cancel the
 *    vactrol's hysteresis and REVERTED — it biased the needle toward empty.
 *    See the note above fuelWrite() before trying that again.
 *  - SECURITY: Bluetooth is restricted to an allowlist (drive mode + SYNC).
 *    Diagnostics stay on USB serial. See pollBTCommands().
 *  - Removed: the 2048-entry CAN sniff table (40 KB static, discovery long
 *    finished), the guided FUEL SWEEP (superseded by fuel_gauge_calibrate.ino),
 *    the self-learning pack-resistance estimator, and isKnown().
 *
 * Companion firmware for the LandroverEV Android app (com.landroverdash.app,
 * ~/AndroidStudioProjects/LandroverEV). The BT protocol below matches the
 * app's BluetoothService.kt EXACTLY — change them together or not at all.
 *
 * SECURITY MODEL — read before extending
 * --------------------------------------
 * Trust boundary: anything arriving over Bluetooth is UNTRUSTED. Pairing is
 * legacy PIN only (BluetoothSerial offers nothing stronger), and Android may
 * negotiate "just works" SSP regardless, so treat the PIN as a speed bump and
 * not as authentication.
 *
 *  - Bluetooth may: request a drive mode, ask for history (SYNC), read the
 *    firmware version (VERSION) and diagnostics (DIAG) — both read-only — and
 *    push a firmware update (OTA, gated: stationary, inverter not in RUN, no
 *    sync running, image must identify as this firmware v7.1+). Everything
 *    else is rejected and logged. Do NOT route new commands through
 *    pollBTCommands() without deciding they are safe in the hands of whoever
 *    last paired a phone.
 *  - requestDriveMode() is the ONLY path that writes to the inverter. It can
 *    write nothing but the six rows of modeParams — no caller picks an id.
 *    Guarded on: valid mode, no change in flight, under 3 km/h, not in RUN.
 *  - SCAN / READ are read-only (0x40 upload requests only) and serial-only.
 *  - Command lines are length-bounded and an over-long line is discarded whole,
 *    never split into a fragment that could parse as its own command.
 *  - The one sscanf() has no %s conversion. Every enum lookup is bounds-checked
 *    against its table size, so a value from a newer inverter build reads as
 *    UNKNOWN rather than walking off the end of the array.
 *
 * SDO mechanics (bench-tested)
 * ----------------------------
 *   - Requests on 0x601 (node 1), replies on 0x581
 *   - Unique-ID addressing: index 0x2100 | (paramId >> 8), sub = paramId & 0xFF
 *   - Values are s32 fixed-point, 5 fractional bits (raw / 32.0)
 *   - Round-robin reads, one transaction in flight, 100 ms slots, 200 ms timeout
 *   - Polled params → app fields:
 *        udc     (2001) → "batt"   pack voltage (V)
 *        idc     (2002) → "curr"   DC-link current (A), × IDC_SCALE
 *        tmphs   (2019) → "btemp"  heatsink °C (app shows "Inverter Temp")
 *        tmpm    (2020) → "mtemp"  motor °C  → also drives the temp gauge
 *        seldir  (2018) → gear on 0x43F (polarity swapped on this install:
 *                         -1 = forward/D, +1 = reverse/R)
 *        opmode  (2000) → gates P vs N, and blocks mode writes while running
 *        fweak   (2)    → identifies the active drive mode
 *        uaux    (2021) → "aux"    12 V rail (V), × AUX_SCALE
 *        lasterr (2038) → "err"    last fault, decoded
 *        status  (2044) → "status" condition bitfield, decoded
 *   - Also answers requests on 0x200 with a cached status frame on 0x201
 *     (4 × int16 LE, value ×10) for any other node that wants the data.
 *
 * 0x329 TX runs from boot on a 10 ms hardware timer (the donor ECU is out of
 * the car). Bus-off recovers automatically via alerts + a periodic health check.
 *
 * ── BT protocol (newline JSON over SPP) ──────────────────────────────────────
 * Device name "Freelander EV" (PIN 2837, legacy pairing only).
 * Live (no "type" field, every 500 ms):
 *   {"spd":f,"soc":u,"batt":f,"curr":f,"btemp":f,"mtemp":f,"aux":f,
 *    "err":i,"errname":"...","status":i,"statustext":"...",
 *    "brake":b,"ac":b,"mode":"ECO|NORMAL|SPORT|CUSTOM|","dc":b}
 *   "mode" is "" until the inverter has reported it (v7.3).
 *   "dc" (v7.3) = inverter has left Off, i.e. "batt" is the real pack voltage;
 *   before that it is 0 or a capacitor bank bleeding down. The app gates its
 *   SOC on it exactly as the fuel needle is gated here.
 * App → ESP32 (newline), ALLOWLIST — anything else is refused:
 *   "ECO" / "NORMAL" / "SPORT"
 *      → {"type":"mode","ok":true,"mode":"SPORT"}
 *      → {"type":"mode","ok":false,"reason":"inverter running — switch off first"}
 *   "SYNC"  → replay history
 *   "VERSION" → {"type":"fw","version":"v7.1","ota":b,"rollback":b,"slot":"ota_0"}
 *   "DIAG"    → {"type":"diag","fw":..,"up":s,"reset":"power-on|brownout|crash|..",
 *                "boots":n,"firsttx":ms,"canup":ms,"bus":"running|bus-off|..","tec":n,
 *                "rec":n,"busoff":n,"txfail":n,"rxmiss":n,"buserr":n,
 *                "dup316":n,"dup329":n,"dup43f":n,"fuel":"saved|live|default",
 *                "fueldrv":%,"sd":b,"heap":kB}
 *   "OTA <size> <md5hex>" → firmware update over this link (see OTA section):
 *      → {"type":"ota","state":"ready","chunk":C,"fw":"v7.1"}  | state "error","msg"
 *      app sends C raw bytes at a time; each chunk →
 *      → {"type":"ota","state":"ack","off":N}
 *      after the last chunk → {"type":"ota","state":"done"} and a reboot
 *   other   → {"type":"error","reason":"command not permitted over BT"}
 * History (auto-flush on connect):
 *   {"type":"sync_start","count":N}
 *   {"type":"data","ts":ms,...same fields...}   × N
 *   {"type":"sync_end"}
 *
 * Serial (USB) commands — full set, physical access required
 * ---------------------------------------------------------
 *   SYNC                 replay history to the app
 *   STOP329 / START329   pause / resume the cluster gauge TX timer
 *   ECO / NORMAL / SPORT change drive mode (same guards as the BT path)
 *   MODE                 print the active mode and the parameter table
 *   FUEL n / AUTO        force a drive %, or resume SOC tracking
 *   SDMOUNT / SDINFO     retry the card / show files and the sync cursor
 *   SDREWIND             reset the sync cursor (replays everything)
 *   ERRLOG               print /ERRORS.CSV
 *   SCAN [a b] / READ n  read-only parameter discovery over SDO
 *
 * Wiring
 * ------
 *   CAN   : WeAct board GPIO26/27 (onboard isolated transceiver)
 *           bare ESP32 + SN65HVD230: R → GPIO16, D → GPIO17
 *   Fuel  : GPIO25 → 100Ω → VTL5C4 LED anode; LED cathode → GND;
 *           vactrol photocell across the old fuel-sender wires (isolated —
 *           do NOT bond ESP32 GND to the gauge circuit)
 *   SD    : WeAct TF slot — CS 13, SCK 14, MOSI 15, MISO 2 (FAT32/FAT16, MBR)
 */

#define FW_VERSION "v7.4"

#include <Arduino.h>
#include "driver/twai.h"
#include "BluetoothSerial.h"
#include "esp_timer.h"
#include "esp_arduino_version.h"
#include "esp_bt.h"
#include <math.h>
#include <SPI.h>
#include <SD.h>
#include <Preferences.h>
#include <Update.h>          // firmware update over Bluetooth (OTA)
#include "esp_ota_ops.h"     // partition/rollback state
#include "esp_system.h"      // esp_reset_reason()

// ── Cross-task state (v7.3) ───────────────────────────────────────────────────
// Everything that talks to the car runs in vehicleTask; Bluetooth, the SD card
// and the USB console stay in loop(). They share a little state. Single 32-bit
// fields (floats, ints, pointers) are atomic on the ESP32, so most of it is
// simply volatile; this spinlock guards the few check-then-set sequences
// (drive-mode and scan requests) that span several fields.
static portMUX_TYPE vehMux = portMUX_INITIALIZER_UNLOCKED;

// Land Rover values decoded on receipt in vehicleTask — readers in loop() get
// a whole value, never half of one frame and half of the next.
static volatile float    g_speedKmh = 0.0f;
static volatile uint32_t g_odoKm    = 0;

// Diagnostics (DIAG over Bluetooth)
static volatile uint32_t g_firstTxMs   = 0;   // app-start → first gauge frame queued
static volatile uint32_t g_canUpMs     = 0;   // app-start → CAN driver running
static volatile uint32_t g_busOffCount = 0;
// Frames RECEIVED on ids this board transmits. The controller never receives
// its own frames, so any count here means another node is sending them too —
// two senders on one id corrupt each other and can drive the bus off.
static volatile uint32_t g_dup316 = 0, g_dup329 = 0, g_dup43F = 0;
static uint32_t g_bootCount = 0;
static bool     g_canOk     = false;          // TWAI driver installed and started

// ── Board selection ───────────────────────────────────────────────────────────
// WeAct CAN485 DevBoard V1: ESP32-D0WD-V3 + onboard 2.5kV ISOLATED CAN
// transceiver on GPIO26/27 (no external SN65HVD230 needed; 120Ω termination
// via onboard DIP switch). Comment this out for the old bare-ESP32 wiring.
#define BOARD_WEACT_CAN485

#ifdef BOARD_WEACT_CAN485
  #define CAN_RX_PIN   GPIO_NUM_26   // onboard CA-IS2062A transceiver
  #define CAN_TX_PIN   GPIO_NUM_27
#else
  #define CAN_RX_PIN   GPIO_NUM_16   // external SN65HVD230: R → 16, D → 17
  #define CAN_TX_PIN   GPIO_NUM_17
#endif
#define SUMMARY_MS   3000
#define BT_MS        500     // live packet + offline buffer interval

#define TARGET_COOLANT_C  60.0f   // gauge fallback while inverter/tmpm is offline
#define BT_PIN            "2837"

// ── Motor-temp → gauge remap ─────────────────────────────────────────────────
// The cluster gauge was scaled for coolant ("normal" ≈ 90 °C), but an EV motor
// cruises much cooler. Stretch the motor's working range across the gauge's
// comfortable zone so the needle behaves like drivers expect:
//   motor 20 °C → gauge shows 50 °C   (just off the cold stop)
//   motor 100 °C → gauge shows 110 °C (knocking on the red zone)
// Linear in between, clamped outside. Tune these four to taste.
#define MOTOR_T_MIN   20.0f
#define MOTOR_T_MAX  100.0f
#define GAUGE_T_MIN   50.0f
#define GAUGE_T_MAX  110.0f

// ── OpenInverter SDO polling ──────────────────────────────────────────────────
#define NODE_ID   1
#define SDO_REQ   (0x600 + NODE_ID)   // 0x601
#define SDO_RESP  (0x580 + NODE_ID)   // 0x581

// Custom status protocol (IDs verified free on this bus)
#define REQ_ID     0x200   // other nodes send this to request inverter status
#define STATUS_ID  0x201   // we reply with this (cached, instant)

#define SDO_SLOT_MS     100   // one poll slot per 100 ms
#define SDO_TIMEOUT_MS  200
#define OI_STALE_MS    5000   // param not refreshed this long → report 0.0

// ── Gear indicator (0x43F) driven from the inverter's seldir ─────────────────
// Replaces the standalone Arduino that used to send 0x43F.
// ⚠ SELDIR_PARAM_ID: set to seldir's unique id from YOUR parameters.json
//   (same id column where udc=2001). While it is 0, seldir polling and the
//   0x43F gear TX are both DISABLED and the old Arduino keeps working.
#define SELDIR_PARAM_ID  2018  // seldir unique id (from parameters.json)
#define TX43F_MS         50    // gear frame every 50 ms — matches the Arduino's cadence

// Exact frames from the retired Arduino gear sender (verified working):
static const uint8_t GEAR_FRAME_D[8] = { 0xC1, 0xF5, 0x80, 0xFF, 0x00, 0x00, 0x00, 0x00 };
static const uint8_t GEAR_FRAME_R[8] = { 0xC7, 0xF7, 0x80, 0xFF, 0x00, 0x00, 0x00, 0x00 };
static const uint8_t GEAR_FRAME_N[8] = { 0xC0, 0xF6, 0x80, 0xFF, 0x00, 0x00, 0x00, 0x00 };
static const uint8_t GEAR_FRAME_P[8] = { 0xC0, 0xF8, 0x80, 0xFF, 0x00, 0x00, 0x00, 0x00 };

// ⚠ OPMODE_PARAM_ID: verify with `oic -n 1 read opmode` / parameters.json —
//   2000 is its usual slot but confirm on YOUR firmware before trusting gear.
#define OPMODE_PARAM_ID  2000
#define OPMODE_RUN       1      // openinverter opmode: 0=Off, 1=Run, ...

// fweak's unique id. Doubles as the drive-mode fingerprint (see modeParams).
#define FWEAK_PARAM_ID   2

// uaux = the 12 V rail, id confirmed against the openinverter web UI 2026-08-15.
//
// The raw reading is HIGH: openinverter reported 16.4 V while a multimeter at
// the battery terminals measured 13.1 V. The aux sense divider on this hwver 3
// (Tesla) build simply isn't calibrated for this install, so correct it here.
//   13.1 / 16.4 = 0.799
// Single-point calibration, which assumes the sense is linear through zero —
// true for a resistive divider into an ADC. If the corrected value drifts off
// at a different rail voltage (e.g. reads right at rest but wrong with the
// DC-DC running), take a second meter reading and I'll add an offset term.
#define AUX_PARAM_ID     2021
#define AUX_SCALE        0.799f
// Below this while driving means the DC-DC is not charging and the car is
// running off the 12 V battery alone — that strands it even on a full pack.
#define AUX_WARN_VOLTS   12.0f

// Fault reporting, ids and enums straight from this inverter's own parameter
// dump (firmware 5.39.R-sine, 2026-08-21) — not guessed.
//   lasterr = the last fault that stopped the inverter
//   status  = a BITFIELD of current conditions; this is the one that explains
//             "it won't start", e.g. 512 = BrakeCheck, waiting for a brake press
#define LASTERR_PARAM_ID 2038
#define STATUS_PARAM_ID  2044

struct Param { const char *name; uint16_t id; float value; bool ok; uint32_t okMs; };
Param params[] = {
  {"udc",    2001, 0, false, 0},           // → batt (V)
  // idc = DC-link current — the correct signal for power/energy (P = udc×idc).
  // NOTE: it reads only a few amps at idle/no-load — that is PHYSICALLY
  // CORRECT (little real work being done). Do NOT swap to ilmax: that is
  // phase-current amplitude incl. magnetizing current, and using it inflated
  // efficiency ~15× (3200 Wh/km) on the road test of 2026-07-11.
  {"idc",    2002, 0, false, 0},           // → curr (A)
  {"tmphs",  2019, 0, false, 0},           // → btemp ("Inverter Temp" in app)
  {"tmpm",   2020, 0, false, 0},           // → mtemp (°C)
  {"seldir", SELDIR_PARAM_ID, 0, false, 0},// → gear: -1=D, 1=R (swapped on this car), 0=N
  {"opmode", OPMODE_PARAM_ID, 0, false, 0},// → gear gate: not RUN (or stale) = P
  // fweak is polled purely to IDENTIFY the drive mode. Its three values are
  // unique per mode, so reading it back tells us what the inverter is really
  // set to — which is the only truth after an ignition cycle, since the
  // inverter reloads its flash config and forgets anything we wrote.
  {"fweak",  FWEAK_PARAM_ID, 0, false, 0}, // → drive mode read-back
  {"uaux",   AUX_PARAM_ID,   0, false, 0}, // → "aux" 12 V rail (V)
  {"lasterr", LASTERR_PARAM_ID, 0, false, 0}, // → "err"    fault code
  {"status",  STATUS_PARAM_ID,  0, false, 0}  // → "status" condition bitfield
};
const int NUM_PARAMS = sizeof(params) / sizeof(params[0]);
#define IDX_UDC    0
#define IDX_CURR   1
#define IDX_TMPHS  2
#define IDX_TMPM   3
#define IDX_SELDIR 4
#define IDX_OPMODE 5
#define IDX_FWEAK   6
#define IDX_AUX     7
#define IDX_LASTERR 8
#define IDX_STATUS  9

// Weighted poll order (100 ms slots, 2.4 s full cycle):
//   seldir every 2nd slot (5 Hz → gear reacts in ~200 ms)
//   opmode every ~600 ms (gear P↔N/D gate)
//   ilmax + udc every ~600 ms (feed the app's live power/energy math)
//   tmphs / tmpm every ~2.4 s (temps drift, not jump)
static const uint8_t pollOrder[] = {
  IDX_CURR, IDX_SELDIR, IDX_UDC, IDX_SELDIR, IDX_OPMODE, IDX_SELDIR,
  IDX_CURR, IDX_SELDIR, IDX_UDC, IDX_SELDIR, IDX_TMPHS,  IDX_SELDIR,
  IDX_CURR, IDX_SELDIR, IDX_UDC, IDX_SELDIR, IDX_OPMODE, IDX_SELDIR,
  IDX_CURR, IDX_SELDIR, IDX_UDC, IDX_SELDIR, IDX_TMPM,   IDX_SELDIR,
  // fweak once per cycle (~2.6 s) — the mode only changes when we change it,
  // or when the user edits the inverter directly, so this is plenty.
  IDX_FWEAK, IDX_SELDIR,
  // uaux likewise: a 12 V rail fails slowly, so once per cycle is ample.
  IDX_AUX,   IDX_SELDIR,
  // Faults and status once per cycle (~3.2 s). Errors latch in the inverter
  // until cleared, so nothing is missed by not polling them faster.
  IDX_LASTERR, IDX_SELDIR,
  IDX_STATUS,  IDX_SELDIR
};
static const uint8_t POLL_ORDER_LEN = sizeof(pollOrder);

enum SdoState { SDO_IDLE, SDO_WAITING, SDO_WRITING, SDO_PROBING };
SdoState sdoState    = SDO_IDLE;
int      sdoParamIdx = 0;
uint32_t sdoSentAt   = 0;
uint8_t  sdoMatch[3];   // expected index-lo, index-hi, subindex of pending reply

// ── DRIVE MODES ───────────────────────────────────────────────────────────────
// Based on catphish's systematic SDU tuning (openinverter thread 2558).
// The causal map from that work:
//   fweak  = frequency at which FULL pack voltage is reached → sets the torque
//            knee AND peak DC current. LOWER = more power, earlier, more amps.
//   boost  = voltage at 0 rpm → launch torque only, independent of the top end.
//   fslipmax 3.0 = his proven optimum for the SDU: more torque per amp and far
//            cooler than 3.5 (he logged 57 °C vs 82 °C, 3.5 being SLOWER too).
//   throtramp = pedal response (5 ≈ 200 ms to full, 10 ≈ 100 ms).
//   fslipconstmax = whether torque survives above the knee (3.0 falls off,
//            4.0+ holds on for higher speeds).
//   idcmax = safety limiter; the limiter starts acting ~100 A BELOW its value,
//            so set it above the real target.
//
// ⚠⚠ SCALING WARNING: catphish tuned for 450–500 A on a pack rated for it.
// This car's pack is ~11.4 kWh (~33 Ah at 340 V) — his absolute numbers would
// be a ~13C abuse of these cells. The values below use his RATIOS scaled to a
// conservative target for this pack. They are STARTING POINTS to be verified
// with logging, exactly as he did.
//
// IDs and NORMAL values below are from THIS car's live config, read
// 2026-07-11 via http://192.168.4.1/cmd?cmd=json (firmware 5.39.R-sine,
// hwver 3 = Tesla). NORMAL is the car exactly as it drives today — which
// happens to be catphish's final recommended tune.
//   Reference: boost 3200 · fweak 230 · fslipmax 3.0 · fslipconstmax 3.0
//              throtramp 10 · throtmax 100 · tripmode 1 (correct/safe)
// A parameter with id 0 is skipped, so the table is safe to run partly filled.

enum DriveMode { MODE_ECO = 0, MODE_NORMAL = 1, MODE_SPORT = 2, MODE_COUNT = 3 };
static const char* MODE_NAMES[MODE_COUNT] = { "ECO", "NORMAL", "SPORT" };

struct ModeParam {
  const char* name;
  uint16_t    id;        // ⚠ unique id from parameters.json — 0 = skip
  float       val[MODE_COUNT];   // ECO, NORMAL, SPORT
};

static ModeParam modeParams[] = {
  // name            id    ECO     NORMAL  SPORT      (NORMAL = current tune)
  { "fweak",          2, { 270,    230,    220   } },  // ↑ = softer/later knee, less DC current
  { "boost",          1, { 2600,   3200,   3600  } },  // launch torque at 0 rpm
  { "fslipmax",      33, { 3.0f,   3.0f,   3.0f  } },  // unchanged: 3.0 is catphish's proven
                                                       // optimum — 3.5 was slower AND 25 °C hotter
  { "fslipconstmax", 100, { 3.0f,   3.0f,   4.0f  } }, // Sport holds torque past the knee
  { "throtramp",     81, { 5,      10,     20    } },  // %/10ms → 200 / 100 / 50 ms to full pedal
  { "throtmax",      97, { 75,     100,    100   } },  // Eco caps available torque
};
static const int NUM_MODE_PARAMS = sizeof(modeParams) / sizeof(modeParams[0]);

// Written by loop() (a request) and vehicleTask (applying it) — see vehMux.
static volatile int  activeMode      = MODE_NORMAL;
static volatile int  pendingMode     = -1;   // mode being written, -1 = none
static volatile int  writeIdx        = 0;    // which modeParam is being written
static volatile bool modeApplyFailed = false;
// Until fweak has been read back (or we have written a mode ourselves) the
// inverter's mode is unknown, and saying "NORMAL" was a guess the app showed
// as fact at every start.
static volatile bool modeWrittenThisBoot = false;

// Safety: never change the V/Hz curve while the car is moving — boost/fweak
// alter torque instantly at a given pedal position.
#define MODE_CHANGE_MAX_KMH  3.0f

static bool paramFresh(const Param& p) {
  return p.ok && p.okMs != 0 && millis() - p.okMs < OI_STALE_MS;
}

// ── Drive mode READ-BACK ──────────────────────────────────────────────────────
// activeMode below only records what WE last wrote. That is not the same thing
// as what the inverter holds: it reloads its flash config on power-down, and it
// can also be changed by hand in the web UI. So the mode shown in the app is
// derived from the fweak value read back over SDO, and falls back to activeMode
// only until the first successful read lands.
static int modeDiscRow() {           // the modeParams row used as fingerprint
  for (int i = 0; i < NUM_MODE_PARAMS; i++)
    if (modeParams[i].id == FWEAK_PARAM_ID) return i;
  return -1;
}

static const char* reportedModeName() {
  int row = modeDiscRow();
  if (row < 0 || !paramFresh(params[IDX_FWEAK]))
    return modeWrittenThisBoot ? MODE_NAMES[activeMode] : "";   // "" = not known yet
  float f = params[IDX_FWEAK].value;
  for (int m = 0; m < MODE_COUNT; m++)
    if (fabsf(f - modeParams[row].val[m]) < 2.0f) return MODE_NAMES[m];
  // fweak sits outside all three presets — someone tuned it directly. Say so
  // rather than rounding to the nearest mode and showing something untrue.
  return "CUSTOM";
}
float getBattVolt()   { return paramFresh(params[IDX_UDC])   ? params[IDX_UDC].value   : 0.0f; }
// |idc| × IDC_SCALE: this conversion has NO regen, so battery current is
// always discharge; magnitude handles the swapped sign convention.
// IDC_SCALE calibrates openinverter's idc estimate against reality.
// History: 3.0 → 0.8 (three-trip SOC calibration, il1gain/il2gain = 1.5)
// → 1.3 → 1.0 (2026-07-11 road feedback) → 1.85 (2026-10-14, test drive:
// battery 100 → 74 % ≈ 2.99 kWh while the app integrated 1.58 kWh at 1.0,
// ratio 1.89; 1.85 is the middle of the 1.8–1.9 estimate).
// idc is the inverter's own estimate and leaves out the DC-DC, so this one
// factor also absorbs the 12 V load.
// ⚠ Recalibrate if il1gain/il2gain ever change:
//   new IDC_SCALE = current IDC_SCALE × (ΔSOC × pack Wh) / (app session Wh)
#define IDC_SCALE  1.85f
float getBattCurr()   { return paramFresh(params[IDX_CURR])  ? fabsf(params[IDX_CURR].value) * IDC_SCALE : 0.0f; }
float getBattTempC()  { return paramFresh(params[IDX_TMPHS]) ? params[IDX_TMPHS].value : 0.0f; }  // heatsink
float getMotorTempC() { return paramFresh(params[IDX_TMPM])  ? params[IDX_TMPM].value  : 0.0f; }
float getAuxVolt()    { return paramFresh(params[IDX_AUX])   ? params[IDX_AUX].value * AUX_SCALE : 0.0f; }

// ── Fault decoding ────────────────────────────────────────────────────────────
// Names verbatim from this inverter's parameter dump, so they match what the
// openinverter web UI shows. -1 means we have not read it yet.
static const char* LASTERR_NAMES[] = {
  "NONE", "OVERCURRENT", "THROTTLE1", "THROTTLE2", "CANTIMEOUT", "EMCYSTOP",
  "MPROT", "DESAT", "OVERVOLTAGE", "ENCODER", "PRECHARGE", "TMPHSMAX",
  "CURRENTLIMIT", "PWMSTUCK", "HICUROFS1", "HICUROFS2", "HIRESOFS",
  "LORESAMP", "TMPMMAX", "CANCRC", "CANCOUNTER"
};
static const int LASTERR_N = sizeof(LASTERR_NAMES) / sizeof(LASTERR_NAMES[0]);

int getLastErrCode() {
  if (!paramFresh(params[IDX_LASTERR])) return -1;
  return (int)roundf(params[IDX_LASTERR].value);
}

const char* lastErrName() {
  int c = getLastErrCode();
  if (c < 0) return "?";                       // not read yet
  if (c >= LASTERR_N) return "UNKNOWN";        // newer firmware than this table
  return LASTERR_NAMES[c];
}

// status is a bitfield, so several conditions can be true at once.
static const uint16_t STATUS_BITS[]  = { 1, 2, 4, 8, 16, 32, 64, 128, 256, 512 };
static const char*    STATUS_NAMES[] = {
  "UdcLow", "UdcHigh", "UdcBelowUdcSw", "UdcLim", "EmcyStop",
  "MProt", "PotPressed", "TmpHs", "WaitStart", "BrakeCheck"
};
static const int STATUS_N = sizeof(STATUS_BITS) / sizeof(STATUS_BITS[0]);

int getStatusBits() {
  if (!paramFresh(params[IDX_STATUS])) return -1;
  return (int)roundf(params[IDX_STATUS].value);
}

/** Set flags joined by "|", or "None". Writes into caller's buffer. */
void statusText(char* dst, size_t n) {
  int s = getStatusBits();
  if (s < 0)  { snprintf(dst, n, "?");    return; }
  if (s == 0) { snprintf(dst, n, "None"); return; }
  dst[0] = 0;
  size_t used = 0;
  for (int i = 0; i < STATUS_N; i++) {
    if (!(s & STATUS_BITS[i])) continue;
    int w = snprintf(dst + used, n - used, "%s%s", used ? "|" : "", STATUS_NAMES[i]);
    if (w <= 0 || (size_t)w >= n - used) break;
    used += w;
  }
  if (used == 0) snprintf(dst, n, "0x%X", s);   // bits outside the known set
}

// Gear logic — SAFETY FIRST: an engaged direction is ALWAYS displayed,
// regardless of run state, so the driver can never leave the car in D or R
// without the cluster saying so.
//   seldir -1 → D, +1 → R (polarity swapped on this install) — unconditional.
//   No direction selected: opmode gates P vs N —
//     in RUN → N, not in RUN (or opmode/seldir stale) → P.
char getGear() {
  if (paramFresh(params[IDX_SELDIR])) {
    if (params[IDX_SELDIR].value < -0.5f) return 'D';
    if (params[IDX_SELDIR].value >  0.5f) return 'R';
  }
  if (!paramFresh(params[IDX_OPMODE])) return 'P';
  if ((int)roundf(params[IDX_OPMODE].value) != OPMODE_RUN) return 'P';
  if (!paramFresh(params[IDX_SELDIR])) return 'P';
  return 'N';
}

// ── Rev counter (0x316) driven by battery current ─────────────────────────────
// The tach becomes a live POWER gauge: needle RPM = |idc| × RPM_PER_AMP.
// Cluster decodes RPM = (byte3·256 + byte2) / 6.4 (verified by calibration:
// byte3 0x18→~1000, 0x40→~2500, 0x71→~4500 rpm), so we encode raw = rpm × 6.4.
// Static bytes below are from the bus sniff table (ign-on / engine-running
// flags) — tweak them if the needle stays dead despite RPM bytes changing.
// Calibrated 2026-07-11 at IDC_SCALE 1.0: hard-pull peak ≈ 128 A × 35 ≈ 4500 rpm
// — full throttle lands at the top of the dial, cruise reads 1000–2000.
// Divided by IDC_SCALE so recalibrating the current doesn't move the needle
// (at 1.85 the same pull reads ≈ 237 A × 18.9 rpm/A).
#define RPM_PER_AMP  (35.0f / IDC_SCALE)
#define RPM_MAX      6000.0f
static twai_message_t tx316;

// ── Pre-built 0x329 frame (gauge TX from boot, unconditional) ─────────────────
// Byte 1 is LIVE: updated from the inverter's motor temp (tmpm) so the
// cluster's temp gauge shows real drivetrain temperature.
//   gauge reads:  T(°C) = 0.75 × byte1 − 48.373
//   we transmit:  byte1 = (T + 48.373) / 0.75
// Falls back to the static TARGET_COOLANT_C while tmpm is stale (inverter off)
// so the gauge sits at a normal position instead of ice cold.
static twai_message_t tx329;
static esp_timer_handle_t txTimer = nullptr;
static bool timerRunning = false;                 // the 10 ms timer itself
static volatile bool tx329UserEnabled = true;     // STOP329 / START329 (USB only)

// Gear frame for the timer to send; vehicleTask points it at the right one.
// P until the inverter has said otherwise — and an engaged direction is always
// shown once known (see getGear()).
static const uint8_t* volatile g_gearFrame = GEAR_FRAME_P;

// One 10 ms timer feeds every cluster frame: coolant 0x329 and tach 0x316 each
// tick, gear 0x43F every 50 ms. It runs in the esp_timer task, so neither a
// blocked loop() nor a busy vehicleTask can starve the cluster. It is never
// stopped for bus-off any more: while the bus is off or recovering,
// twai_transmit() simply fails fast, and frames resume the moment it is back.
static void onGaugeTimer(void*) {
  static uint8_t tick = 0;
  if (tx329UserEnabled) {
    if (twai_transmit(&tx329, 0) == ESP_OK && g_firstTxMs == 0)
      g_firstTxMs = (uint32_t)(esp_timer_get_time() / 1000);
    twai_transmit(&tx316, 0);
  }
  if (++tick >= TX43F_MS / 10) {
    tick = 0;
    if (SELDIR_PARAM_ID != 0) {          // 0 = the old Arduino still owns 0x43F
      twai_message_t g = {};
      g.identifier       = 0x43F;
      g.data_length_code = 8;
      memcpy(g.data, (const void*)g_gearFrame, 8);
      twai_transmit(&g, 0);
    }
  }
}

static void startGaugeTimer() {
  if (!timerRunning && txTimer) { esp_timer_start_periodic(txTimer, 10000); timerRunning = true; }
}

// ── Offline ring buffer ───────────────────────────────────────────────────────
// 300 samples at 500 ms = 2.5 min, ~6 KB. Was 1200 (10 min, 24 KB) back when
// RAM was the only offline store. The SD card is now primary and unbounded, so
// this is purely the no-card fallback and does not need to be large — the RAM
// it was holding is worth more as free heap for the Bluetooth stack.
#define BUF_SIZE 300

struct Sample {
  uint32_t tsMs;
  float    spd;
  uint8_t  soc;
  int16_t  battX10;
  int16_t  currX10;
  int16_t  btempX10;
  int16_t  mtempX10;
};

static Sample   ringBuf[BUF_SIZE];
static uint16_t bufHead  = 0;
static uint16_t bufCount = 0;
static bool     synced   = false;

// ── Chunked flush state ───────────────────────────────────────────────────────
#define FLUSH_LINES_PER_PASS 10
static bool     flushing   = false;
static uint16_t flushIdx   = 0;
static uint16_t flushSent  = 0;
static uint16_t flushTotal = 0;

// ── SD card log ───────────────────────────────────────────────────────────────
// The RAM ring buffer above holds 10 minutes and then silently overwrites its
// oldest sample, so a long stretch without the phone lost data permanently.
// The card replaces it as the primary store: every sample is appended as CSV,
// nothing is ever deleted, and a persistent cursor records how far the app has
// been brought up to date. The ring buffer stays as the fallback for a missing
// or failed card, so the firmware still works with no card fitted.
//
// Defined further down the file. Declared here rather than trusting the IDE's
// automatic prototype generation, which has already caused one build failure
// in this sketch by hoisting a prototype above the type it referenced.
float    getCurrentSpeed();
uint32_t getOdometerKm();

// WeAct CAN485 DevBoard TF slot. No clash with CAN (26/27) or fuel PWM (25).
#define SD_CS    13
#define SD_SCK   14
#define SD_MOSI  15
#define SD_MISO   2

// Field order for every row. Written as the first line of each new file so the
// card is self-describing when opened on a PC.
static const char* SD_HEADER =
  "ts_ms,speed_kmh,pack_v,pack_a,inv_c,motor_c,aux_v,mode\n";

// Rows are batched in RAM and appended every few seconds rather than on every
// sample: one SD transaction per row would both stall loop() and wear the card.
#define SD_FLUSH_MS    5000
#define SD_PEND_LIMIT   900

// Ceiling on one sync. Without it, a card holding days of history would try to
// push a million records over SPP in a single session — hours of transfer, and
// the app's database would drown. Anything left over goes on the next connect,
// so nothing is skipped; at ~2 samples/s logged against ~100/s sent, a backlog
// drains far faster than it builds.
#define SYNC_MAX_RECORDS 20000

// Pacing. serviceSdFlush() is called from loop(), which runs thousands of times
// a second — unpaced it would push tens of thousands of rows per second into a
// link that carries maybe fifty. BT.print() then blocks hard, loop() starves,
// and the gear/gauge timers suffer. 10 rows per 100 ms is ~100 rows/s, which
// SPP can actually absorb, so a 1000-row backlog drains in about ten seconds.
#define SYNC_PACE_MS     100

// Write the cursor to NVS every this many rows, not only when the sync ends.
// A sync interrupted by a reboot or a dropped link used to lose ALL progress,
// so the next connect replayed from the old position while new rows piled up
// behind it — the backlog appeared to grow rather than shrink.
// Checkpoint interval. At SYNC_PACE_MS this is roughly every half second, so a
// dropped link or a reboot costs at most that much re-sent data. It was 200
// (~2 s), and anything that killed the link faster than that threw away every
// record sent since the last checkpoint — which looks exactly like the sync
// replaying forever and never finishing.
#define SYNC_SAVE_EVERY  50

static SPIClass sdSpi(HSPI);
static bool     sdOk        = false;
static uint16_t sdFileNum   = 0;      // file being written this session
static char     sdPath[20]  = "";
// Fixed buffer, not a String: this appends every 500 ms for hours, and repeated
// String growth would fragment the heap on a device that never restarts.
static char     sdPend[1024];
static uint16_t sdPendLen   = 0;
static uint32_t sdLastWrite = 0;
static uint32_t sdRows      = 0;      // rows written this session
static uint32_t sdFileBytes = 0;      // bytes in sdPath on the card (header + rows)
// True from the moment the app connects (its catch-up sync already started)
// until it disconnects. Rows logged in a live session reach the app as live
// packets, so the cursor may move past them — see sdServiceEx().
static bool     liveSession = false;

// How far the app has been caught up: a (file, byte offset) pair. Persisted, so
// a reboot neither resends old data nor loses its place.
static Preferences sdPrefs;
static uint16_t syncFile = 0;
static uint32_t syncOfs  = 0;

// Sync-from-card state
static bool     sdFlushing    = false;
static bool     sdSyncOpen    = false;
static File     syncFh;
static uint32_t syncSentThis  = 0;
static uint32_t syncLastPace  = 0;
static uint32_t syncSinceSave = 0;
// Where the card ended when this sync began. Rows appended after that point
// arrive as live packets during the sync, so the replay stops here.
static uint16_t syncStopFile  = 0;
static uint32_t syncStopOfs   = 0;
static bool     syncCapped    = false;   // ended on SYNC_MAX_RECORDS, not caught up

static void sdPathFor(char* dst, size_t n, uint16_t num) {
  snprintf(dst, n, "/LOG%04u.CSV", num);
}

static void sdSaveCursor() {
  if (!sdOk) return;
  sdPrefs.putUShort("syncfile", syncFile);
  sdPrefs.putULong("syncofs",  syncOfs);
}

void sdInit() {
  // GPIO2 is MISO on this board and has no external pull-up, while the ESP32
  // boots it with an internal pull-DOWN. An SD card needs MISO idling high or
  // it never answers, so assert the pull-up before probing the bus.
  pinMode(SD_MISO, INPUT_PULLUP);
  sdSpi.begin(SD_SCK, SD_MISO, SD_MOSI, SD_CS);

  // Walk down the clock. A cheap card, a microSD adapter, or long traces can
  // all fail at the 4 MHz default yet work perfectly at 1 MHz — and a slow
  // mount is infinitely better than no mount, since we only write a few
  // hundred bytes every 5 seconds.
  static const uint32_t RATES[] = { 4000000, 2000000, 1000000, 400000 };
  bool mounted = false;
  for (int i = 0; i < 4 && !mounted; i++) {
    if (SD.begin(SD_CS, sdSpi, RATES[i])) {
      mounted = true;
      Serial.printf("[SD] mounted at %lu Hz\n", (unsigned long)RATES[i]);
    } else {
      SD.end();
      delay(50);
    }
  }

  if (!mounted) {
    // Mount failure covers several different problems, so name them all — a
    // card that is present but exFAT looks identical to no card at all here.
    // This library cannot format, only mount an existing FAT filesystem.
    Serial.println("[SD] mount FAILED at every clock rate. Check, in order:");
    Serial.println("     1. card actually seated (push until it clicks)");
    Serial.println("     2. formatted FAT32/FAT16 with an MBR partition —");
    Serial.println("        exFAT, the default above 32 GB, will NOT mount");
    Serial.println("     3. pins: CS=13 SCK=14 MOSI=15 MISO=2 for the WeAct");
    Serial.println("        CAN485 TF slot. A different board needs these changed.");
    Serial.println("[SD] 'SDMOUNT' retries without rebooting.");
    Serial.println("[SD] falling back to the 10 min RAM ring buffer");
    return;
  }

  uint8_t ct = SD.cardType();
  Serial.printf("[SD] card type: %s\n",
    ct == CARD_NONE ? "NONE (reader responded but no card)" :
    ct == CARD_MMC  ? "MMC"  : ct == CARD_SD   ? "SDSC" :
    ct == CARD_SDHC ? "SDHC" : "UNKNOWN");

  sdPrefs.begin("evlog", false);
  // Each boot gets its own file: there is no RTC, so a monotonic session
  // number is the only honest way to order them.
  sdFileNum = sdPrefs.getUShort("nextfile", 1);
  sdPrefs.putUShort("nextfile", (uint16_t)(sdFileNum + 1));

  syncFile = sdPrefs.getUShort("syncfile", 0);
  syncOfs  = sdPrefs.getULong("syncofs", 0);
  if (syncFile == 0) { syncFile = sdFileNum; syncOfs = 0; }   // first ever boot

  sdPathFor(sdPath, sizeof(sdPath), sdFileNum);
  File f = SD.open(sdPath, FILE_WRITE);
  if (!f) {
    Serial.printf("[SD] cannot create %s — using the RAM ring buffer\n", sdPath);
    return;
  }
  f.print(SD_HEADER);
  f.close();
  sdFileBytes = strlen(SD_HEADER);

  sdOk = true;
  uint64_t mb = SD.cardSize() / (1024ULL * 1024ULL);
  Serial.printf("[SD] card %llu MB   logging to %s   app is caught up to LOG%04u @ %lu\n",
                mb, sdPath, syncFile, (unsigned long)syncOfs);
}

/** Queue one sample. Cheap — no card access here. */
void sdLog() {
  if (!sdOk) return;
  char row[128];
  int n = snprintf(row, sizeof(row), "%lu,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%s\n",
    (unsigned long)millis(), getCurrentSpeed(), getBattVolt(), getBattCurr(),
    getBattTempC(), getMotorTempC(), getAuxVolt(), reportedModeName());
  if (n <= 0) return;
  if (sdPendLen + n >= sizeof(sdPend)) return;   // committed on the next pass
  memcpy(sdPend + sdPendLen, row, n);
  sdPendLen += n;
  sdPend[sdPendLen] = 0;
}

/** Commit queued rows. sdServiceForce() bypasses the timer, before a sync.
 *  mayAdvance=false keeps the cursor still (used for the pre-sync flush, whose
 *  rows were logged while the app was away and still have to be replayed). */
static void sdServiceEx(bool force, bool mayAdvance) {
  if (!sdOk || sdPendLen == 0) return;
  if (!force && sdPendLen < SD_PEND_LIMIT
             && millis() - sdLastWrite < SD_FLUSH_MS) return;

  File f = SD.open(sdPath, FILE_APPEND);
  if (!f) {
    // Card pulled mid-drive, or the filesystem gave up. Drop to the ring
    // buffer rather than silently discarding everything from here on.
    Serial.println("[SD] append failed — card removed? falling back to RAM buffer");
    sdOk = false;
    sdPendLen = 0;
    return;
  }
  f.write((const uint8_t*)sdPend, sdPendLen);
  f.close();
  sdFileBytes += sdPendLen;
  for (uint16_t i = 0; i < sdPendLen; i++) if (sdPend[i] == '\n') sdRows++;
  sdPendLen = 0;
  sdLastWrite = millis();

  // Rows logged during a live session reached the app as live packets, so it
  // already has them — move the cursor past them. Before v7.3 the cursor only
  // ever moved during a sync, so every connect replayed the whole previous
  // drive (minutes of "Syncing…" with live data held back) and the app counted
  // that drive twice. Not while syncing: the sync owns the cursor until it
  // finishes, and sdEndFlush() then jumps it to the end of the file.
  if (mayAdvance && liveSession && !sdFlushing) {
    syncFile = sdFileNum;
    syncOfs  = sdFileBytes;
    sdSaveCursor();
  }
}

// Separate entry points rather than a default argument: the IDE's automatic
// prototype generation drops default values, which turns a no-arg call into a
// build error.
void sdService()      { sdServiceEx(false, true); }
void sdServiceForce() { sdServiceEx(true,  true); }

// ── Fault log ─────────────────────────────────────────────────────────────────
// One line per CHANGE of lasterr, appended to a single file that is never
// rotated — a fault history is only useful if it outlives the drive it happened
// on. Logged on both directions: a fault appearing and a fault clearing.
//
// Reference time is the odometer plus ESP32 uptime. There is no RTC, so the
// odometer is the only value that means anything months later: "PRECHARGE at
// 143120 km" is actionable, "PRECHARGE at 4381 ms" is not.
#define ERR_LOG_PATH "/ERRORS.CSV"
static const char* ERR_HEADER = "esp_ms,odo_km,code,name,status_bits,status\n";
static int errLast = -2;                        // -2 = nothing seen yet

void sdLogError(int code) {
  if (!sdOk) return;
  bool fresh = !SD.exists(ERR_LOG_PATH);
  File f = SD.open(ERR_LOG_PATH, FILE_APPEND);
  if (!f) { Serial.println("[ERR] could not write " ERR_LOG_PATH); return; }
  if (fresh) f.print(ERR_HEADER);
  char st[96]; statusText(st, sizeof(st));
  char row[192];
  snprintf(row, sizeof(row), "%lu,%lu,%d,%s,%d,%s\n",
    (unsigned long)millis(), (unsigned long)getOdometerKm(),
    code, (code >= 0 && code < LASTERR_N) ? LASTERR_NAMES[code] : "UNKNOWN",
    getStatusBits(), st);
  f.print(row);
  f.close();
}

/** Called every loop; writes only on a transition. */
void serviceErrorLog() {
  int c = getLastErrCode();
  if (c < 0) return;                            // not read yet — not a change
  if (c == errLast) return;
  int prev = errLast;
  errLast = c;
  if (prev == -2) {
    // First successful read after boot. Only worth recording if it is an
    // actual fault; logging "NONE" on every power-up would bury the real ones.
    if (c == 0) return;
    Serial.printf("[ERR] inverter reports %s (latched from before boot)\n",
                  lastErrName());
  } else {
    Serial.printf("[ERR] %s -> %s\n",
                  (prev >= 0 && prev < LASTERR_N) ? LASTERR_NAMES[prev] : "?",
                  lastErrName());
  }
  sdLogError(c);
}

/** Unsent bytes across every file from the cursor to the one being written.
 *  Stops once there is more than one sync's worth: every file opened costs
 *  card time, and there is one file per boot, so a cursor left far behind
 *  used to mean hundreds of opens on every connect. */
static uint32_t sdBacklogBytes() {
  uint32_t total = 0;
  const uint32_t enough = (uint32_t)SYNC_MAX_RECORDS * 48;
  uint16_t opened = 0;
  for (uint16_t n = syncFile; n <= sdFileNum; n++) {
    if (total >= enough || ++opened > 200) break;
    char p[20]; sdPathFor(p, sizeof(p), n);
    File f = SD.open(p, FILE_READ);
    if (!f) continue;                       // gap in the numbering — skip
    uint32_t sz = f.size();
    f.close();
    if (n == syncFile) total += (sz > syncOfs) ? sz - syncOfs : 0;
    else               total += sz;
  }
  return total;
}

/** For the periodic serial summary: the full count opens files, so refresh it
 *  once a minute rather than every three seconds. */
static uint32_t sdBacklogCached() {
  static uint32_t val = 0, at = 0;
  if (at == 0 || millis() - at >= 60000) { val = sdBacklogBytes(); at = millis(); }
  return val;
}

// ─────────────────────────────────────────────────────────────────────────────
BluetoothSerial BT;

// Per-ID last data + change counters.
//
// This was a full 2048-entry table indexed directly by CAN id, from the days of
// discovering what was on the bus. At 20 bytes an entry that is 40 KB of static
// RAM permanently unavailable to the heap — and with the SD card's FATFS
// buffers also wanting 20-30 KB, free heap fell to 13 KB and Bluetooth could no
// longer accept a connection. Discovery is finished, and only two ids are
// actually read (0x153 speed, 0x613 odometer), so this is now a small
// find-or-create list: ~24 slots instead of 2048.
#define TRACK_MAX 24
struct IdEntry {
  uint16_t id;
  bool     seen;
  uint8_t  dlc;
  uint8_t  data[8];
  uint32_t count;
  uint32_t changes;
};
static IdEntry track[TRACK_MAX];
static uint8_t trackN = 0;

// These return an INDEX, not an IdEntry*. The IDE hoists generated prototypes
// above the struct definition, so a function signature mentioning IdEntry fails
// to compile — this sketch has already been bitten by exactly that.
int trackFind(uint16_t id) {
  for (uint8_t i = 0; i < trackN; i++) if (track[i].id == id) return i;
  return -1;
}

/** Find, or claim a free slot. -1 when the list is full. */
int trackGet(uint16_t id) {
  int i = trackFind(id);
  if (i >= 0) return i;
  if (trackN >= TRACK_MAX) return -1;      // ignore ids beyond the first 24
  i = trackN++;
  track[i].id = id;
  return i;
}


// ── Land Rover CAN decoding ───────────────────────────────────────────────────
// Decoded in vehicleTask the moment a frame arrives (decodeLandRover), so the
// readers below — loop(), the OTA and drive-mode gates — always see one whole
// value rather than bytes copied mid-update from the tracking list.
float    getCurrentSpeed() { return g_speedKmh; }
uint32_t getOdometerKm()   { return g_odoKm; }

static void decodeLandRover(const twai_message_t& m, uint16_t id) {
  if (id == 0x153 && m.data_length_code >= 3)
    g_speedKmh = (((uint16_t)m.data[2] << 4) | (m.data[1] >> 4)) / 8.0f;
  else if (id == 0x613 && m.data_length_code >= 2)
    g_odoKm = (((uint32_t)m.data[1] << 8) | m.data[0]) * 10;
}

// (Oil temp 0x618 and the fuel-gauge SOC proxy are no longer decoded — speed
// and odometer are the only Land Rover values used. SOC comes from pack
// voltage via the app's voltage-SOC settings, so "soc" is sent as 0.)

// TODO: brake / A-C bits still unidentified — operate them while watching the
// per-ID "changes" counters in the serial summary, then decode here.
bool getBrakeOn() { return false; }
bool getAcOn()    { return false; }

void bufferPush() {
  ringBuf[bufHead] = {
    millis(), getCurrentSpeed(), 0 /* soc: app derives it from voltage */,
    (int16_t)(getBattVolt()  * 10), (int16_t)(getBattCurr()  * 10),
    (int16_t)(getBattTempC() * 10), (int16_t)(getMotorTempC() * 10)
  };
  bufHead = (bufHead + 1) % BUF_SIZE;
  if (bufCount < BUF_SIZE) bufCount++;
}

// ── SDO transactions (non-blocking, one in flight) ────────────────────────────
// ── Parameter discovery (SCAN / READ) ────────────────────────────────────────
// Reads arbitrary unique IDs so an unknown parameter can be located without the
// inverter's parameters.json — uaux (the 12 V rail) being the reason this
// exists. STRICTLY READ-ONLY: it emits 0x40 upload requests and nothing else,
// so it cannot alter the inverter no matter what id is swept.
// Requested from the USB console (loop), run by vehicleTask — see vehMux.
static volatile int probeId   = -1;    // id currently being probed; -1 = not scanning
static volatile int probeEnd  = -1;    // last id in the requested range
static volatile int probeHits = 0;

void sdoStartProbe(uint16_t pid) {
  uint16_t index = 0x2100 | (pid >> 8);
  twai_message_t msg = {};
  msg.identifier = SDO_REQ;
  msg.data_length_code = 8;
  msg.data[0] = 0x40;                       // SDO upload (read) request
  msg.data[1] = sdoMatch[0] = index & 0xFF;
  msg.data[2] = sdoMatch[1] = index >> 8;
  msg.data[3] = sdoMatch[2] = pid & 0xFF;
  if (twai_transmit(&msg, 0) == ESP_OK) {
    sdoState  = SDO_PROBING;
    sdoSentAt = millis();
  }
}

void sdoStartRead(int paramIdx) {
  uint16_t pid = params[paramIdx].id;
  uint16_t index = 0x2100 | (pid >> 8);     // unique-ID addressing
  twai_message_t msg = {};
  msg.identifier = SDO_REQ;
  msg.data_length_code = 8;
  msg.data[0] = 0x40;                       // SDO upload request
  msg.data[1] = sdoMatch[0] = index & 0xFF;
  msg.data[2] = sdoMatch[1] = index >> 8;
  msg.data[3] = sdoMatch[2] = pid & 0xFF;
  if (twai_transmit(&msg, 0) == ESP_OK) {
    sdoState    = SDO_WAITING;
    sdoParamIdx = paramIdx;
    sdoSentAt   = millis();
  }
}

// SDO expedited DOWNLOAD (write) — 0x23 = 4-byte write. Same unique-ID
// addressing as reads; value is s32 fixed point with 5 fractional bits.
void sdoStartWrite(uint16_t paramId, float value) {
  uint16_t index = 0x2100 | (paramId >> 8);
  int32_t  raw   = (int32_t)lroundf(value * 32.0f);
  twai_message_t msg = {};
  msg.identifier = SDO_REQ;
  msg.data_length_code = 8;
  msg.data[0] = 0x23;                       // expedited download, 4 bytes
  msg.data[1] = sdoMatch[0] = index & 0xFF;
  msg.data[2] = sdoMatch[1] = index >> 8;
  msg.data[3] = sdoMatch[2] = paramId & 0xFF;
  memcpy(&msg.data[4], &raw, 4);
  if (twai_transmit(&msg, 0) == ESP_OK) {
    sdoState  = SDO_WRITING;
    sdoSentAt = millis();
  }
}

void handleSdoReply(const twai_message_t &rx) {
  if (rx.data[1] != sdoMatch[0] || rx.data[2] != sdoMatch[1]
      || rx.data[3] != sdoMatch[2]) return;   // stale/mismatched reply

  if (sdoState == SDO_WRITING) {              // reply to a mode-parameter write
    if (rx.data[0] == 0x80) {                 // abort — wrong id or read-only
      uint32_t code; memcpy(&code, &rx.data[4], 4);
      Serial.printf("[MODE] write %s FAILED: abort 0x%08lX\n",
                    modeParams[writeIdx].name, (unsigned long)code);
      modeApplyFailed = true;
    }
    writeIdx++;
    sdoState = SDO_IDLE;
    return;
  }

  if (sdoState == SDO_PROBING) {              // discovery scan
    if (rx.data[0] != 0x80) {                 // 0x80 = abort, i.e. no such id
      int32_t raw; memcpy(&raw, &rx.data[4], 4);
      Serial.printf("  id %4d = %12.3f\n", probeId, raw / 32.0f);
      probeHits++;
    }
    probeId++;
    sdoState = SDO_IDLE;
    return;
  }

  if (sdoState != SDO_WAITING) return;
  Param& p = params[sdoParamIdx];
  if (rx.data[0] == 0x80) {                   // SDO abort
    p.ok = false;
  } else {
    int32_t raw;
    memcpy(&raw, &rx.data[4], 4);
    p.value = raw / 32.0f;                    // s32 fixed point, 5 frac bits
    p.ok    = true;
    p.okMs  = millis();
  }
  sdoState = SDO_IDLE;
}

// Request a mode change. Returns false (with reason) if not allowed right now.
// This is the ONLY path that can write to the inverter, and it is reachable
// from a paired phone, so every guard here matters. It can write nothing but
// the six parameters in modeParams — no caller chooses an id.
bool requestDriveMode(int mode, const char** why) {
  if (mode < 0 || mode >= MODE_COUNT)      { *why = "invalid mode";       return false; }
  if (getCurrentSpeed() > MODE_CHANGE_MAX_KMH) {
    *why = "vehicle moving — stop first";  return false;
  }
  // Refuse while the inverter is running. boost/fweak change torque at a given
  // pedal position the instant they land, and the inverter rejects the writes
  // in RUN anyway — so this turns a half-applied tune plus "SOME WRITES FAILED"
  // into a clean refusal the app can show.
  if (paramFresh(params[IDX_OPMODE])
      && (int)roundf(params[IDX_OPMODE].value) == OPMODE_RUN) {
    *why = "inverter running — switch off first";  return false;
  }
  // Called from loop(); applied by vehicleTask. Set the progress fields BEFORE
  // pendingMode, which is what vehicleTask keys on, all under the lock so the
  // check and the set can't straddle a change finishing on the other task.
  portENTER_CRITICAL(&vehMux);
  bool busy = pendingMode >= 0;
  if (!busy) {
    writeIdx        = 0;
    modeApplyFailed = false;
    pendingMode     = mode;
  }
  portEXIT_CRITICAL(&vehMux);
  if (busy) { *why = "change in progress"; return false; }
  Serial.printf("[MODE] applying %s...\n", MODE_NAMES[mode]);
  return true;
}

// Walks the parameter list, one SDO write at a time (only one transaction may
// be in flight). Parameters with id 0 are skipped so a partly-filled table is
// still safe to run.
void serviceDriveMode() {
  if (pendingMode < 0) return;
  if (sdoState != SDO_IDLE) return;
  while (writeIdx < NUM_MODE_PARAMS && modeParams[writeIdx].id == 0) writeIdx++;
  if (writeIdx >= NUM_MODE_PARAMS) {
    portENTER_CRITICAL(&vehMux);
    activeMode          = pendingMode;
    modeWrittenThisBoot = true;
    pendingMode         = -1;
    portEXIT_CRITICAL(&vehMux);
    // Drop the cached fweak so the next poll re-reads it. Until that lands,
    // reportedModeName() falls back to activeMode, which we just set — so the
    // app shows the new mode immediately and then has it confirmed from the bus.
    params[IDX_FWEAK].ok = false;
    Serial.printf("[MODE] %s active%s\n", MODE_NAMES[activeMode],
                  modeApplyFailed ? " (SOME WRITES FAILED — check ids)" : "");
    return;
  }
  ModeParam& mp = modeParams[writeIdx];
  sdoStartWrite(mp.id, mp.val[pendingMode]);
  Serial.printf("[MODE]   %s = %.2f\n", mp.name, mp.val[pendingMode]);
}

void serviceSdoPolling() {
  static uint8_t orderPos = 0;
  static uint32_t lastPoll = 0;
  if (sdoState == SDO_WAITING && millis() - sdoSentAt > SDO_TIMEOUT_MS) {
    params[sdoParamIdx].ok = false;           // timeout
    sdoState = SDO_IDLE;
  }
  if (sdoState == SDO_WRITING && millis() - sdoSentAt > SDO_TIMEOUT_MS) {
    Serial.printf("[MODE] write %s TIMEOUT\n", modeParams[writeIdx].name);
    modeApplyFailed = true;
    writeIdx++;
    sdoState = SDO_IDLE;
  }
  if (sdoState == SDO_PROBING && millis() - sdoSentAt > SDO_TIMEOUT_MS) {
    probeId++;              // no reply = no such parameter; move on quietly
    sdoState = SDO_IDLE;
  }
  if (pendingMode >= 0) return;               // mode writes take priority

  // A discovery scan takes the bus ahead of routine polling so it finishes
  // quickly. Gear/seldir polling stalls meanwhile, which is why SCAN refuses
  // to run while the inverter is in RUN.
  if (probeId >= 0) {
    if (sdoState == SDO_IDLE) {
      if (probeId > probeEnd) {
        Serial.printf("[SCAN] done — %d parameters answered.\n", probeHits);
        probeId = -1;
      } else {
        sdoStartProbe((uint16_t)probeId);
      }
    }
    return;
  }

  if (sdoState == SDO_IDLE && millis() - lastPoll >= SDO_SLOT_MS) {
    lastPoll = millis();
    // Walk the interleaved order, skipping params without a configured id
    for (uint8_t tries = 0; tries < POLL_ORDER_LEN; tries++) {
      uint8_t idx = pollOrder[orderPos];
      orderPos = (orderPos + 1) % POLL_ORDER_LEN;
      if (params[idx].id != 0) { sdoStartRead(idx); break; }
    }
  }
}

// ── Live temp-gauge update: tmpm → 0x329 byte 1 ───────────────────────────────
// Single-byte write is atomic, so no locking needed against the 10 ms TX timer.
void serviceGaugeTemp() {
  static uint32_t lastUpd = 0;
  if (millis() - lastUpd < 500) return;
  lastUpd = millis();
  float display;
  if (paramFresh(params[IDX_TMPM])) {
    // Remap motor range onto the gauge's comfortable zone (see defines above)
    float frac = (params[IDX_TMPM].value - MOTOR_T_MIN) / (MOTOR_T_MAX - MOTOR_T_MIN);
    display = GAUGE_T_MIN + constrain(frac, 0.0f, 1.0f) * (GAUGE_T_MAX - GAUGE_T_MIN);
  } else {
    display = TARGET_COOLANT_C;   // inverter offline → steady neutral position
  }
  int b = (int)roundf((display + 48.373f) / 0.75f);
  tx329.data[1] = (uint8_t)constrain(b, 0, 255);
}

// ── Live rev-counter update: |idc| → 0x316 bytes 2/3 ─────────────────────────
void serviceGaugeRpm() {
  static uint32_t lastUpd = 0;
  if (millis() - lastUpd < 200) return;
  lastUpd = millis();
  float rpm = getBattCurr() * RPM_PER_AMP;          // 0 when inverter off/stale
  uint16_t raw = (uint16_t)(constrain(rpm, 0.0f, RPM_MAX) * 6.4f);
  tx316.data[2] = raw & 0xFF;                       // RPM LSB
  tx316.data[3] = raw >> 8;                         // RPM MSB
}

// ── Fuel gauge via vactrol (VTL5C4 on the old fuel-sender wires) ─────────────
// The ESP32 dims the vactrol LED; its photocell replaces the sender, so the
// cluster's fuel needle shows battery SOC. SOC is computed HERE (not in the
// app) so the gauge works with or without the phone connected.
//
// Match the app Settings (Voltage at 100% / 0%).
#define PACK_V_FULL    340.0f   // pack voltage at 100% SOC
#define PACK_V_EMPTY   270.0f   // pack voltage at 0% SOC

// ── Pack volts → state of charge ──────────────────────────────────────────────
// ONE table, written directly in PACK VOLTS: 270 V = 0 %, 340 V = 100 %.
//
// Not a straight line, because an NMC pack isn't. Its open-circuit voltage has
// steep knees at both ends and a long flat plateau through the middle, so a
// linear map overstates remaining charge by 15-20 points across the middle of
// the range.
//
// Derived from a standard NMC open-circuit curve at 84 cells in series:
//   340 V = 4.05 V/cell (100 %)    270 V = 3.21 V/cell (0 %)
// Note the plateau: 300 V to 310 V is worth 22 points of SOC, while the same
// 10 V swing near either end is worth about half that.
//
// MUST match SOC_V / SOC_P in DashboardViewModel.kt — the app's dial and this
// physical needle are driven from the same numbers and have to agree.
static const float SOC_V[] = { 270, 275, 280, 285, 290, 295, 300, 305,
                               310, 315, 320, 325, 330, 335, 340 };
static const float SOC_P[] = {   0,   1,   4,   9,  15,  23,  32,  44,
                                54,  63,  72,  80,  87,  94, 100 };
static const int   SOC_N = sizeof(SOC_V) / sizeof(SOC_V[0]);

// Terminal voltage sags under load, which reads as lost charge, so SOC is taken
// from estimated open-circuit voltage (v + i*PACK_R) rather than the raw
// reading. 0.12 ohm is typical for an 84S pack — a 200 A pull sags it ~24 V.
#define PACK_R  0.12f

// PACK_V_FULL/EMPTY are mapped proportionally onto the table's 270-340 V span,
// so changing the charge limit still gives a true 0 % and 100 % at the ends.
static float socFromOcv(float ocvPack) {
  if (PACK_V_FULL - PACK_V_EMPTY < 1.0f) return 0.0f;   // nonsense config
  float lo = SOC_V[0], hi = SOC_V[SOC_N - 1];
  float v = lo + (ocvPack - PACK_V_EMPTY) / (PACK_V_FULL - PACK_V_EMPTY) * (hi - lo);
  if (v <= lo) return 0.0f;
  if (v >= hi) return 100.0f;
  for (int i = 1; i < SOC_N; i++)
    if (v <= SOC_V[i])
      return SOC_P[i-1] + (SOC_P[i] - SOC_P[i-1]) *
             (v - SOC_V[i-1]) / (SOC_V[i] - SOC_V[i-1]);
  return 100.0f;
}
#define FUEL_PWM_PIN   GPIO_NUM_25
// 12-bit PWM, not the default 8-bit. Measurement showed the whole upper half
// of the gauge lives below 8 % drive, which at 8-bit is duty 0..20 of 255 —
// about 4 counts per eighth of a tank. 12 bits gives ~41 counts per 1 % drive,
// so the needle can actually be placed. Must match fuel_gauge_calibrate.ino.
#define FUEL_PWM_BITS   12
#define FUEL_PWM_MAX    4095.0f
// Outlier guard only. IR compensation now handles normal sag, so this exists
// solely to reject samples where the linear i*R model breaks down.
#define FUEL_GATE_AMPS_MAX  250.0f
#define FUEL_EMA_ALPHA  0.02f   // slow — a fuel gauge should be well damped

// Calibration (measured 2026-08-15, ignition-cycled so the needle settles):
//   drive %  5,6,7   8      10  11   14   17   20    30   40   50    55  60    70    80  100
//   needle    >F  just <F  7/8 7/8  3/4  5/8  4.2/8 3.8/8 3/8 2.2/8 1/4 1.8/8 1.7/8 1/8  E
// Inverted below: a needle at e/8 is what SOC = e/8*100 should look like,
// so these are (SOC → drive %) with drive falling as SOC rises.
// Two hard floors: 0 % drive is an open circuit and pins the gauge to EMPTY,
// while 7 % already pushes past the FULL stop and 8 % sits a hair under it.
// 100 % SOC therefore maps to 7.5 — the only value that lands ON full.
static const float FUEL_SOC_PTS[]   = { 0, 12.5, 22, 25, 27.5, 37.5, 47.5, 52.5, 62.5, 75, 87.5, 100 };
static const float FUEL_DRIVE_PTS[] = { 100, 80,  65, 55, 50,   40,   30,   20,   17,   14, 10.5, 7.5 };
static const int   FUEL_NPTS = 12;
static volatile float fuelVolt = 0;    // slow-smoothed pack OCV behind the needle
static volatile int   fuelOverride = -1;  // -1 = SOC-driven; 0..100 = forced drive (calibration)
// What was ACTUALLY written to the pin, for the serial summary. Lets a stuck
// needle be told apart from a firmware that is not driving anything.
static volatile int   fuelDriveApplied = -1;

// ── Fuel needle across ignition cycles (v7.3) ────────────────────────────────
// The cluster reads the sender the moment it wakes, then damps hard, so the
// needle has to be right from power-on. Two things used to make it wrong:
//  1. The LED was off until setup() finished and stayed off until the inverter
//     reported a voltage — an open circuit, which reads below EMPTY.
//  2. This car precharges on the start signal (bootprec = 0), so before RUN the
//     inverter's udc is 0 or a capacitor bank bleeding down. A bleed-down
//     reading seeded the estimate near 0 %, and the 2 %-a-step smoothing plus
//     the cluster's damping then took ages to climb back — or never seemed to.
// Now: the last good estimate is kept in NVS and driven from power-on; udc is
// only trusted once the inverter has left Off (contactors closed) and reads
// like a pack; and the first live reading SETS the estimate (median of three)
// rather than being smoothed into it.
static Preferences fuelPrefs;
static bool        fuelLiveSeeded = false;   // set from live pack voltage this boot
static float       fuelSeedBuf[3];
static uint8_t     fuelSeedN      = 0;
static float       fuelSavedOcv   = 0;       // last value written to NVS
static uint32_t    fuelLastSaveMs = 0;
static const char* volatile g_fuelSrc = "default";   // "saved" | "live" | "default"
#define FUEL_V_MIN_OK      (PACK_V_EMPTY - 30.0f)    // 240 V: below this it isn't the pack
#define FUEL_V_MAX_OK      (PACK_V_FULL  + 20.0f)    // 360 V
#define FUEL_SAVE_MS       60000                     // NVS at most once a minute
#define FUEL_SAVE_DELTA_V  0.5f                      // ...and only when it has moved
#define FUEL_DEFAULT_SOC   50.0f                     // first boot ever: no history yet

float fuelDriveForSoc(float soc) {
  if (soc <= FUEL_SOC_PTS[0])            return FUEL_DRIVE_PTS[0];
  if (soc >= FUEL_SOC_PTS[FUEL_NPTS - 1])return FUEL_DRIVE_PTS[FUEL_NPTS - 1];
  for (int i = 1; i < FUEL_NPTS; i++) {
    if (soc <= FUEL_SOC_PTS[i]) {
      float t = (soc - FUEL_SOC_PTS[i-1]) / (FUEL_SOC_PTS[i] - FUEL_SOC_PTS[i-1]);
      return FUEL_DRIVE_PTS[i-1] + t * (FUEL_DRIVE_PTS[i] - FUEL_DRIVE_PTS[i-1]);
    }
  }
  return FUEL_DRIVE_PTS[FUEL_NPTS - 1];
}

// ── Driving the vactrol ───────────────────────────────────────────────────────
// Straight, unconditional application of the target drive level.
//
// A "bright pulse before settling" was tried here to cancel the CdS cell's
// light-history hysteresis, and it made things WORSE. The VTL5C4 is strongly
// asymmetric: resistance falls within milliseconds when the LED is lit, but
// takes seconds to recover when it is dimmed — measured on this very part,
// creeping slowly up toward 600 kΩ in the dark. So each bright pulse dropped
// the resistance instantly and it never climbed all the way back before the
// next one. Low resistance reads EMPTY on this gauge, so the needle sat
// permanently low. Do not reintroduce it without solving that asymmetry.
//
// The residual problem the pulse was meant to fix is still real but small:
// roughly a third of an eighth of spread at the FULL end across ignition
// cycles. The proper fix is to replace the vactrol with a digital
// potentiometer, which has no light history at all.
static void fuelWrite(float drive) {
  fuelDriveApplied = (int)roundf(drive);
  analogWrite(FUEL_PWM_PIN, (int)roundf(drive * FUEL_PWM_MAX / 100.0f));
}

/** Power-on: put the needle where it was last time, before anything slow runs.
 *  Called first thing in setup(), so the LED is never dark while the cluster
 *  takes its wake-up reading. */
static void fuelBegin() {
  analogWriteResolution(FUEL_PWM_PIN, FUEL_PWM_BITS);   // before the first write
  fuelPrefs.begin("fuel", false);
  float saved = fuelPrefs.getFloat("ocv", 0.0f);
  if (saved >= FUEL_V_MIN_OK && saved <= FUEL_V_MAX_OK) {
    fuelVolt     = saved;
    fuelSavedOcv = saved;
    g_fuelSrc    = "saved";
    fuelWrite(fuelDriveForSoc(socFromOcv(saved)));
  } else {
    g_fuelSrc = "default";                     // first boot: mid-scale, not open
    fuelWrite(fuelDriveForSoc(FUEL_DEFAULT_SOC));
  }
}

/** True once the DC link is actually connected to the pack. Before the start
 *  signal this car's inverter sits in Off with its contactors open, and udc is
 *  whatever the capacitors still hold. */
static bool inverterDcLinkUp() {
  return paramFresh(params[IDX_OPMODE]) && (int)roundf(params[IDX_OPMODE].value) != 0;
}

void serviceFuelGauge() {
  static uint32_t last = 0;
  if (millis() - last < 500) return;
  last = millis();
  if (fuelOverride >= 0) {   // calibration: force a fixed drive %, ignore SOC
    fuelWrite((float)fuelOverride);
    return;
  }

  float v = getBattVolt();
  bool  usable = inverterDcLinkUp() && v >= FUEL_V_MIN_OK && v <= FUEL_V_MAX_OK;
  if (usable) {
    float i = getBattCurr();
    // Smooth the IR-COMPENSATED voltage, not the terminal voltage. Adding back
    // i*R removes the sag, so the estimate stays valid while driving. The gate
    // is only an outlier guard: a linear IR model stops holding at extreme current.
    float ocv = v + i * PACK_R;
    if (!fuelLiveSeeded) {
      // First live readings this boot SET the estimate — it may have charged
      // since the saved value. Median of three, so one odd sample can't.
      if (i <= FUEL_GATE_AMPS_MAX) fuelSeedBuf[fuelSeedN++] = ocv;
      if (fuelSeedN >= 3) {
        float a = fuelSeedBuf[0], b = fuelSeedBuf[1], c = fuelSeedBuf[2];
        fuelVolt       = fmaxf(fminf(a, b), fminf(fmaxf(a, b), c));
        fuelLiveSeeded = true;
        fuelSeedN      = 0;
        g_fuelSrc      = "live";
      }
    } else if (i <= FUEL_GATE_AMPS_MAX) {
      fuelVolt += FUEL_EMA_ALPHA * (ocv - fuelVolt);
    }
    // Keep the next power-on's starting point current. Rarely, and only on a
    // real change: this is flash.
    if (fuelLiveSeeded && millis() - fuelLastSaveMs >= FUEL_SAVE_MS
        && fabsf(fuelVolt - fuelSavedOcv) >= FUEL_SAVE_DELTA_V) {
      fuelPrefs.putFloat("ocv", fuelVolt);
      fuelSavedOcv   = fuelVolt;
      fuelLastSaveMs = millis();
    }
  }
  // Always drive the LED from the current estimate. Never leave it dark: an
  // open circuit pins the needle below EMPTY. With no usable voltage this
  // simply holds the saved (or last live) level.
  if (fuelVolt > 0.0f) fuelWrite(fuelDriveForSoc(socFromOcv(fuelVolt)));
}

// ── Gear frame (0x43F) — replaces the standalone Arduino ─────────────────────
// Only CHOOSES the frame; the 10 ms gauge timer sends it every 50 ms (see
// onGaugeTimer). Sending it from loop() meant any stall there — a blocked
// Bluetooth write, a slow card — silenced the gear display.
void updateGearFrame() {
  char g = getGear();
  g_gearFrame = (g == 'D') ? GEAR_FRAME_D
              : (g == 'R') ? GEAR_FRAME_R
              : (g == 'N') ? GEAR_FRAME_N
              :              GEAR_FRAME_P;
}

// ── Custom protocol: status frame from cache (4 × int16 LE, value ×10) ────────
static uint32_t statusRepliesSent = 0;

void sendStatusFrame() {
  statusRepliesSent++;
  twai_message_t msg = {};
  msg.identifier = STATUS_ID;
  msg.data_length_code = 8;
  int16_t v;
  v = (int16_t)(params[IDX_UDC].value   * 10); memcpy(&msg.data[0], &v, 2);  // udc
  v = (int16_t)(params[IDX_CURR].value  * 10); memcpy(&msg.data[2], &v, 2);  // idc
  v = (int16_t)(params[IDX_TMPHS].value * 10); memcpy(&msg.data[4], &v, 2);  // tmphs
  v = (int16_t)(params[IDX_TMPM].value  * 10); memcpy(&msg.data[6], &v, 2);  // tmpm
  twai_transmit(&msg, 0);
}

// ── History sync (auto on connect; chunked so loop() never stalls) ────────────

// One logged CSV row → the same {"type":"data"} packet the app already parses,
// so the replay path is identical whether it came from the card or from RAM.
static void sdSendRow(const char* row) {
  unsigned long ts; float spd, v, a, bt, mt, aux;
  // Seven fields is enough; the trailing mode column is ignored on replay.
  if (sscanf(row, "%lu,%f,%f,%f,%f,%f,%f",
             &ts, &spd, &v, &a, &bt, &mt, &aux) < 7) return;
  char out[220];
  snprintf(out, sizeof(out),
    "{\"type\":\"data\",\"ts\":%lu,\"spd\":%.1f,\"soc\":0,\"batt\":%.1f,"
    "\"curr\":%.1f,\"btemp\":%.1f,\"mtemp\":%.1f,\"aux\":%.1f,"
    "\"brake\":false,\"ac\":false}\n",
    ts, spd, v, a, bt, mt, aux);
  BT.print(out);
}

static void sdEndFlush(bool complete) {
  if (sdSyncOpen) { syncFh.close(); sdSyncOpen = false; }
  sdFlushing = false;
  flushing   = false;
  // Caught up, and still connected: everything appended since the sync began
  // went out as live packets, so jump the cursor to the end of the card.
  // (Not when the per-session cap ended it — the rest is still owed.)
  if (complete && !syncCapped && liveSession) {
    syncFile = sdFileNum;
    syncOfs  = sdFileBytes;
  }
  // Save the cursor either way: an aborted sync has still delivered everything
  // up to this point, and resending it would duplicate rows in the app.
  sdSaveCursor();
  if (complete) {
    BT.printf("{\"type\":\"sync_end\"}\n");
    synced = true;
    Serial.printf("[SD] sync complete — %lu records sent, cursor LOG%04u @ %lu\n",
                  (unsigned long)syncSentThis, syncFile, (unsigned long)syncOfs);
  } else {
    Serial.printf("[SD] sync aborted after %lu records — resumes from LOG%04u @ %lu\n",
                  (unsigned long)syncSentThis, syncFile, (unsigned long)syncOfs);
  }
}

static void sdBeginFlush() {
  // Get queued rows onto the card first — WITHOUT moving the cursor: these
  // were logged while the app was away, so they are exactly what this sync
  // has to deliver.
  sdServiceEx(true, false);
  syncSentThis  = 0;
  syncSinceSave = 0;
  syncLastPace  = 0;
  syncCapped    = false;
  syncStopFile  = sdFileNum;           // replay up to here; later rows go live
  syncStopOfs   = sdFileBytes;
  uint32_t est = sdBacklogBytes() / 48;          // ~48 bytes per CSV row
  if (est > SYNC_MAX_RECORDS) est = SYNC_MAX_RECORDS;
  BT.printf("{\"type\":\"sync_start\",\"count\":%lu}\n", (unsigned long)est);
  if (est == 0) { BT.printf("{\"type\":\"sync_end\"}\n"); return; }
  flushing   = true;
  sdFlushing = true;
  // The cursor is printed on every sync so a replay is obvious: if two
  // consecutive syncs start from the SAME LOG/offset, nothing is being
  // persisted and the fault is the cursor, not the transfer.
  static uint32_t syncAttempt = 0;
  Serial.printf("[SD] sync #%lu started: ~%lu records from LOG%04u @ %lu\n",
                (unsigned long)++syncAttempt, (unsigned long)est,
                syncFile, (unsigned long)syncOfs);
}

static void serviceSdFlush() {
  if (!BT.hasClient()) { sdEndFlush(false); return; }

  // Pace the send. Without this, loop() hammers BT.print() until it blocks,
  // starving the CAN pump and the gauge timers.
  if (millis() - syncLastPace < SYNC_PACE_MS) return;
  syncLastPace = millis();

  if (!sdSyncOpen) {
    char p[20]; sdPathFor(p, sizeof(p), syncFile);
    syncFh = SD.open(p, FILE_READ);
    if (!syncFh) {
      // Missing file number (card swapped, file deleted). Step over it, and
      // persist that — otherwise a restart re-walks the same dead entry.
      if (syncFile < sdFileNum) { syncFile++; syncOfs = 0; sdSaveCursor(); return; }
      sdEndFlush(true);
      return;
    }
    syncFh.seek(syncOfs);
    sdSyncOpen = true;
  }

  for (uint8_t n = 0; n < FLUSH_LINES_PER_PASS; n++) {
    if (syncSentThis >= SYNC_MAX_RECORDS) {      // bounded; rest goes next time
      Serial.println("[SD] per-session cap reached — remainder follows on the next connect");
      syncCapped = true;
      sdEndFlush(true);
      return;
    }
    // Reached where the card ended when this sync began: anything after that
    // has been arriving as live packets meanwhile.
    if (syncFile == syncStopFile && syncOfs >= syncStopOfs) {
      sdEndFlush(true);
      return;
    }
    if (!syncFh.available()) {                   // end of this file
      syncOfs = syncFh.position();
      syncFh.close(); sdSyncOpen = false;
      if (syncFile < sdFileNum) {
        syncFile++; syncOfs = 0;
        sdSaveCursor();       // persist the boundary, or we re-send this file
        return;
      }
      sdEndFlush(true);
      return;
    }
    // readBytesUntil into a fixed buffer, not readStringUntil: a String per
    // row would allocate thousands of times during a long sync.
    char row[128];
    size_t len = syncFh.readBytesUntil('\n', row, sizeof(row) - 1);
    row[len] = 0;
    syncOfs = syncFh.position();
    if (len < 5) continue;
    if (strncmp(row, "ts_ms", 5) == 0) continue;  // header line
    sdSendRow(row);
    syncSentThis++;

    // Checkpoint the cursor as we go. If this sync dies half way — dropped
    // link, brownout, reboot — everything already delivered stays delivered.
    if (++syncSinceSave >= SYNC_SAVE_EVERY) {
      syncSinceSave = 0;
      sdSaveCursor();
      Serial.printf("[SD] sync %lu records, cursor LOG%04u @ %lu\n",
                    (unsigned long)syncSentThis, syncFile, (unsigned long)syncOfs);
    }
  }
}

void beginFlush() {
  if (flushing) return;
  if (sdOk) { sdBeginFlush(); return; }

  // No card — fall back to the RAM ring buffer.
  BT.printf("{\"type\":\"sync_start\",\"count\":%u}\n", bufCount);
  if (bufCount == 0) {
    BT.printf("{\"type\":\"sync_end\"}\n");
    return;
  }
  flushing   = true;
  flushTotal = bufCount;
  flushSent  = 0;
  flushIdx   = (bufCount < BUF_SIZE) ? 0 : bufHead;
  Serial.printf("[BUF] Sync started: %u records\n", flushTotal);
}

void serviceFlush() {
  if (sdFlushing) { serviceSdFlush(); return; }
  if (!flushing) return;
  if (!BT.hasClient()) { flushing = false; Serial.println("[BUF] Sync aborted."); return; }
  char line[200];
  for (uint8_t n = 0; n < FLUSH_LINES_PER_PASS && flushSent < flushTotal; n++) {
    Sample& s = ringBuf[(flushIdx + flushSent) % BUF_SIZE];
    snprintf(line, sizeof(line),
      "{\"type\":\"data\",\"ts\":%lu,\"spd\":%.1f,\"soc\":%u,\"batt\":%.1f,"
      "\"curr\":%.1f,\"btemp\":%.1f,\"mtemp\":%.1f,\"brake\":false,\"ac\":false}\n",
      (unsigned long)s.tsMs, s.spd, s.soc, s.battX10 / 10.0f,
      s.currX10 / 10.0f, s.btempX10 / 10.0f, s.mtempX10 / 10.0f);
    BT.print(line);
    flushSent++;
  }
  if (flushSent >= flushTotal) {
    BT.printf("{\"type\":\"sync_end\"}\n");
    flushing = false;
    bufHead  = 0;
    bufCount = 0;
    synced   = true;
    Serial.println("[BUF] Sync complete.");
  }
}

// ── Live packet (typeless — matches the app's real-time path) ─────────────────
void sendBTJson() {
  // Live packets keep flowing during a sync (v7.3). They used to be held back
  // for the whole replay — up to minutes of a frozen dashboard after connect.
  // The app tells them apart: replayed rows carry "type":"data", live ones none.
  if (!BT.hasClient()) return;
  char stText[96]; statusText(stText, sizeof(stText));
  char buf[420];
  snprintf(buf, sizeof(buf),
    "{\"spd\":%.1f,\"soc\":%u,\"batt\":%.1f,\"curr\":%.1f,"
    "\"btemp\":%.1f,\"mtemp\":%.1f,\"aux\":%.1f,"
    "\"err\":%d,\"errname\":\"%s\",\"status\":%d,\"statustext\":\"%s\","
    "\"brake\":%s,\"ac\":%s,\"mode\":\"%s\",\"dc\":%s}\n",
    getCurrentSpeed(), 0 /* soc: app derives it from voltage */, getBattVolt(), getBattCurr(),
    getBattTempC(), getMotorTempC(), getAuxVolt(),
    getLastErrCode(), lastErrName(), getStatusBits(), stText,
    getBrakeOn() ? "true" : "false", getAcOn() ? "true" : "false",
    reportedModeName(),    // read back from the inverter, not assumed
    inverterDcLinkUp() ? "true" : "false");   // same gate as the fuel needle
  BT.print(buf);
}

// ── Serial (USB) commands ─────────────────────────────────────────────────────
void handleCommand(const char* cmd) {
  if      (strcmp(cmd, "SYNC") == 0)     beginFlush();
  // The timer keeps running (it also carries the gear frame); these only mute
  // or restore the coolant/tach frames on it.
  else if (strcmp(cmd, "STOP329") == 0)  { tx329UserEnabled = false; Serial.println("[329] stopped (0x329 + 0x316)"); }
  else if (strcmp(cmd, "START329") == 0) { tx329UserEnabled = true;  Serial.println("[329] started"); }
  // Drive mode: "ECO" / "NORMAL" / "SPORT", or "MODE" to report the current one
  else if (strcasecmp(cmd, "ECO") == 0 || strcasecmp(cmd, "NORMAL") == 0
        || strcasecmp(cmd, "SPORT") == 0) {
    int m = (strcasecmp(cmd, "ECO") == 0) ? MODE_ECO
          : (strcasecmp(cmd, "SPORT") == 0) ? MODE_SPORT : MODE_NORMAL;
    const char* why = "";
    if (!requestDriveMode(m, &why)) Serial.printf("[MODE] refused: %s\n", why);
  }
  else if (strcasecmp(cmd, "MODE") == 0) {
    Serial.printf("[MODE] active = %s\n", MODE_NAMES[activeMode]);
    for (int i = 0; i < NUM_MODE_PARAMS; i++)
      Serial.printf("   %-14s id %-5u eco %-7.2f normal %-7.2f sport %-7.2f%s\n",
        modeParams[i].name, modeParams[i].id, modeParams[i].val[0],
        modeParams[i].val[1], modeParams[i].val[2],
        modeParams[i].id == 0 ? "   << id NOT SET (skipped)" : "");
  }
  // Fuel-gauge calibration:
  //   FUEL 30      force 30 % drive
  //   FUEL AUTO    back to SOC-driven
  // SDMOUNT — retry the card without a reboot, for testing a fresh format
  else if (strcmp(cmd, "SDMOUNT") == 0) {
    if (sdOk) Serial.println("[SD] already mounted — SDINFO for details");
    else { SD.end(); delay(100); sdInit(); }
  }
  // SD card:
  //   SDINFO     card state, file list, how far the app is caught up
  //   SDREWIND   send the whole card again on the next connect
  else if (strcmp(cmd, "SDINFO") == 0) {
    if (!sdOk) { Serial.println("[SD] no card"); }
    else {
      sdServiceForce();
      Serial.printf("[SD] writing %s   %lu rows this session\n",
                    sdPath, (unsigned long)sdRows);
      Serial.printf("[SD] app caught up to LOG%04u @ %lu   unsent ~%lu rows\n",
                    syncFile, (unsigned long)syncOfs,
                    (unsigned long)(sdBacklogBytes() / 48));
      for (uint16_t n = 1; n <= sdFileNum; n++) {
        char p[20]; sdPathFor(p, sizeof(p), n);
        File f = SD.open(p, FILE_READ);
        if (!f) continue;
        Serial.printf("     %s  %lu bytes\n", p, (unsigned long)f.size());
        f.close();
      }
    }
  }
  // ERRLOG — print the whole fault history from the card
  else if (strcmp(cmd, "ERRLOG") == 0) {
    if (!sdOk) { Serial.println("[ERR] no card — history is not being kept"); }
    else {
      File f = SD.open(ERR_LOG_PATH, FILE_READ);
      if (!f) Serial.println("[ERR] no faults logged yet");
      else {
        Serial.printf("[ERR] %s (%lu bytes)\n", ERR_LOG_PATH, (unsigned long)f.size());
        while (f.available()) {
          char row[192];
          size_t n = f.readBytesUntil('\n', row, sizeof(row) - 1);
          row[n] = 0;
          if (n) Serial.printf("     %s\n", row);
        }
        f.close();
      }
    }
  }
  else if (strcmp(cmd, "SDREWIND") == 0) {
    if (!sdOk) Serial.println("[SD] no card");
    else {
      syncFile = 1; syncOfs = 0; sdSaveCursor();
      Serial.println("[SD] cursor reset to the first file — the next connect");
      Serial.println("     replays everything, capped per session. Expect duplicates");
      Serial.println("     in the app for anything it already had.");
    }
  }
  // Parameter discovery (read-only):
  //   SCAN            sweep ids 2000-2060, where openinverter keeps spot values
  //   SCAN 2000 2100  sweep an explicit range
  //   READ 2003       read one id
  else if (strncmp(cmd, "SCAN", 4) == 0) {
    if (paramFresh(params[IDX_OPMODE]) &&
        (int)roundf(params[IDX_OPMODE].value) == OPMODE_RUN) {
      Serial.println("[SCAN] refused — inverter is in RUN. A scan monopolises");
      Serial.println("       the bus and would stall the gear display. Park first.");
    } else {
      const char* a = cmd + 4;
      while (*a == ' ') a++;
      int from = 2000, to = 2060;
      if (*a) {
        char* end = nullptr;
        from = (int)strtol(a, &end, 10);
        while (end && (*end == ' ' || *end == '-')) end++;
        to = (end && *end) ? (int)strtol(end, nullptr, 10) : from;
      }
      from = constrain(from, 0, 65535);
      to   = constrain(to,   from, from + 400);   // bound the sweep
      // vehicleTask starts scanning as soon as probeId >= 0, so set it last.
      portENTER_CRITICAL(&vehMux);
      probeEnd = to; probeHits = 0; probeId = from;
      portEXIT_CRITICAL(&vehMux);
      Serial.printf("[SCAN] reading ids %d..%d — only ids that answer are listed.\n",
                    from, to);
      Serial.println("       Look for a value near 12-14: that is uaux, the 12 V rail.");
    }
  }
  else if (strncmp(cmd, "READ", 4) == 0) {
    const char* a = cmd + 4;
    while (*a == ' ') a++;
    int id = (int)strtol(a, nullptr, 10);
    if (id <= 0) Serial.println("  Usage: READ <id>");
    else {
      portENTER_CRITICAL(&vehMux);
      probeEnd = id; probeHits = 0; probeId = id;
      portEXIT_CRITICAL(&vehMux);
      Serial.printf("[SCAN] reading id %d\n", id);
    }
  }
  else if (strncmp(cmd, "FUEL", 4) == 0) {
    const char* a = cmd + 4;
    while (*a == ' ') a++;
    if (strncasecmp(a, "AUTO", 4) == 0) {
      fuelOverride = -1;
      Serial.println("[FUEL] auto (SOC-driven)");
    } else {
      // Clamped 0..100: this drives the physical gauge, and an out-of-range
      // duty would either open the sender circuit or peg the needle.
      fuelOverride = constrain(atoi(a), 0, 100);
      Serial.printf("[FUEL] forced drive %d %%  ('FUEL AUTO' to resume SOC)\n",
                    fuelOverride);
    }
  }
}

// USB serial keeps the full command set — it requires physical access to the
// board, which is a stronger control than anything Bluetooth offers here.
void pollSerialCommands() {
  static char buf[40];
  static uint8_t len = 0;
  static bool overflow = false;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (len && !overflow) { buf[len] = '\0'; handleCommand(buf); }
      len = 0; overflow = false;
    } else if (len < sizeof(buf) - 1) {
      buf[len++] = c;
    } else {
      // Discard the whole over-long line. Resetting len mid-line would let the
      // tail be parsed as a command in its own right.
      overflow = true;
    }
  }
}

// Same command set accepted from the Android app over Bluetooth, so the
// dashboard's drive-mode selector can drive it. Replies with a mode line
// the app can parse.
// ── Firmware update over Bluetooth (OTA) ─────────────────────────────────────
// The unit lives sealed in the console with no USB or SD access, so the app
// pushes new firmware over the same SPP link it already uses.
//
//   app:  OTA <size> <md5hex>\n
//   esp:  {"type":"ota","state":"ready","chunk":C,...}   (or state "error")
//   app:  C raw bytes (last chunk may be shorter)
//   esp:  {"type":"ota","state":"ack","off":N}   once that chunk is in flash
//   ...until N == size...
//   esp:  {"type":"ota","state":"done"} → reboot into the new image
//
// Stop-and-wait on purpose: BluetoothSerial's receive queue is only 512 bytes
// and DISCARDS on overflow, and a flash sector erase stalls this loop for tens
// of ms. One chunk in flight can never overflow it. RFCOMM is reliable and
// ordered, and Update.end() MD5-checks the whole image before it is made
// bootable, so a failure or power cut mid-transfer leaves the running firmware
// untouched.
//
// SECURITY: this lets a paired device replace the firmware. It is gated like
// drive-mode writes (stationary, inverter not in RUN); PIN pairing is the
// outer gate — keep BT_PIN private.
#define OTA_CHUNK      400     // must stay below BluetoothSerial's 512-byte RX queue
#define OTA_IDLE_MS  15000     // no bytes for this long → abort, keep old firmware

#if defined(CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE) || defined(CONFIG_APP_ROLLBACK_ENABLE)
#define OTA_ROLLBACK 1
#else
#define OTA_ROLLBACK 0
#endif

static bool     otaActive = false;
static uint32_t otaSize   = 0;
static uint32_t otaRecv   = 0;     // bytes committed to Update
static uint32_t otaFill   = 0;     // bytes buffered in the current chunk
static uint32_t otaLastRx = 0;
static uint8_t  otaBuf[OTA_CHUNK];
static bool     btLinkUp = false;

// ── Image identity check ─────────────────────────────────────────────────────
// Flashing anything that can't itself take a Bluetooth update — the fuel-gauge
// calibration sketch, a pre-v7.1 build, someone else's ESP32 code — would end
// OTA for good on a unit sealed in the console (the core confirms such an
// image at its first boot, so rollback can't save it). So the incoming bytes
// are scanned for this firmware's banner, and Update.end() is refused unless
// it is there with a version of 7.1 or later.
// ⚠ setup() prints "Freelander EV Vehicle Interface " FW_VERSION — that literal
//   IS the identity. Keep it exactly, or no later build can be installed OTA.
static const char OTA_ID_PREFIX[] = "Freelander EV Vehicle Interface v";
static uint8_t otaIdPos     = 0;       // prefix characters matched so far
static bool    otaIdCapture = false;   // reading the version digits after it
static char    otaIdVer[8];
static uint8_t otaIdVerLen  = 0;
static bool    otaIdOk      = false;   // banner found with version >= 7.1

static void otaIdReset() {
  otaIdPos = 0; otaIdCapture = false; otaIdVerLen = 0; otaIdOk = false;
}

static void otaIdFeed(uint8_t b) {
  if (otaIdOk) return;
  if (otaIdCapture) {
    if ((b >= '0' && b <= '9') || b == '.') {
      if (otaIdVerLen < sizeof(otaIdVer) - 1) otaIdVer[otaIdVerLen++] = (char)b;
      return;
    }
    otaIdVer[otaIdVerLen] = 0;
    otaIdCapture = false;
    int vMaj = 0, vMin = 0;
    if (sscanf(otaIdVer, "%d.%d", &vMaj, &vMin) == 2 && (vMaj > 7 || (vMaj == 7 && vMin >= 1)))
      otaIdOk = true;
    otaIdVerLen = 0;
    // fall through: this byte may itself start another banner
  }
  // The prefix's first letter appears nowhere else in it, so a plain restart
  // on mismatch is a correct substring search.
  const uint8_t L = sizeof(OTA_ID_PREFIX) - 1;
  if (b == (uint8_t)OTA_ID_PREFIX[otaIdPos]) {
    if (++otaIdPos == L) { otaIdPos = 0; otaIdCapture = true; otaIdVerLen = 0; }
  } else {
    otaIdPos = (b == (uint8_t)OTA_ID_PREFIX[0]) ? 1 : 0;
  }
}

static void otaReply(const char* state, const char* msg = nullptr) {
  if (!BT.hasClient()) return;
  if (msg) BT.printf("{\"type\":\"ota\",\"state\":\"%s\",\"msg\":\"%s\"}\n", state, msg);
  else     BT.printf("{\"type\":\"ota\",\"state\":\"%s\"}\n", state);
}

static void otaAbort(const char* why) {
  if (Update.isRunning()) Update.abort();
  otaActive = false;
  otaReply("error", why);
  Serial.printf("[OTA] aborted: %s — running firmware unchanged\n", why);
}

static void sendFwInfo() {
  const esp_partition_t* run  = esp_ota_get_running_partition();
  const esp_partition_t* next = esp_ota_get_next_update_partition(nullptr);
  BT.printf("{\"type\":\"fw\",\"version\":\"" FW_VERSION "\",\"ota\":%s,"
            "\"rollback\":%s,\"slot\":\"%s\"}\n",
            next ? "true" : "false", OTA_ROLLBACK ? "true" : "false",
            run ? run->label : "?");
}

static const char* resetReasonName(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON:   return "power-on";
    case ESP_RST_BROWNOUT:  return "brownout";       // supply dipped (e.g. at key-on)
    case ESP_RST_PANIC:     return "crash";
    case ESP_RST_INT_WDT:   return "watchdog-int";
    case ESP_RST_TASK_WDT:  return "watchdog-task";
    case ESP_RST_WDT:       return "watchdog";
    case ESP_RST_SW:        return "software";       // e.g. after a firmware update
    case ESP_RST_DEEPSLEEP: return "deep-sleep";
    case ESP_RST_EXT:       return "reset-pin";
    default:                return "unknown";
  }
}

// Read-only health report for the app (Settings → Car interface diagnostics),
// because the unit is sealed in and the USB console is out of reach.
static void sendDiag() {
  twai_status_info_t s = {};
  bool st = g_canOk && twai_get_status_info(&s) == ESP_OK;
  const char* bus = !g_canOk ? "not-started"
                  : !st ? "unknown"
                  : s.state == TWAI_STATE_RUNNING    ? "running"
                  : s.state == TWAI_STATE_BUS_OFF    ? "bus-off"
                  : s.state == TWAI_STATE_RECOVERING ? "recovering" : "stopped";
  BT.printf("{\"type\":\"diag\",\"fw\":\"" FW_VERSION "\",\"up\":%lu,\"reset\":\"%s\","
            "\"boots\":%lu,\"firsttx\":%lu,\"canup\":%lu,\"bus\":\"%s\","
            "\"tec\":%lu,\"rec\":%lu,\"busoff\":%lu,\"txfail\":%lu,\"rxmiss\":%lu,"
            "\"buserr\":%lu,\"dup316\":%lu,\"dup329\":%lu,\"dup43f\":%lu,"
            "\"fuel\":\"%s\",\"fueldrv\":%d,\"sd\":%s,\"heap\":%lu}\n",
    (unsigned long)(millis() / 1000), resetReasonName(esp_reset_reason()),
    (unsigned long)g_bootCount, (unsigned long)g_firstTxMs, (unsigned long)g_canUpMs, bus,
    (unsigned long)(st ? s.tx_error_counter : 0), (unsigned long)(st ? s.rx_error_counter : 0),
    (unsigned long)g_busOffCount,
    (unsigned long)(st ? s.tx_failed_count : 0), (unsigned long)(st ? s.rx_missed_count : 0),
    (unsigned long)(st ? s.bus_error_count : 0),
    (unsigned long)g_dup316, (unsigned long)g_dup329, (unsigned long)g_dup43F,
    g_fuelSrc, (int)fuelDriveApplied, sdOk ? "true" : "false",
    (unsigned long)(ESP.getFreeHeap() / 1024));
}

// Validates and starts an update. Returns false (reply already sent) if refused.
static bool otaBegin(uint32_t size, const char* md5) {
  const esp_partition_t* slot = esp_ota_get_next_update_partition(nullptr);
  const char* why = nullptr;
  if (getCurrentSpeed() > MODE_CHANGE_MAX_KMH)        why = "vehicle moving — stop first";
  else if (paramFresh(params[IDX_OPMODE])
        && (int)roundf(params[IDX_OPMODE].value) == OPMODE_RUN)
                                                      why = "inverter running — switch off first";
  else if (flushing || sdFlushing)                    why = "history sync in progress — try again shortly";
  else if (pendingMode >= 0)                          why = "drive-mode change in progress";
  else if (!slot)                                     why = "no OTA partition — reflash once over USB with an OTA partition scheme";
  else if (size == 0 || size > slot->size)            why = "image does not fit the OTA slot";
  else if (strlen(md5) != 32)                         why = "bad checksum";
  if (why) { otaReply("error", why); Serial.printf("[OTA] refused: %s\n", why); return false; }

  if (!Update.begin(size, U_FLASH)) { otaReply("error", Update.errorString()); return false; }
  if (!Update.setMD5(md5))          { Update.abort(); otaReply("error", "bad checksum"); return false; }

  otaActive = true;
  otaSize   = size;
  otaRecv   = 0;
  otaFill   = 0;
  otaLastRx = millis();
  otaIdReset();
  Serial.printf("[OTA] receiving %lu bytes into '%s'\n", (unsigned long)size, slot->label);
  BT.printf("{\"type\":\"ota\",\"state\":\"ready\",\"chunk\":%d,\"fw\":\"" FW_VERSION "\"}\n",
            OTA_CHUNK);
  return true;
}

// Runs instead of the normal loop while an update is in progress. Every byte
// on the link is firmware now, so nothing else may read from BT. The car side
// is unaffected: vehicleTask and the gauge timer keep running throughout.
static void serviceOta() {
  if (!BT.hasClient()) { otaAbort("link dropped"); return; }
  while (otaActive && BT.available()) {
    uint8_t b = (uint8_t)BT.read();
    otaBuf[otaFill++] = b;
    otaIdFeed(b);
    otaLastRx = millis();
    uint32_t left     = otaSize - otaRecv;
    uint32_t chunkLen = left < OTA_CHUNK ? left : OTA_CHUNK;
    if (otaFill < chunkLen) continue;

    if (Update.write(otaBuf, otaFill) != otaFill) { otaAbort(Update.errorString()); return; }
    otaRecv += otaFill;
    otaFill  = 0;
    BT.printf("{\"type\":\"ota\",\"state\":\"ack\",\"off\":%lu}\n", (unsigned long)otaRecv);

    if (otaRecv == otaSize) {
      if (!otaIdOk) {
        otaAbort("not Bluetooth-updatable car firmware (needs v7.1 or later) — refused "
                 "so future updates keep working");
        return;
      }
      if (!Update.end()) { otaAbort(Update.errorString()); return; }   // MD5 checked here
      otaActive = false;
      otaReply("done");
      Serial.println("[OTA] image verified — rebooting into new firmware");
      delay(1000);            // let the reply leave before the radio goes down
      ESP.restart();
    }
  }
  if (otaActive && millis() - otaLastRx > OTA_IDLE_MS) otaAbort("transfer stalled");
}

// ── Post-update self-check (rollback) ────────────────────────────────────────
// A freshly flashed image boots PENDING_VERIFY. It stays unconfirmed until the
// app reconnects over Bluetooth — proof this firmware can still be updated,
// which is all that matters for a unit you can't physically reach. If it
// crashes, reboots or loses power before then, the bootloader boots the
// previous firmware again.
//
// Tells the Arduino core not to confirm a new image the instant it boots.
extern "C" bool verifyRollbackLater() { return true; }

static void serviceOtaValidation() {
#if OTA_ROLLBACK
  static int8_t pending = -1;          // checked once; reading otadata is flash IO
  if (pending == 0) return;
  if (pending < 0) {
    esp_ota_img_states_t st;
    pending = (esp_ota_get_state_partition(esp_ota_get_running_partition(), &st) == ESP_OK
               && st == ESP_OTA_IMG_PENDING_VERIFY) ? 1 : 0;
    if (!pending) return;
    Serial.println("[OTA] new firmware on trial — waiting for the app to reconnect");
  }
  if (!btLinkUp) {
    Serial.println("[OTA] Bluetooth failed on new firmware — rolling back");
    delay(200);
    esp_ota_mark_app_invalid_rollback_and_reboot();
  }
  if (BT.hasClient()) {
    esp_ota_mark_app_valid_cancel_rollback();
    pending = 0;
    Serial.println("[OTA] app reconnected — new firmware confirmed");
  }
#endif
}

// ── Bluetooth command handling ────────────────────────────────────────────────
// SECURITY: Bluetooth gets an ALLOWLIST, not the full serial command set.
//
// This previously fell through to handleCommand(), which meant anything paired
// with the car could issue every diagnostic command — including "FUEL 100" to
// pin the fuel gauge to empty while the pack was full, "SDREWIND" to force an
// endless history replay, "STOP329" to kill the temperature gauge, and "SCAN"
// to sweep the inverter over CAN. Legacy PIN pairing is the only thing standing
// in front of that, so the surface needs to be as small as the app requires.
//
// The app needs: request a drive mode, ask for history, read the firmware
// version and diagnostics (read-only), and push a firmware update (gated — see
// the OTA section). Everything else stays on the USB serial port, which
// requires physical access.
void pollBTCommands() {
  static char buf[64];               // "OTA <size> <32-hex md5>" needs ~50
  static uint8_t len = 0;
  static bool overflow = false;      // discard an over-long line, don't split it

  while (BT.available()) {
    char c = (char)BT.read();
    if (c == '\n' || c == '\r') {
      if (len && !overflow) {
        buf[len] = '\0';
        // Mode requests get an explicit ack/refusal the app can act on.
        if (strcasecmp(buf, "ECO") == 0 || strcasecmp(buf, "NORMAL") == 0
         || strcasecmp(buf, "SPORT") == 0) {
          int m = (strcasecmp(buf, "ECO") == 0) ? MODE_ECO
                : (strcasecmp(buf, "SPORT") == 0) ? MODE_SPORT : MODE_NORMAL;
          const char* why = "";
          if (requestDriveMode(m, &why))
            BT.printf("{\"type\":\"mode\",\"ok\":true,\"mode\":\"%s\"}\n", MODE_NAMES[m]);
          else
            BT.printf("{\"type\":\"mode\",\"ok\":false,\"reason\":\"%s\"}\n", why);
        } else if (strcasecmp(buf, "SYNC") == 0) {
          beginFlush();
        } else if (strcasecmp(buf, "VERSION") == 0) {
          sendFwInfo();
        } else if (strcasecmp(buf, "DIAG") == 0) {
          sendDiag();                // read-only health report
        } else if (strncasecmp(buf, "OTA ", 4) == 0) {
          unsigned long sz = 0;
          char md5[40] = {0};
          if (sscanf(buf + 4, "%lu %39s", &sz, md5) != 2) {
            otaReply("error", "bad OTA command");
          } else if (otaBegin((uint32_t)sz, md5)) {
            len = 0; overflow = false;
            return;                  // everything after this is raw firmware bytes
          }
        } else {
          BT.printf("{\"type\":\"error\",\"reason\":\"command not permitted over BT\"}\n");
          Serial.printf("[BT] rejected command '%s'\n", buf);
        }
      }
      len = 0; overflow = false;
    } else if (len < sizeof(buf) - 1) {
      buf[len++] = c;
    } else {
      overflow = true;   // hold until the newline; never parse a fragment
    }
  }
}

// ── Serial debug summary — everything received and sent, grouped ─────────────
void printDecoded() {
  uint32_t up = millis() / 1000;
  float gaugeC = 0.75f * tx329.data[1] - 48.373f;   // what the cluster shows

  Serial.println();
  Serial.printf("=== FREELANDER EV " FW_VERSION " ================ up %02lu:%02lu:%02lu | heap %uk ===\n",
    (unsigned long)(up / 3600), (unsigned long)(up / 60 % 60), (unsigned long)(up % 60),
    ESP.getFreeHeap() / 1024);

  // ── Receiving ──
  Serial.printf(" RX< LandRover : spd %.1f km/h   odo %lu km\n",
    getCurrentSpeed(), (unsigned long)getOdometerKm());
  Serial.printf(" RX< Inverter  : udc %s%.1f V   idc %s%.1f A   tmphs %s%.1f C   tmpm %s%.1f C\n",
    params[IDX_UDC].ok   ? "" : "?", params[IDX_UDC].value,
    params[IDX_CURR].ok  ? "" : "?", params[IDX_CURR].value,
    params[IDX_TMPHS].ok ? "" : "?", params[IDX_TMPHS].value,
    params[IDX_TMPM].ok  ? "" : "?", params[IDX_TMPM].value);
  {
    char st[96]; statusText(st, sizeof(st));
    int c = getLastErrCode();
    Serial.printf(" RX< Faults    : lasterr %s (%d)%s   status %s\n",
      lastErrName(), c, (c > 0) ? "  ** FAULT **" : "", st);
  }
  Serial.printf(" RX< 12V rail  : uaux %s%.1f V%s\n",
    params[IDX_AUX].ok ? "" : "?", getAuxVolt(),
    (paramFresh(params[IDX_AUX]) && getAuxVolt() < AUX_WARN_VOLTS)
      ? "   ** LOW — DC-DC not charging **" : "");
  Serial.printf(" RX< Inverter  : opmode %s%.0f (%s)   seldir %s%.0f -> gear %c\n",
    params[IDX_OPMODE].ok ? "" : "?", params[IDX_OPMODE].value,
    (paramFresh(params[IDX_OPMODE]) &&
     (int)roundf(params[IDX_OPMODE].value) == OPMODE_RUN) ? "RUN" : "not run",
    params[IDX_SELDIR].ok ? "" : "?", params[IDX_SELDIR].value, getGear());
  Serial.printf(" MODE          : %s   (fweak %s%.0f, last written %s)%s\n",
    reportedModeName(),
    params[IDX_FWEAK].ok ? "" : "?", params[IDX_FWEAK].value,
    MODE_NAMES[activeMode],
    pendingMode >= 0 ? "  (applying...)" : "");

  // ── Sending ──
  const char* txState = !timerRunning ? "TIMER NOT RUNNING"
                      : tx329UserEnabled ? "10 ms" : "STOPPED (STOP329)";
  Serial.printf(" TX> 0x329 temp: %s   gauge %.1f C (byte 0x%02X)   src %s\n",
    txState, gaugeC, tx329.data[1],
    paramFresh(params[IDX_TMPM]) ? "live tmpm" : "fallback 60C");
  Serial.printf(" TX> 0x316 rev : %s   %.0f rpm (%.1f A x %.0f rpm/A)\n",
    txState,
    ((tx316.data[3] << 8) | tx316.data[2]) / 6.4f, getBattCurr(), RPM_PER_AMP);
  {
    float soc = (fuelVolt > 0) ? socFromOcv(fuelVolt) : -1.0f;
    // Report what is ACTUALLY on the pin, plus the source — so a stuck needle
    // can be told apart from a firmware that isn't driving anything.
    const char* src = (fuelOverride >= 0) ? "FORCED" : g_fuelSrc;   // saved | live | default
    int duty = (fuelDriveApplied >= 0)
             ? (int)roundf(fuelDriveApplied * FUEL_PWM_MAX / 100.0f) : -1;
    Serial.printf(" TX> fuel gauge: %s  drive %d %% (pwm %d/%d on GPIO%d)   SOC %.0f %%\n",
      src, fuelDriveApplied, duty, (int)FUEL_PWM_MAX, (int)FUEL_PWM_PIN, soc);
    // SOC internals: the sag-corrected voltage the curve is actually fed, and
    // the terminal voltage it came from. The gap between them is i * PACK_R.
    Serial.printf(" SOC calc      : ocv %.1f V (terminal %.1f V, R %.2f ohm)  window %.0f-%.0f V  dc link %s\n",
      (float)fuelVolt, getBattVolt(), PACK_R, PACK_V_EMPTY, PACK_V_FULL,
      inverterDcLinkUp() ? "UP" : "not up (holding saved level)");
  }
  if (SELDIR_PARAM_ID != 0)
    Serial.printf(" TX> 0x43F gear: %c   every %d ms (timer)\n", getGear(), TX43F_MS);
  else
    Serial.println(" TX> 0x43F gear: OFF (SELDIR_PARAM_ID not set — Arduino owns it)");
  Serial.printf(" CAN health    : bus-offs %lu   foreign 0x316/0x329/0x43F frames %lu/%lu/%lu   first TX %lu ms\n",
    (unsigned long)g_busOffCount, (unsigned long)g_dup316, (unsigned long)g_dup329,
    (unsigned long)g_dup43F, (unsigned long)g_firstTxMs);
  Serial.printf(" TX> 0x201 stat: %lu replies to 0x200 requests\n",
    (unsigned long)statusRepliesSent);

  // ── App link ──
  if (sdOk) {
    uint32_t back = sdBacklogCached();
    Serial.printf(" SD  storage   : %s   %lu rows this session   unsent ~%lu rows\n",
      sdPath, (unsigned long)sdRows, (unsigned long)(back / 48));
  } else {
    Serial.println(" SD  storage   : NO CARD — 10 min RAM ring buffer only");
  }
  Serial.printf(" BT > app      : %s   buffer %u/%u   synced %s\n",
    BT.hasClient() ? "connected (live 500 ms)" : "waiting",
    bufCount, BUF_SIZE, synced ? "yes" : "no");
  Serial.println("==============================================================");
}

// ── CAN health: alerts + periodic state check (bus-off / stopped recovery) ────
// Runs in vehicleTask. The gauge timer is left running throughout: while the
// controller is bus-off or recovering, twai_transmit() fails fast and frames
// resume the instant it is back. (Before v7.3 the timer was stopped here and
// restarted on recovery — one more thing that had to go right at power-up.)
void serviceCanAlerts() {
  if (!g_canOk) return;
  uint32_t alerts;
  if (twai_read_alerts(&alerts, 0) == ESP_OK) {
    if (alerts & TWAI_ALERT_BUS_OFF) {
      g_busOffCount++;
      Serial.printf("[CAN] BUS-OFF #%lu — recovering\n", (unsigned long)g_busOffCount);
      twai_initiate_recovery();
    }
    if (alerts & TWAI_ALERT_BUS_RECOVERED) {
      Serial.println("[CAN] Bus recovered");
      twai_start();
    }
    if (alerts & TWAI_ALERT_RX_QUEUE_FULL) Serial.println("[CAN] RX overflow");
  }
  // Belt-and-braces: if the driver ended up STOPPED (e.g. after recovery
  // without the alert being caught), restart it.
  static uint32_t lastHealth = 0;
  if (millis() - lastHealth >= 1000) {
    lastHealth = millis();
    twai_status_info_t s;
    if (twai_get_status_info(&s) == ESP_OK) {
      if (s.state == TWAI_STATE_BUS_OFF)      twai_initiate_recovery();
      else if (s.state == TWAI_STATE_STOPPED) twai_start();
    }
  }
}

// ── One received frame (vehicleTask) ─────────────────────────────────────────
static void handleRxFrame(const twai_message_t& msg) {
  if (msg.rtr || msg.extd) return;            // this bus is 11-bit data frames
  uint16_t id = msg.identifier & 0x7FF;

  if (id == SDO_RESP)                    handleSdoReply(msg);
  else if (id == REQ_ID)                 sendStatusFrame();   // instant reply from cache
  else if (id == 0x153 || id == 0x613)   decodeLandRover(msg, id);
  else if (id == 0x316 || id == 0x329 || id == 0x43F) {
    // We never receive our own frames, so this is ANOTHER sender on one of
    // our ids. Two nodes on one id corrupt each other's frames — exactly the
    // kind of thing that confuses the cluster and drives the bus off.
    volatile uint32_t* c = (id == 0x316) ? &g_dup316 : (id == 0x329) ? &g_dup329 : &g_dup43F;
    if ((*c)++ == 0)
      Serial.printf("[CAN] ** another node is ALSO sending 0x%03X — check for a "
                    "second sender (old gear Arduino, inverter CAN map) **\n", id);
  }

  // Everything also feeds the tracking list (serial summary)
  int ti = trackGet(id);
  if (ti < 0) return;                         // list full — ignore this id
  IdEntry& e = track[ti];
  bool changed = !e.seen
              || e.dlc != msg.data_length_code
              || memcmp(e.data, msg.data, msg.data_length_code) != 0;
  e.seen  = true;
  e.dlc   = msg.data_length_code;
  e.count++;
  memcpy(e.data, msg.data, msg.data_length_code);
  if (changed) e.changes++;
}

// ── Vehicle task (v7.3) ──────────────────────────────────────────────────────
// Everything that talks to the car, in one place, above loop() and immune to
// it. A congested Bluetooth link blocks BT.print() for as long as it likes, the
// SD card stalls on every append, the USB console blocks when its buffer
// fills — none of which may freeze the gear display or let the inverter
// readings go stale (stale seldir/opmode shows P). The 0x329/0x316/0x43F
// frames themselves come from the 10 ms timer, which is separate again.
#define VEHICLE_TASK_PRIO   3        // loop() runs at 1, on the same core
#define VEHICLE_TASK_STACK  6144     // printf + NVS writes need the headroom

static void vehicleTask(void*) {
  for (;;) {
    if (g_canOk) {
      twai_message_t msg;
      for (int n = 0; n < 64 && twai_receive(&msg, 0) == ESP_OK; n++) handleRxFrame(msg);
    }
    serviceCanAlerts();
    serviceDriveMode();     // apply a pending drive-mode change (SDO writes)
    serviceSdoPolling();    // round-robin inverter reads, one in flight
    updateGearFrame();      // which 0x43F frame the timer sends
    serviceGaugeTemp();     // motor temp onto the cluster gauge (0x329 byte 1)
    serviceGaugeRpm();      // |idc| onto the rev counter (0x316 bytes 2/3)
    serviceFuelGauge();     // SOC onto the fuel needle
    // ~1 kHz. Always yields, even under a flood of frames (the 64-deep RX
    // queue absorbs a millisecond easily), so loop() is never starved either.
    vTaskDelay(1);
  }
}

// Cluster-facing CAN, first thing at power-on (v7.3): frames, the driver, the
// 10 ms timer and vehicleTask. Nothing slow in here, and nothing slow before it.
static void canBegin() {
  uint8_t coolantByte = (uint8_t)roundf((TARGET_COOLANT_C + 48.373f) / 0.75f);
  memset(&tx329, 0, sizeof(tx329));
  tx329.identifier       = 0x329;
  tx329.data_length_code = 8;
  tx329.data[0] = 0xD9;
  tx329.data[1] = coolantByte;

  // 0x316 rev counter — COMPLETE 8-byte frame, all statics set to the
  // "engine running / ON" values from the bus sniff table so the ABS and
  // instrument cluster see a fully valid Motor Electronics message:
  //   B0 0x05 = ON            B1 0x1B = tickover (running)
  //   B2/B3  = RPM LSB/MSB (live from |idc|)
  //   B4 0x0C = tickover, follows B1
  //   B5 0x01 = engine running
  //   B6 0x00 = ON            B7 0x00 = unused
  memset(&tx316, 0, sizeof(tx316));
  tx316.identifier       = 0x316;
  tx316.data_length_code = 8;
  tx316.data[0] = 0x05;
  tx316.data[1] = 0x1B;
  tx316.data[2] = 0x00;   // RPM LSB — live
  tx316.data[3] = 0x00;   // RPM MSB — live
  tx316.data[4] = 0x0C;
  tx316.data[5] = 0x01;
  tx316.data[6] = 0x00;
  tx316.data[7] = 0x00;

  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(CAN_TX_PIN, CAN_RX_PIN, TWAI_MODE_NORMAL);
  g.rx_queue_len   = 64;    // headroom: SDO replies + LR traffic + bursts
  g.tx_queue_len   = 16;    // 0x329/0x316 @10 ms + 0x43F + SDO requests + status replies
  g.alerts_enabled = TWAI_ALERT_BUS_OFF | TWAI_ALERT_BUS_RECOVERED | TWAI_ALERT_RX_QUEUE_FULL;
  twai_timing_config_t  t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t  f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  // No ESP_ERROR_CHECK: aborting here would reboot-loop the unit and take
  // Bluetooth — and so firmware updates — down with it. Carry on without CAN;
  // the boot banner and DIAG both report it.
  g_canOk   = twai_driver_install(&g, &t, &f) == ESP_OK && twai_start() == ESP_OK;
  g_canUpMs = (uint32_t)(esp_timer_get_time() / 1000);

  esp_timer_create_args_t timerArgs = {
    .callback        = onGaugeTimer,
    .arg             = nullptr,
    .dispatch_method = ESP_TIMER_TASK,
    .name            = "gauges"
  };
  if (esp_timer_create(&timerArgs, &txTimer) == ESP_OK) startGaugeTimer();

  xTaskCreatePinnedToCore(vehicleTask, "vehicle", VEHICLE_TASK_STACK, nullptr,
                          VEHICLE_TASK_PRIO, nullptr, 1);
}

// ─────────────────────────────────────────────────────────────────────────────
void setup() {
  // ── 1. What the cluster reads at power-on — before anything slow ──────────
  // (v7.3) This used to come LAST, after a 300 ms delay, the SD mount and the
  // Bluetooth stack: a second or more of silent bus and an open-circuit fuel
  // sender while the cluster woke up.
  Serial.setTxBufferSize(4096);   // the ~1.5 KB summary must never block loop()
  Serial.begin(115200);           // non-blocking; no settle delay needed
  fuelBegin();                    // needle at the last known level, never dark
  canBegin();                     // CAN + gauge/gear timer + vehicleTask

  // ── 2. Everything else ────────────────────────────────────────────────────
  Serial.println();
  // ⚠ This literal is the firmware's identity for OTA (see otaIdFeed): keep
  //   "Freelander EV Vehicle Interface " FW_VERSION exactly as it is.
  Serial.println("Freelander EV Vehicle Interface " FW_VERSION);
  {
    Preferences d;
    d.begin("diag", false);
    g_bootCount = d.getULong("boots", 0) + 1;
    d.putULong("boots", g_bootCount);
    d.end();
  }
  Serial.printf("Boot #%lu   last reset: %s   CAN %s %lu ms after start   fuel needle from %s\n",
    (unsigned long)g_bootCount, resetReasonName(esp_reset_reason()),
    g_canOk ? "running" : "FAILED TO START", (unsigned long)g_canUpMs, g_fuelSrc);
  Serial.printf("0x329: byte 0x%02X = %.1f C\n", tx329.data[1], 0.75f * tx329.data[1] - 48.373f);

  sdInit();             // card becomes the primary store if one is fitted

  // Only Bluetooth CLASSIC (SPP) is used — releasing the BLE half of the
  // controller returns ~30 KB of heap.
  esp_bt_controller_mem_release(ESP_BT_MODE_BLE);

#if ESP_ARDUINO_VERSION_MAJOR >= 3
  BT.setPin(BT_PIN, strlen(BT_PIN));
#else
  BT.setPin(BT_PIN);
#endif
  btLinkUp = BT.begin("Freelander EV");
  Serial.println(btLinkUp
    ? "BT started as 'Freelander EV'  (drive mode, SYNC, VERSION, DIAG, OTA; diagnostics USB-only)"
    : "BT FAILED TO START");
  {
    const esp_partition_t* run = esp_ota_get_running_partition();
    Serial.printf("Firmware " FW_VERSION " in '%s' | OTA over BT: %s | rollback: %s\n",
      run ? run->label : "?",
      esp_ota_get_next_update_partition(nullptr) ? "available"
                                                 : "NO OTA PARTITION (USB only)",
      OTA_ROLLBACK ? "on" : "OFF");
  }
  Serial.println("Vehicle task running: 0x329/0x316 every 10 ms, 0x43F every 50 ms, SDO polling node 1.");
}

// ─────────────────────────────────────────────────────────────────────────────
// loop() is now only the app side: Bluetooth, the SD card and the USB console.
// The car side lives in vehicleTask and the gauge timer, and keeps running
// whatever happens here — including a firmware update.
void loop() {
  if (otaActive) {          // the link carries raw image bytes until it's done
    serviceOta();
    return;
  }
  serviceOtaValidation();   // confirms a freshly updated image once the app reconnects
  serviceErrorLog();        // append to /ERRORS.CSV when lasterr changes

  pollSerialCommands();
  pollBTCommands();         // drive mode, SYNC, VERSION, DIAG, OTA

  static bool wasClient = false;
  bool hasClient = BT.hasClient();
  if (hasClient && !wasClient) {
    liveSession = true;     // from here, logged rows also go out live
    beginFlush();           // replay only what was logged while the app was away
  } else if (!hasClient && wasClient) {
    liveSession = false;
  }
  wasClient = hasClient;

  serviceFlush();

  static uint32_t lastBT = 0;
  if (millis() - lastBT >= BT_MS) {
    if (hasClient) sendBTJson();
    // The card gets every sample regardless of the phone, so the archive has
    // no gaps. The RAM ring buffer is only needed when there is no card.
    if (sdOk)            sdLog();
    else if (!hasClient) bufferPush();
    lastBT = millis();
  }
  sdService();              // batched append — see SD_FLUSH_MS

  static uint32_t lastSummary = 0;
  if (millis() - lastSummary >= SUMMARY_MS) {
    printDecoded();
    lastSummary = millis();
  }
}

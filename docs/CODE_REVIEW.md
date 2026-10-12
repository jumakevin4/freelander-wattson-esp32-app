# Wattson (LandroverEV) — Code Review

A detailed review of the Android app that streams live data from an ESP32 (BT SPP)
and computes range, efficiency and trip history for a Tesla-powered Land Rover EV.

The architecture is sound for the size of the app: clean Room layer, a ViewModel
holding state in `StateFlow`s, a small custom view for the SOC gauge, and a sensible
sync protocol. The issues below are ranked by impact. The first three are correctness
bugs that I'd fix before trusting any number the app shows.

---

## Critical — correctness bugs that corrupt the data

### 1. Energy integration assumes 1 Hz, but the ESP32 sends at 2 Hz
`EnergyManager.onSecondSample()` computes `wh = V * I / 3600` for every packet and
accumulates it, i.e. it treats **each packet as exactly one second of energy**. But
the firmware sends real-time JSON every 500 ms (`#define BT_MS 500` in `can_sniffer.ino`),
so there are ~2 packets per second. Every energy figure in the app is therefore roughly
**2× too high**: `sessionWh`, consumed kWh, efficiency (Wh/km), the energy bar chart,
projected range, and every persisted `EnergyTenMin`/`EnergyHourly`/`TripSession` row.

Fix: integrate against the *actual* elapsed time between samples rather than a fixed
divisor.

```kotlin
fun onSample(voltageV: Float, currentA: Float, speedKmh: Float,
             distDeltaKm: Float, dtMs: Long) {
    val hours = dtMs / 3_600_000f
    val wh = voltageV * currentA * hours   // W × h
    ...
}
```

Pass `now - lastUpdateMs` from the ViewModel (it already computes `now`). This also
makes the math correct if the firmware cadence ever changes, and it removes the
implicit "1 sample = 1 second" coupling that the method name still advertises.

### 2. Buffered sync records ignore their timestamp
The protocol sends buffered records as `{"type":"data","ts":ms,...}` (ESP32 uptime),
but `parseCarData()` never reads `ts`. During sync these records arrive back-to-back,
so in the ViewModel `now - lastUpdateMs ≈ 0` for each one and:

- `distDelta` collapses to ~0 → buffered distance is essentially lost,
- the rolling 30-min window and 5-min buckets get a burst of zero-dt samples,
- trip start/duration timing is wrong for any recovered drive.

So the "sync buffered data" feature currently contributes almost nothing useful and
can skew the live window right after connect. Either carry `ts` through `CarData` and
drive all time deltas from it (use it for both live and buffered packets), or at
minimum reconstruct `dtMs` from consecutive `ts` values during sync.

### 3. `runBlocking` on the main thread (ANR risk)
`recoverBackupIfExists()` runs inside `init { }` and calls
`runBlocking(Dispatchers.IO) { dao.insert(trip) }`. `init` runs on the main thread when
the ViewModel is created, so the first DB insert **blocks the UI thread** while the
database opens and writes — a classic cold-start ANR. `onCleared()` does the same.

Fix: make recovery a suspend operation launched in `viewModelScope`, or move it behind
`Dispatchers.IO` without blocking:

```kotlin
init {
    viewModelScope.launch(Dispatchers.IO) { recoverBackupIfExists() }
    ...
}
```

For `onCleared()` (where you can't suspend), persist to the backup prefs instead and let
the next launch recover — don't block teardown on a DB write.

---

## High — robustness and performance

### 4. Synchronous `SharedPreferences.commit()` twice per second
`backupTripState()` calls `prefs.edit()....commit()` (annotated `@Suppress("ApplySharedPref")`)
on **every BT packet** once a trip passes 0.5 km. `commit()` is a synchronous disk write
on the calling thread (the collector runs on the main dispatcher), so you're doing blocking
disk I/O ~2×/second for the entire drive — jank, flash wear, and battery drain.

Fix: throttle the backup (e.g. every 10–15 s, or piggyback on the existing 30 s timer),
and use `apply()` unless you specifically need durability at that instant.

### 5. Phantom distance after a connection gap
`distDelta = speedKmh * (now - lastUpdateMs) / 3_600_000`. `lastUpdateMs` persists across
a BT drop/reconnect. If the link drops for several minutes and the first packet back has
`speed > 0`, that single packet adds `speed × (whole gap)` km to the trip. Clamp the delta:

```kotlin
val dtMs = (now - lastUpdateMs).coerceAtMost(MAX_SAMPLE_GAP_MS) // e.g. 5_000
```

Apply the same clamp to the energy integration in #1.

### 6. Reconnect loop can stack
In `DashboardActivity.observeViewModel()`, `DISCONNECTED`/`ERROR` triggers `delay(5s)`
then `viewModel.connect(...)`. `BluetoothService.connect()`'s `finally { disconnect() }`
emits `DISCONNECTED`, which re-enters this branch — and every failed attempt schedules
another. Under a flapping link you can end up with overlapping `connect` coroutines and
sockets. Track connection state in the service and ignore reconnect requests while
`CONNECTING`/`CONNECTED`, or cancel the previous attempt before starting a new one.

### 7. Possible NPE on `adapter`
`getSystemService(BluetoothManager::class.java).adapter` and `btManager.adapter` can be
`null` (no BT hardware, or BT fully off on some OEMs). `MainActivity` and the
reconnect path dereference it directly. Guard for null and show the existing
"Bluetooth must be enabled" path instead of crashing.

### 8. UI keeps collecting while stopped
The 10 `lifecycleScope.launch { flow.collect { } }` blocks in `DashboardActivity` (and
the others) run from `onCreate` and never pause — they keep updating views while the
activity is in the background. Wrap them in `repeatOnLifecycle(Lifecycle.State.STARTED)`
so collection stops in `onStop` and resumes on return. (Less critical here because the
screen is kept on, but it's the correct pattern and saves work during reconnect storms.)

---

## Medium — design, testability, maintainability

### 9. No tests for the risky logic
The only tests are the generated `2 + 2` templates. The energy integration, range
projection, trip-detection timeout, and SOC-drop math are exactly the parts most likely
to be wrong (see #1, #2). Pull the pure math out of the Android-bound classes into small
testable functions (e.g. an `EnergyAccumulator` with no `Context`) and unit-test them.
This single change would have caught bug #1.

### 10. Business logic is welded into the ViewModel's collector
`DashboardViewModel.init` is one ~65-line lambda doing distance, speed averaging, energy
deltas, bucketing, the rolling window, range projection and backup. It can't be tested
without a live `BluetoothService`. Consider extracting a `TripTracker`/`RangeEstimator`
that takes `(CarData, dtMs)` and returns updated state — then the ViewModel just wires
flows to it.

### 11. `BluetoothService` is hard-instantiated
`val btService = BluetoothService()` inside the ViewModel prevents substituting a fake
for tests or previews. Inject it (constructor param + a simple `ViewModelProvider.Factory`,
or Hilt if you plan to grow).

### 12. `TripAdapter` uses `notifyDataSetChanged()`
Switch to `ListAdapter` + `DiffUtil` so history updates animate and don't rebind every
row. Low effort, and trip lists only grow.

### 13. Per-packet allocation churn in `updateRangePanel`
It builds several `listOf(...).random()` lists (and formats strings) on every range
emission — i.e. up to ~2×/second. Hoist the tip lists to `val`/companion constants so you
only index into them. Minor GC relief, and it makes the copy easier to edit in one place.

---

## Low — polish and config

- **Release build is unshrunk and unsigned.** `isMinifyEnabled = false`, no
  `proguard-rules.pro`, no `signingConfig`, `versionCode` stuck at 1. Enable R8 +
  resource shrinking and add a signing config before distributing.
- **Theme colours are copy-pasted as `Color.parseColor("#…")` across five activities.**
  The same ~7 hexes are redefined in `DashboardActivity`, `SettingsActivity`,
  `ChartActivity`, `DetailsActivity`, `TripHistoryActivity` and `TripAdapter`. Move them
  to `colors.xml` (you already have `values-night/`) and use theme attributes so dark mode
  is automatic and you stop hand-toggling colours in every `applyTheme()`.
- **`BLUETOOTH_SCAN`** is declared without `android:usesPermissionFlags="neverForLocation"`.
  If you never derive location from scans, add it to avoid pulling in the location
  permission expectation.
- **Dead code:** `EnergySecond` data class (unused), `DetailsActivity.RESULT_RESET_TRIP`
  (unused), and a few unused `lifecycleScope` imports.
- **Stale-ish deps:** `compileSdk`/`targetSdk` 34 (35 is current), lifecycle 2.7.0,
  coroutines 1.7.3. `MPAndroidChart v3.1.0` is effectively unmaintained — fine to keep,
  just be aware.
- **Hardcoded UI strings** (only `app_name` is in `strings.xml`). Not urgent for a
  single-language personal dashboard, but worth knowing if localization ever matters.
- **No onboarding for battery capacity.** `designCapacity` defaults to `0`, so projected
  range silently stays `--` until the user visits Settings. A first-run hint ("set your
  battery capacity to enable range") would close the gap.

---

## Quick wins (highest value for least effort)
1. Multiply energy by real elapsed time (#1) — fixes every energy/efficiency/range number.
2. Move backup recovery off the main thread (#3) and throttle the backup writes (#4).
3. Clamp the sample gap (#5) to kill phantom distance.
4. Extract the energy/range math into pure functions and add unit tests (#9).

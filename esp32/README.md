# Freelander EV — freelander-esp32 + LandroverEV Dashboard

ESP32 firmware that decodes the Land Rover Freelander CAN bus, keeps the
coolant gauge alive, and streams telemetry over Bluetooth Classic (SPP) to
the **LandroverEV** Android app.

| Component | Location |
|-----------|----------|
| ESP32 firmware | `freelander-esp32/freelander-esp32.ino` (this folder) |
| Android app | `~/AndroidStudioProjects/LandroverEV` (separate Android Studio project, v4.0) |
| UI mockup | `dashboard_mockup.html` |

> The earlier BLE variant (ev_telemetry_ble + `android_app/`) was removed on
> 2026-07-03 — this SPP pair is the project.

---

## Hardware

| Component | Notes |
|-----------|-------|
| ESP32 38-pin dev board | Built-in TWAI (CAN) peripheral |
| SN65HVD230 | 3.3 V CAN transceiver |

```
SN65HVD230 R  →  GPIO 16
SN65HVD230 D  →  GPIO 17
VCC → 3.3 V, GND → GND
CANH / CANL   →  Land Rover CAN bus (500 kbps)
```

---

## Protocol (must match LandroverEV `BluetoothService.kt`)

Device name **"Freelander EV"**, newline-delimited JSON over SPP.

**Live packet** — every 500 ms, **no `type` field** (the app routes typeless
lines to its real-time path):

```json
{"spd":42.5,"soc":76,"batt":0.0,"curr":0.0,"btemp":0.0,"mtemp":0.0,"brake":false,"ac":false}
```

**History sync** — sent automatically when the app connects (the app never
sends commands). While disconnected the firmware buffers one record per
500 ms (15 min ring buffer):

```json
{"type":"sync_start","count":1800}
{"type":"data","ts":123456,"spd":42.5,"soc":76,"batt":0.0,"curr":0.0,"btemp":0.0,"mtemp":0.0,"brake":false,"ac":false}
{"type":"sync_end"}
```

`ts` is ESP32 uptime in ms — the app integrates buffered records using
consecutive `ts` deltas (clamped at 5 s), so buffered driving counts toward
trip distance/energy.

**Current data sources** (all CAN):

- Land Rover bus: `spd` ← 0x153, `soc` ← 0x613 fuel-gauge proxy, plus
  odometer (0x613) and oil temp (0x618) on the serial summary.
- OpenInverter (v5, bench-tested): actively polled via **CANopen SDO** —
  requests on 0x601 (node 1), replies on 0x581, unique-ID addressing
  (index `0x2100 | pid>>8`, sub `pid & 0xFF`), values s32 ÷ 32.
  Round-robin at 100 ms slots, 200 ms timeout, ~2.5 refreshes/s per param:
  `udc` (2001) → `batt`, `ilmax` (2005) → `curr`, `tmphs` (2019) → `btemp`
  ("Inverter Temp" in the app), `tmpm` (2020) → `mtemp`. Params fall back
  to 0.0 ("not reported") if not refreshed for >5 s.
- The gateway also answers 0x200 requests with a cached status frame on
  0x201 (4 × int16 LE, ×10) for any other node on the bus.
- `brake`/`ac` remain false until their bus sources are identified (operate
  them while watching the per-ID `changes` counters in the serial summary).

---

## Coolant gauge (0x329 TX)

The firmware transmits a synthetic 0x329 frame every 10 ms (hardware timer)
holding the coolant gauge at 60 °C. Safety behaviour:

- TX runs **from boot, unconditionally** — the donor engine ECU that owned
  0x329 is out of the car, so there is nothing to conflict with
- USB-serial commands for bench work: `STOP329`, `START329`, `SYNC`

Also built in: TWAI bus-off auto-recovery, chunked history flush (a big sync
can't stall CAN reception), classic-BT PIN `2837` (legacy pairing only).

---

## Setup

**ESP32 (Arduino IDE):** install the ESP32 board package (core 2.0.5+ or 3.x),
open `freelander-esp32/freelander-esp32.ino`, board *ESP32 Dev Module*,
flash at 115200 baud.

**Android:** open `~/AndroidStudioProjects/LandroverEV` in Android Studio and
run. Pair **"Freelander EV"** in Android Bluetooth settings first (PIN `2837`
if asked); the app then auto-connects to the last device on launch.

---

## Extending

- **Brake / A-C bits:** operate the pedal / A/C while watching the serial
  summary's per-ID `changes` counters, then decode in `getBrakeOn()` /
  `getAcOn()`.
- **OpenInverter values:** once its CAN is on this bus, fill in
  `getBattVolt()` / `getBattCurr()` / `getBattTempC()` / `getMotorTempC()`
  from the openinverter message IDs — the app already displays them, computes
  Wh/km efficiency, energy buckets, and projected range.
- If you add JSON keys, update **both** this firmware and the app's
  `parseCarData()` in `BluetoothService.kt`.

# Freelander EV: Wattson app and car interface

The electronics for a 2003 Land Rover Freelander 1 converted to electric, with
a Tesla small drive unit running OpenInverter. Two halves talk over Bluetooth:

- **Wattson**, the Android dashboard app on the ATOTO A6PF head unit
  (Android 10, 1024×600, system font scale about 1.3).
- **The car interface**, an ESP32 on a WeAct CAN485 board. It reads the Land
  Rover bus and the inverter over CAN, drives the cluster's rev counter,
  temperature and fuel gauges and the gear display, logs to a microSD card,
  streams live data to the app and takes firmware updates from it.

> Keep this repository private. `esp32/freelander-esp32/freelander-esp32.ino` holds the
> car interface's Bluetooth PIN (`BT_PIN`), and anyone with it can pair.

## What's where

| Folder | Contents |
|---|---|
| `android/` | The app's Android Studio project (Kotlin, XML views, package `com.landroverdash.app`). |
| `esp32/freelander-esp32/` | Car interface firmware. The change history for each version is at the top of the sketch. |
| `esp32/fuel_gauge_calibrate/` | Bench sketch for calibrating the fuel needle's vactrol. |
| `esp32/releases/` | Ready-to-install firmware builds (`.ino.bin`). |
| `esp32/README.md` | Original notes on the Bluetooth protocol between the app and the interface. Its hardware section predates the WeAct board. |
| `openinverter/` | Inverter parameter baseline (regen off, `il1gain`/`il2gain` 1.5). |
| `docs/` | Original mockup, code review and screenshots. |

## The app

Open `android/` in Android Studio, which creates `local.properties` on first
open. Build and install as usual. It needs Android 10 (API 29) or later.

## The car interface

Build with Arduino IDE and the **esp32 core 3.3.8**, board **ESP32 Dev
Module**, with these settings: Partition Scheme *Minimal SPIFFS (1.9MB APP
with OTA/128KB SPIFFS)*, CPU 240 MHz, Flash 80 MHz QIO 4 MB, PSRAM disabled,
Arduino Runs On and Events Run On both Core 1. Only core libraries are used.
The same build from the command line:

```sh
arduino-cli compile --fqbn "esp32:esp32:esp32:UploadSpeed=921600,CPUFreq=240,FlashFreq=80,FlashMode=qio,FlashSize=4M,PartitionScheme=min_spiffs,DebugLevel=none,PSRAM=disabled,LoopCore=1,EventsCore=1,EraseFlash=none,JTAGAdapter=default,ZigbeeMode=default" esp32/freelander-esp32
```

To install, use **Sketch → Export Compiled Binary** in Arduino IDE (or a file
from `esp32/releases/`), copy the `.ino.bin` to the head unit, then in the app
go to **Settings → Firmware → Choose file… → Install**. Do it with the car
stopped, the inverter off and the ignition on; it takes about 2 minutes. A USB
upload from Arduino IDE works too.

When you change the firmware:

- Bump `FW_VERSION` and add a note to the change list at the top of the sketch.
- Keep the boot banner `"Freelander EV Vehicle Interface " FW_VERSION`
  exactly as it is. The app and the interface use it to recognise the
  firmware, and they refuse anything below v7.1.

### Current calibration

`IDC_SCALE` (1.25 since v7.5) corrects the inverter's own current estimate.
To recalibrate, measure with a full charge on both ends: start full, drive,
charge back to full and read the kWh on the wallbox. Then:

```
new IDC_SCALE = current IDC_SCALE × (wallbox kWh × 0.87) / (app kWh)
```

The 0.87 allows for charger and battery losses. Don't recalibrate from the
battery %. It is worked out from pack voltage, and on a short drive straight
off the charger it overstates what was used.

## Design

The screen designs live in a private Claude design canvas:
<https://claude.ai/code/artifact/bdc46fa3-bf08-4e3a-9386-aa9197f2b889>

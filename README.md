# XIAO IMU — firmware + gym tracker

A Seeed Studio XIAO nRF52840 Sense streaming its 6-axis IMU over Bluetooth LE,
and an Android app that turns that stream into a gym log: rep counting, sets,
weight, and raw sample capture for training a better detector later.

Both halves are verified on real hardware.

| | |
|---|---|
| **[`firmware/`](firmware/)** | register-level LSM6DS3TR-C driver + BLE GATT service |
| **[`app/`](app/)** | Kotlin / Jetpack Compose app, BLE or laptop-bridged |

## What it does

**Firmware** — a driver written directly against the LSM6DS3TR-C datasheet (no
sensor library) that streams accelerometer, gyroscope and die temperature as a
20-byte notification, with a writable characteristic for rate and full-scale
range. At rest it reads `|accel| = 0.997 g` against an expected 1.000, with zero
dropped packets.

**App** — a home screen with a weekly activity chart and a tappable body map,
a searchable machine catalogue (plus custom machines), and per-machine set
tracking with automatic rep counting, weight entry and a set log.

## Two hardware findings worth knowing

If you are writing your own firmware for this board, these two cost real
debugging time:

**The IMU power pin needs HIGH drive.** P1.08 feeds the sensor's supply rail
directly rather than switching a load switch, so the pin has to source the
part's operating current. Standard drive cannot. Measured over four trials:

| Gate drive on P1.08 | I²C lines release |
|---|---|
| High drive (`H0H1`) | **2 ms** |
| Standard drive (`S0S1`) | **never** (still low after 3 s) |

With the rail down, the unpowered chip clamps SDA/SCL low — and this core's
`Wire` is an unbounded busy-wait, so the first I²C call then hangs forever.

**`--singlebank` breaks command-line uploads.** `arduino-cli`'s recipe for this
core passes it to `adafruit-nrfutil`; on this bootloader that writes the image
but never activates it. The upload reports `Device programmed` and the board
then sits in its bootloader forever. Drop the flag and dual-bank DFU works.

Details, register maps and the GATT layout are in
[`firmware/README.md`](firmware/README.md).

## Rep detection

Detection sits behind a single interface (`gym/RepDetector.kt`) so a model can
replace the shipped heuristic without touching set tracking, storage or UI.

The heuristic reduces accel and gyro to magnitudes (orientation-independent, so
mounting angle does not matter), band-passes each to the ~0.2–2 Hz range where
reps live, picks whichever channel carries more signal, normalises by its
running RMS, then counts rising edges through a Schmitt trigger with a
refractory period and an outlier gate for impacts.

**It is a heuristic, and it is not perfect.** Measured against 10 deliberate
movements it found the 10 real reps in a tight 0.92–1.10 amplitude band, plus
one marginal extra and one at 3.48 which was the board being set down. The
outlier gate now rejects that last class; an extra motion that genuinely looks
like a rep is not separable by any threshold. That is why the signal plot,
sensitivity slider and manual ± correction exist, and why hand-corrected sets
are recorded as such.

## Training data

Every set records its full raw sample stream to CSV alongside a JSON record
pairing `repsDetected` (algorithm) with `repsFinal` (ground truth after
correction) — the supervision signal for fitting a better detector. Export:

```sh
adb pull /sdcard/Android/data/dev.froa.xiaoimu/files/gym ./gym
```

The exported directory carries its own `SCHEMA.md`. See
[`app/README.md`](app/README.md).

## Getting started

Flash the firmware (Arduino IDE, "Seeed nRF52 Boards", board *Seeed XIAO
nRF52840 Sense*), then install the APK from
[Releases](../../releases) and pick **Bluetooth** on the tracking screen.

To run it in an Android emulator instead — which has no Bluetooth radio at all —
`app/tools/imu_bridge.py` relays the board over your laptop's Bluetooth to a TCP
socket the app reads.

## Status

Verified: firmware streaming, BLE service, the app's BLE and bridge paths,
storage and export, the home screen and body map.

Not yet verified: the impact-rejection gate against a real put-down, and rep
counting on an actual gym machine rather than hand movements.

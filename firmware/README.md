# XIAO nRF52840 Sense — IMU over BLE

Firmware that reads the onboard 6-axis IMU on a Seeed Studio XIAO nRF52840
Sense and streams it over Bluetooth LE, so you can watch it live on an Android
phone.

**Status: flashed and verified on hardware.** At rest the stream reads
`|accel| = 0.997 g` (expected 1.000), gyro ≈ 0 °/s, with zero dropped
sequence numbers.

## What's on the board

| Sensor | Part | Bus | Address |
|---|---|---|---|
| Accelerometer + gyroscope | LSM6DS3TR-C | `Wire1` (P0.07 SDA / P0.27 SCL) | `0x6A` |

**There is no magnetometer on this board.** The XIAO nRF52840 Sense carries
only the 6-axis LSM6DS3TR-C plus a PDM microphone, so this firmware streams
accelerometer + gyroscope + die temperature.

## Two board-specific gotchas the driver handles

Both of these cost real debugging time, so they're worth knowing:

**1. The IMU power pin needs HIGH DRIVE.** P1.08 feeds the sensor's supply rail
directly rather than switching a MOSFET, so the pin has to source the part's
operating current (~1 mA in high-performance mode). The nRF52's standard drive
cannot. Measured on this board, over four trials each:

| Gate drive on P1.08 | I²C lines release |
|---|---|
| High drive (`H0H1`) | **2 ms** |
| Standard drive (`S0S1`) | **never** (still low after 3 s) |

With the rail down, the unpowered chip clamps SDA/SCL low.

**2. A held-low bus hangs the whole sketch.** This core's `Wire` is an unbounded
busy-wait — `while(!EVENTS_TXSTARTED && !EVENTS_ERROR);` — with no timeout. On a
bus that's stuck low, TWIM never starts and the first I²C call never returns.
So `powerOnBoardSensor()` returns a bool and `begin()` refuses to touch a bus
that isn't released. Never call `Wire` on this board without checking first.

A related trap: the usual I²C-scanner idiom (a zero-length write) sets
`TXD.MAXCNT = 0` and hangs the same way. Always send at least one byte.

## Layout

```
firmware/xiao_imu_ble/
  xiao_imu_ble.ino        sampling loop, runtime config, battery sense
  lsm6ds3tr.h/.cpp        register-level LSM6DS3TR-C driver
  imu_ble_service.h/.cpp  GATT service
  i2c_reg.h               I²C register helper
```

## Build and flash

1. Arduino IDE → **File ▸ Preferences ▸ Additional Board Manager URLs**, add:

   ```
   https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
   ```

2. **Tools ▸ Board ▸ Boards Manager**, install **Seeed nRF52 Boards** — the
   plain package, **not** the mbed-enabled one. This firmware uses the Adafruit
   Bluefruit stack (`bluefruit.h`) that ships with the non-mbed core.

3. **Tools ▸ Board ▸ Seeed nRF52 Boards ▸ Seeed XIAO nRF52840 Sense**.

4. Open `xiao_imu_ble/xiao_imu_ble.ino`, select the port, and upload. If the
   port doesn't appear, double-tap RESET to enter the bootloader.

No external libraries are needed — the sensor driver is written directly
against the datasheet's register map.

`#include <Adafruit_TinyUSB.h>` is required for `Serial` to resolve on this
core. It's already in the sketch; don't remove it.

### If uploading from the command line

`arduino-cli`'s upload recipe for this core passes `--singlebank` to
`adafruit-nrfutil`. On this board's bootloader (UF2 0.6.1, SoftDevice S140
7.3.0) that **writes the image but never activates it** — the upload reports
`Device programmed`, then the board sits in the bootloader forever and the
sketch never runs. Drop the flag and let it use dual-bank DFU:

```sh
adafruit-nrfutil dfu genpkg --dev-type 0x0052 --sd-req 0x0123 \
  --application build/xiao_imu_ble.ino.hex fw.zip
adafruit-nrfutil dfu serial -pkg fw.zip -p /dev/ttyACM0 -b 115200
```

The board is in the application when its USB PID is `2886:8045`; `2886:0045`
means it's still in the bootloader. That's the quickest way to tell whether a
flash actually took.

## Reading it on Android

Install **nRF Connect for Mobile** (Nordic Semiconductor, free on Play Store).

1. **SCAN**, find `XIAO-IMU`, tap **CONNECT**.
2. Open service `a3c87500-8ed3-4bdf-8a39-a01bebede295`.
3. On the **IMU sample** characteristic, tap the triple-down-arrow (notify)
   icon.

Values start streaming immediately.

## GATT layout

Service `a3c87500-8ed3-4bdf-8a39-a01bebede295`

### `a3c87501-…` — IMU sample (notify, read), 20 bytes

Little-endian, fixed-point. 20 bytes is deliberate: it's the largest
notification that fits the default 23-byte ATT MTU, and Android never
renegotiates the MTU unless the app asks, so a bigger packet would arrive
silently truncated.

| Offset | Type | Field | Units |
|---|---|---|---|
| 0 | `u8` | `seq` | rolling counter — gaps mean dropped notifications |
| 1 | `u8` | `flags` | bit0 = a sample was dropped |
| 2 | `u32` | `t_ms` | device uptime at sample time, ms |
| 6 | `i16` | `ax` | milli-g (÷1000 → g) |
| 8 | `i16` | `ay` | milli-g |
| 10 | `i16` | `az` | milli-g |
| 12 | `i16` | `gx` | deci-deg/s (÷10 → °/s) |
| 14 | `i16` | `gy` | deci-deg/s |
| 16 | `i16` | `gz` | deci-deg/s |
| 18 | `i16` | `temp` | centi-°C (÷100 → °C) |

### `a3c87502-…` — Config (read, write), 4 bytes

Write all four bytes at once; out-of-range values are clamped and the clamped
result is echoed back on read.

| Offset | Field | Meaning |
|---|---|---|
| 0 | `rate_hz` | sampling rate, 1–208. `0` pauses the stream |
| 1 | `accel_g` | accel full scale: `2`, `4`, `8` or `16` |
| 2 | `gyro_code` | gyro full scale: `0`=245, `1`=500, `2`=1000, `3`=2000 °/s |
| 3 | `flags` | reserved, write 0 |

Example: `64 10 03 00` = 100 Hz, ±16 g, ±2000 °/s.

The sensor's own output data rate follows automatically — the firmware picks
the lowest ODR that's at least 2× the streaming rate, so the IMU isn't burning
power producing samples the link can't carry.

Standard Battery (`0x180F`) and Device Information (`0x180A`) services are also
present. Battery percentage is a coarse linear map of the 3.4–4.1 V range read
through the board's 1 MΩ/510 kΩ divider — a battery bar, not a fuel gauge.

## On the achievable rate

**The connection interval, not `rate_hz`, is the real ceiling.** One
notification goes out per connection event, and `notify()` blocks when the
radio's queue is full, so the sampling loop is paced by the link.

Measured over BlueZ on Linux: 16.6 Hz before requesting a faster interval,
33.2 Hz after (`setConnInterval(6, 12)` — 7.5–15 ms). Both with zero sequence
gaps. The central always has the final say on the interval; Android typically
negotiates faster than BlueZ, so expect to do better than 33 Hz there.

Because the ceiling is the link, requesting 100 Hz doesn't produce 100 Hz — it
produces the same link-limited rate. `seq` and `t_ms` always tell you what you
actually got.

## What this firmware does not do

There's no connectionless broadcast of samples in the advertising packet.
It was tried and removed: `BLEAdvertising::_start()` caches the advertising
data descriptor in a function-level `static`, so the data lengths are captured
on the first `start()` and never refreshed. Re-advertising changed content
makes `sd_ble_gap_adv_set_configure` fail, `start()` returns false, and the
device stops advertising altogether — it disappears completely, so you can't
even connect. The advertisement is therefore set up exactly once and never
touched again. Anything dynamic goes over a connection.

## Bringing up changes

Set `kSerialEcho = true` in the sketch to mirror samples to USB serial. It
costs enough time to cap the achievable rate, so leave it off otherwise.

Note that USB CDC output is dropped whenever no host has the port open, so
anything printed before your terminal attaches is lost — including the boot
banner. When debugging startup, either print from `loop()` or delay the
interesting work by a few seconds so you can attach first.

# XIAO IMU — Android app

Live readings and visualizations for the XIAO nRF52840 Sense firmware in
`../firmware`. Kotlin + Jetpack Compose.

**Status: verified against real hardware.** Streaming at ~32 Hz with zero
dropped packets; at rest it reads `|accel| = 1.021 g` and the orientation card
agrees with the raw axes to a tenth of a degree.

## The emulator problem, and the bridge

**The Android emulator has no Bluetooth radio.** It cannot see the board at
all — there's no host-Bluetooth passthrough, so BLE simply does not work
inside an AVD. That's a platform limitation, not a setup issue.

So the app has two interchangeable sources:

| Source | Use | How it gets data |
|---|---|---|
| **Bluetooth** | real phone | scans, connects, subscribes to notifications |
| **Bridge (TCP)** | emulator | reads packets relayed from the laptop |

`tools/imu_bridge.py` connects to the board over the **laptop's** Bluetooth and
re-streams the identical 20-byte packets on a TCP port. The emulator therefore
shows real data from real hardware, not a simulation. From inside an AVD the
host is reachable at `10.0.2.2`, which is what the app uses once it detects
it's running on an emulator.

## Running it on the emulator

```sh
# 1. terminal one - the bridge (needs bleak)
python -m venv .venv && .venv/bin/pip install bleak
.venv/bin/python tools/imu_bridge.py        # add --decode to print readings

# 2. build + install
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.froa.xiaoimu/.MainActivity
```

Then tap **Connect**. The source defaults to *Bridge (TCP)* on an emulator.

The board must be **advertising** for the bridge to find it. A BLE peripheral
stops advertising while it is connected to something, so if nRF Connect on your
phone still holds the connection, disconnect there first — otherwise the bridge
scans forever and finds nothing.

## Running it on a real phone

Install the same APK and pick **Bluetooth**. No bridge, no laptop. The app
requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on Android 12+, or
`ACCESS_FINE_LOCATION` below that, and scans by service UUID.

## What it shows

- **Readings** — accel (g), gyro (°/s) per axis, plus `|accel|`, die
  temperature and the current sequence number.
- **Strip charts** — scrolling traces for accel and gyro, drawn on a Compose
  Canvas. The vertical scale grows instantly to fit a spike but decays back
  slowly, so one sharp movement doesn't leave the trace permanently flattened.
- **Orientation** — a bubble level driven by the gravity vector, with pitch and
  roll. Only meaningful while the board is still: an accelerometer alone cannot
  tell gravity from linear acceleration.
- **Stats** — rate in Hz, total packets, and dropped count derived from gaps in
  the packet sequence number (modulo 256, since `seq` is one byte).

## Notes on the implementation

**Charts repaint on a ~30 fps tick, not per packet.** Recomposing on every
notification would do far more work than the display can show. Samples land in
a preallocated ring buffer (`ImuHistory`) with no per-sample allocation, and
the UI reads it on a timer (`ImuRepository.tick`).

**`compileSdk` is 37 while `targetSdk` is 35.** The AndroidX versions used here
require compiling against 37; targeting 35 keeps runtime behavior matched to
the API-35 emulator image. These are independent knobs.

**AGP 9 has Kotlin support built in** — adding `org.jetbrains.kotlin.android`
is now an error, not just redundant.

**Edge-to-edge is mandatory on API 35**, so the root layout needs
`safeDrawingPadding()` or content draws underneath the status bar.

## Screens

`Home` is the entry point; everything else is reached from it and returns
there with one back press.

| Screen | What it does |
|---|---|
| **Home** | weekly activity chart, tappable body map, shortcuts, recent sets |
| **Machines** | searchable list, optionally filtered to one muscle group |
| **Tracking** | live rep counting, weight, set log, raw capture |
| **Raw sensor** | the unfiltered IMU dashboard — charts, orientation, link stats |

### Weekly activity

Bars are **days trained per week** over the last 8 weeks, current week
highlighted. A "workout" is a distinct calendar day with at least one completed
set, so three sets in one evening count once, not three. Empty weeks draw a
stub bar so the axis reads continuously rather than looking broken.

### Body map

A front/back figure whose muscle groups are tappable; tapping one opens the
machine list filtered to that group, with a chip to drop the filter without
going back. `BodyRegion.category` is what links a region to its machines, so it
must match `Machine.category` exactly.

Regions are axis-aligned rounded boxes in normalised (0..1) coordinates rather
than real silhouette paths — hit-testing is then exact containment with no path
maths, and the shapes still read as a body at this size. Taps also resolve to
the *nearest* region within ~9dp, because a tap landing in the gap between the
thighs otherwise does nothing and just reads as a broken screen.

Back muscles need the reverse view, hence the Front/Back toggle: the front
exposes Chest/Shoulders/Arms/Core/Legs, the back merges the torso into a single
Back region. Cardio has no body region and is reachable via **All machines**.

## Gym tracking

Pick a machine (search matches name *and* category, so "leg" finds both
`Leg Press` and everything in the Legs group), or add a custom one. The
tracking screen counts reps from the sensor, takes a weight, and logs sets.
Sets close automatically after 6 s of stillness.

### Swapping in your own detector

Rep detection sits behind one interface, `gym/RepDetector.kt`. Everything
above it -- set boundaries, weight, persistence, recorded training data -- is
detector-agnostic. To plug in a model:

1. Implement `RepDetector`. `update(sample, nowMs)` is called once per sample,
   in order, on the transport thread, and returns `NONE`, `REP` or `REJECTED`.
2. Name it in one place:

   ```kotlin
   // MainActivity.onCreate
   tracker = WorkoutTracker(store, MyModelDetector())
   ```

Give it a distinct `name` (e.g. `model-v1`) and expose its tuning constants
via `params` -- both are written into every set record, so results stay
attributable to the detector that produced them.

The shipped `HeuristicRepDetector` (`heuristic-v1`) is described in its own
KDoc. In short: band-pass the accel and gyro magnitudes, pick whichever
channel carries more signal, normalise by its running RMS, then count rising
edges through a Schmitt trigger with a refractory period and an outlier gate
that discards impacts.

### Recorded data

Every set records its full raw sample stream, so a model can be fitted offline
later. Export the whole directory:

```sh
adb pull /sdcard/Android/data/dev.froa.xiaoimu/files/gym ./gym
```

```
gym/
  SCHEMA.md          field-by-field description, written by the app
  machines.json      custom machines
  weights.json       last weight per machine
  sets.jsonl         one JSON object per set, append-only
  recordings/<setId>.csv   raw samples for that set
```

`sets.jsonl` is newline-delimited JSON: append-only, never rewritten, so a
partial write can only cost the last line. Raw samples are CSV because that is
what a dataframe loads without ceremony.

**The supervision signal is `repsDetected` vs `repsFinal`.** The first is what
the algorithm produced; the second is ground truth after the user's `±`
correction. A set where they differ is a labelled failure case, and
`recordingFile` points at the samples behind it.

Recording columns are `t_host_ms, t_device_ms, seq, ax_g, ay_g, az_g, gx_dps,
gy_dps, gz_dps, temp_c, signal, active, rep_event`. The last three are the
incumbent detector's outputs, recorded so a replacement can be scored against
it on identical input -- train on the sensor columns, not on those.

Three things to know before analysing:

- **Sampling is not uniform.** The rate follows the BLE connection interval and
  drifts. Resample on `t_host_ms` before anything that assumes a fixed rate.
- **Recording starts when the screen connects**, so the CSV usually begins
  before the set's `startedAt`. Those leading rows are real idle baseline.
- `signal` is forced to 0 whenever `active` is 0, because the normalisation is
  meaningless below the noise floor and would otherwise spike into the tens.

Raw capture can be switched off per session on the tracking screen. A set is
roughly 2 KB per second of recording.

### Detection accuracy

Measured once against 10 deliberate movements: 10 genuine reps were detected
with amplitudes in a tight 0.92-1.10 band, plus one marginal extra at 0.90 and
one at 3.48 which was the board being set down. The outlier gate now rejects
that last class. An extra motion that genuinely looks like a rep is *not*
separable by any threshold, which is why the signal plot, the sensitivity
slider and manual `±` correction all exist -- and why hand-edited sets are
marked `✎` and carry a non-zero `manualDelta` in the data.

## Troubleshooting

### The board is invisible to my phone, and resetting it does nothing

Almost always: **something else is already connected to it.** A BLE peripheral
stops advertising while it is in a connection, so while the laptop bridge (or
nRF Connect on another device) holds the link, the board is invisible to
everything else. Pressing reset on the board does not help — the *host* end is
holding the connection.

Stop the bridge with Ctrl-C, which now releases the board on the way out. If it
was killed less gracefully, the OS may still be holding the link even though
the process is gone. On Linux:

```sh
bluetoothctl devices Connected          # is XIAO-IMU listed?
bluetoothctl disconnect <MAC>           # release it
```

Then confirm it is advertising again:

```sh
bluetoothctl --timeout 10 scan le | grep -i XIAO
```

### It does not show up in Android's Bluetooth settings

It will not, and that is expected. This is a BLE peripheral with a custom GATT
service and no pairing — such devices generally do not appear in the system
Bluetooth list. **There is nothing to pair.** Connect from this app (tracking
screen → **Bluetooth** → Connect) or from nRF Connect.

### Connected, but no data

Check the sensor status on the tracking screen. If it says connected but reps
never move and the signal plot is flat, the board may be streaming while sitting
still — the detector deliberately ignores motion below its noise floor. The
**Raw sensor** screen shows the unfiltered stream, which distinguishes "no data"
from "no movement".

## Build requirements

- A real **JDK** (not a JRE) — Gradle needs `javac`. On this machine every JVM
  under `/usr/lib/jvm` was a JRE, so builds failed with *"does not provide the
  required capabilities: [JAVA_COMPILER]"*. `sudo apt install openjdk-21-jdk`
  fixes it, or point `JAVA_HOME` at any JDK 17+.
- Android SDK with platform 37 and build-tools. `local.properties` points at
  it via `sdk.dir`.

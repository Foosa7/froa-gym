package dev.froa.xiaoimu.gym

/**
 * Written to gym/SCHEMA.md on first run so an exported data directory
 * describes itself without needing this repository.
 */
internal val SCHEMA_DOC = """
# XIAO IMU gym data — schemaVersion 1

Everything here is written by the Android app. Pull the whole directory with:

    adb pull /sdcard/Android/data/dev.froa.xiaoimu/files/gym ./gym

## Files

| File | Format | Contents |
|---|---|---|
| `machines.json` | JSON | user-defined machines (built-ins live in the app) |
| `weights.json` | JSON | last weight used per machine, kg |
| `sets.jsonl` | JSON Lines | one object per completed set, append-only |
| `recordings/<setId>.csv` | CSV | raw samples for that set |

`sets.jsonl` is append-only newline-delimited JSON: one record per line, never
rewritten, so a partial write can only cost the final line.

## sets.jsonl fields

| Field | Type | Meaning |
|---|---|---|
| `setId` | string | unique id; matches the recording filename |
| `machineId` / `machineName` / `machineCategory` | string | which machine |
| `startedAt` / `finishedAt` | int | epoch milliseconds |
| `startedAtIso` / `finishedAtIso` | string | same instants, UTC ISO-8601 |
| `durationMs` | int | first rep to set close |
| `weightKg` | float | as entered by the user |
| `repsDetected` | int | **raw detector output, before correction** |
| `repsFinal` | int | **ground truth after the user's correction** |
| `manualDelta` | int | `repsFinal - repsDetected` |
| `rejectedImpacts` | int | crossings discarded as collisions |
| `detector` | string | detector identity, e.g. `heuristic-v1` |
| `detectorParams` | object | that detector's tuning constants |
| `sampleCount` | int | rows in the recording |
| `sampleRateHz` | float | mean rate over the set |
| `recordingFile` | string/null | path relative to this directory |

`repsDetected` vs `repsFinal` is the supervision signal: `repsFinal` is what
actually happened, `repsDetected` is what the algorithm thought. A set where
they differ is a labelled failure case.

## recordings/<setId>.csv columns

| Column | Unit | Notes |
|---|---|---|
| `t_host_ms` | ms | phone clock on arrival — use this for timing |
| `t_device_ms` | ms | board uptime; wraps every ~49 days |
| `seq` | 0-255 | firmware packet counter; gaps mean dropped packets |
| `ax_g`, `ay_g`, `az_g` | g | accelerometer |
| `gx_dps`, `gy_dps`, `gz_dps` | deg/s | gyroscope |
| `temp_c` | °C | die temperature, updates ~1 Hz |
| `signal` | — | detector's scalar at that sample |
| `active` | 0/1 | detector considered the board moving |
| `rep_event` | 0/1/2 | 0 none, 1 rep counted, 2 impact rejected |

The last three columns are the incumbent detector's opinion, recorded so a new
model can be scored against it on identical input. They are outputs, not
inputs — train on the sensor columns.

## Caveats

- Sampling is **not uniform**. The rate follows the BLE connection interval and
  drifts; resample on `t_host_ms` before anything assuming a fixed rate.
- `seq` gaps mean genuinely missing samples, not just reordering.
- A set only exists here once it closed (6 s of stillness, or "Finish set").
  An abandoned set is never written.
- Recording starts when the machine screen connects, so the CSV usually begins
  **before** `startedAt` (which is the first detected rep). Those leading rows
  are genuine idle baseline — useful for calibration, but filter them out if
  you only want movement.
- `signal`, `active` and `rep_event` are the incumbent detector's outputs at
  that sample. `signal` is forced to 0 whenever `active` is 0, because the
  normalisation is meaningless below the noise floor.
""".trimIndent()

package dev.froa.xiaoimu.gym

import dev.froa.xiaoimu.ImuSample
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Counts repetitions from the IMU stream.
 *
 * A rep is a reciprocating motion, so the job is to turn the 6-axis stream
 * into one oscillating scalar and count its cycles. The pipeline:
 *
 *  1. Reduce accel and gyro to their magnitudes. Magnitudes are used because
 *     they are orientation-independent -- it should not matter how the board
 *     is strapped to the machine.
 *  2. Band-pass each: subtract a slow EMA baseline (removes gravity and any
 *     constant bias) and smooth with a fast EMA (removes sensor noise). What
 *     survives is roughly the 0.2-2 Hz band that human reps live in.
 *  3. Pick whichever of the two carries more signal, scaled by what counts as
 *     "a lot" for that quantity. Machines that pivot (lat pulldown, leg
 *     extension) show up strongly on the gyro; machines that translate
 *     (cable stacks, leg press) show up on the accelerometer.
 *  4. Normalise by that channel's running RMS, so thresholds are relative to
 *     how hard this particular person is moving this particular machine
 *     rather than absolute numbers that would need per-machine tuning.
 *  5. Count rising edges through a Schmitt trigger with a refractory period.
 *
 * This is a heuristic. It cannot distinguish a rep from a similar-looking
 * shake, and partial reps may or may not register. The UI shows the live
 * signal and lets the count be corrected by hand for exactly that reason.
 */
class HeuristicRepDetector : RepDetector {

    override val name = "heuristic-v1"

    override val params: Map<String, Any>
        get() = mapOf(
            "sensitivity" to sensitivity,
            "tauBaselineS" to TAU_BASELINE,
            "tauSmoothS" to TAU_SMOOTH,
            "tauRmsS" to TAU_RMS,
            "idleScore" to IDLE_SCORE,
            "highThreshold" to HIGH_THRESHOLD,
            "lowThreshold" to LOW_THRESHOLD,
            "minRepMs" to MIN_REP_MS,
            "outlierRatio" to OUTLIER_RATIO,
        )

    /** Higher = counts smaller motions. 1.0 is the default. */
    override var sensitivity: Float = 1.0f

    var reps: Int = 0; private set
    /** Normalised detection signal, for plotting. Roughly -3..3. */
    override var signal: Float = 0f; private set
    override val plotThreshold: Float get() = HIGH_THRESHOLD / sensitivity
    /** True while the board is moving enough to be considered active. */
    override var active: Boolean = false; private set
    var lastRepAtMs: Long = 0L; private set

    private var accelBaseline = 0f
    private var gyroBaseline = 0f
    private var accelSmooth = 0f
    private var gyroSmooth = 0f
    private var accelMsq = 0f      // EMA of squared band-passed accel
    private var gyroMsq = 0f
    private var started = false

    private var armed = false      // Schmitt: has dipped below the low threshold
    private var lastSampleMs = 0L

    // Running mean of accepted rep amplitudes, used to spot impacts. Within a
    // set, real reps are strikingly consistent -- measured on this hardware,
    // ten deliberate reps all landed between 0.92 and 1.10 -- while setting
    // the board down produced 3.48. An outlier that far out is a collision,
    // not a repetition.
    private var ampMean = 0f
    private var accepted = 0
    /** Crossings discarded as impacts. Surfaced so the UI can be honest. */
    override var rejectedImpacts: Int = 0; private set

    override fun reset() {
        reps = 0
        signal = 0f
        active = false
        armed = false
        lastRepAtMs = 0L
        started = false
        accelMsq = 0f; gyroMsq = 0f
        ampMean = 0f; accepted = 0; rejectedImpacts = 0
    }

    override fun update(sample: ImuSample, nowMs: Long): RepEvent {
        val s = sample
        val aMag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        val gMag = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)

        if (!started) {
            started = true
            accelBaseline = aMag; gyroBaseline = gMag
            accelSmooth = 0f; gyroSmooth = 0f
            lastSampleMs = nowMs
            return RepEvent.NONE
        }

        // Sample interval varies with the BLE connection interval, so derive
        // the filter coefficients from real elapsed time instead of assuming a
        // fixed rate.
        val dt = ((nowMs - lastSampleMs).coerceIn(1, 500)) / 1000f
        lastSampleMs = nowMs

        val aSlow = emaAlpha(dt, TAU_BASELINE)
        val aFast = emaAlpha(dt, TAU_SMOOTH)

        accelBaseline += (aMag - accelBaseline) * aSlow
        gyroBaseline += (gMag - gyroBaseline) * aSlow
        accelSmooth += ((aMag - accelBaseline) - accelSmooth) * aFast
        gyroSmooth += ((gMag - gyroBaseline) - gyroSmooth) * aFast

        val aRms = emaAlpha(dt, TAU_RMS).let { k ->
            accelMsq += (accelSmooth * accelSmooth - accelMsq) * k
            sqrt(accelMsq)
        }
        val gRms = emaAlpha(dt, TAU_RMS).let { k ->
            gyroMsq += (gyroSmooth * gyroSmooth - gyroMsq) * k
            sqrt(gyroMsq)
        }

        // Scale each channel by a typical rep amplitude so they are comparable.
        val aScore = aRms / TYPICAL_ACCEL_G
        val gScore = gRms / TYPICAL_GYRO_DPS
        val useGyro = gScore > aScore
        val raw = if (useGyro) gyroSmooth else accelSmooth
        val rms = if (useGyro) gRms else aRms

        active = maxOf(aScore, gScore) > IDLE_SCORE
        // Below the noise floor `raw / rms` is noise divided by noise and can
        // reach absurd values. Report zero instead: it keeps the plot honest
        // and, more importantly, keeps the recorded `signal` column usable as
        // training data rather than full of idle-time spikes.
        signal = if (active && rms > 1e-6f) raw / rms else 0f

        if (!active) {
            // Below the noise floor the normalised signal is meaningless --
            // dividing noise by noise yields something that crosses thresholds
            // constantly. Stay disarmed so a still board never counts.
            armed = false
            return RepEvent.NONE
        }

        val high = HIGH_THRESHOLD / sensitivity
        val low = LOW_THRESHOLD / sensitivity

        if (signal < low) armed = true

        if (armed && signal > high) {
            val sinceLast = nowMs - lastRepAtMs
            if (lastRepAtMs == 0L || sinceLast >= MIN_REP_MS) {
                armed = false
                // Only judge outliers once there are enough accepted reps to
                // have a meaningful baseline; early in a set anything goes.
                val isImpact = accepted >= MIN_REPS_FOR_BASELINE &&
                    signal > OUTLIER_RATIO * ampMean
                if (isImpact) {
                    rejectedImpacts++
                    android.util.Log.d(
                        "RepCounter",
                        "impact rejected signal=${"%.2f".format(signal)} " +
                            "mean=${"%.2f".format(ampMean)}"
                    )
                    return RepEvent.REJECTED
                }
                ampMean = if (accepted == 0) signal
                          else ampMean + (signal - ampMean) / (accepted + 1).coerceAtMost(8)
                accepted++
                lastRepAtMs = nowMs
                reps++
                return RepEvent.REP
            }
        }
        return RepEvent.NONE
    }

    private fun emaAlpha(dt: Float, tau: Float): Float =
        (dt / (tau + dt)).coerceIn(0f, 1f)

    private companion object {
        const val TAU_BASELINE = 1.5f     // s, tracks gravity/bias
        const val TAU_SMOOTH = 0.10f      // s, noise removal
        const val TAU_RMS = 2.0f          // s, amplitude estimate

        // What a moderate rep looks like on each channel, used only to decide
        // which channel is carrying the motion.
        const val TYPICAL_ACCEL_G = 0.12f
        const val TYPICAL_GYRO_DPS = 25f

        const val IDLE_SCORE = 0.35f      // below this the board is treated as still
        const val HIGH_THRESHOLD = 0.9f   // rising edge, in units of RMS
        const val LOW_THRESHOLD = -0.35f  // must dip below this to re-arm
        const val MIN_REP_MS = 350L       // refractory: max ~2.8 reps/second
        const val OUTLIER_RATIO = 2.5f    // above this multiple of the set's mean = impact
        const val MIN_REPS_FOR_BASELINE = 3
    }
}

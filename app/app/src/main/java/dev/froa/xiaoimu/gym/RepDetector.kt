package dev.froa.xiaoimu.gym

import dev.froa.xiaoimu.ImuSample

/**
 * Turns the raw IMU stream into rep events.
 *
 * This is the seam for swapping in a different model. Everything above it --
 * set boundaries, weight, persistence, the recorded training data -- is
 * detector-agnostic, so a replacement only has to implement this interface and
 * be named in one place (`WorkoutTracker`'s constructor).
 *
 * Contract:
 *  - [update] is called once per received sample, on the transport thread,
 *    in order. It must not block.
 *  - [signal] is whatever scalar the detector wants plotted; the UI draws it
 *    against [plotThreshold] and does not interpret the units.
 *  - Implementations should be reset-able mid-session via [reset].
 */
interface RepDetector {
    /** Stable identifier recorded with every set, e.g. "heuristic-v1". */
    val name: String

    /** Detector knobs, recorded alongside each set so results are reproducible. */
    val params: Map<String, Any>

    /** Latest value of the detection signal, for plotting. */
    val signal: Float

    /** Threshold to draw on the plot, in the same units as [signal]. */
    val plotThreshold: Float

    /** True while the sensor is moving enough to be worth judging. */
    val active: Boolean

    /** Crossings discarded as impacts rather than reps. */
    val rejectedImpacts: Int

    /** User-facing sensitivity, 0.5..2.5, 1.0 = default. */
    var sensitivity: Float

    fun reset()

    /** Feeds one sample. Returns what happened on this sample. */
    fun update(sample: ImuSample, nowMs: Long): RepEvent
}

/** What a single sample produced. */
enum class RepEvent {
    /** Nothing of note. */
    NONE,

    /** A repetition completed on this sample. */
    REP,

    /** A crossing was seen but discarded as an impact / artefact. */
    REJECTED,
}

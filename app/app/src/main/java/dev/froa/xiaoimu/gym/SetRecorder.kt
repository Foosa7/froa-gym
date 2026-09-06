package dev.froa.xiaoimu.gym

import dev.froa.xiaoimu.ImuSample
import java.io.File

/**
 * Buffers the raw sample stream for one set and writes it as CSV.
 *
 * Samples are collected in memory and flushed once, when the set closes:
 * writing per sample would put file IO on the transport callback thread at
 * tens of hertz for no benefit.
 *
 * The recorded columns include the detector's own signal and its rep/reject
 * events, so a future model can be trained and compared against the current
 * heuristic on exactly the same data.
 */
class SetRecorder(private val recordingsDir: File) {

    private val rows = StringBuilder()
    private var count = 0
    private var firstHostMs = 0L
    private var lastHostMs = 0L

    var enabled: Boolean = true

    val sampleCount: Int get() = count

    /** Mean rate over the set, or 0 if there is not enough to tell. */
    val sampleRateHz: Float
        get() = if (count > 1 && lastHostMs > firstHostMs) {
            count * 1000f / (lastHostMs - firstHostMs)
        } else 0f

    fun reset() {
        rows.setLength(0)
        count = 0
        firstHostMs = 0L
        lastHostMs = 0L
    }

    fun add(s: ImuSample, hostMs: Long, signal: Float, active: Boolean, event: RepEvent) {
        if (!enabled) return
        if (count >= MAX_SAMPLES) return       // bound memory on a runaway set
        if (count == 0) firstHostMs = hostMs
        lastHostMs = hostMs
        count++
        rows.append(hostMs).append(',')
            .append(s.tMs).append(',')
            .append(s.seq).append(',')
            .append(s.ax).append(',').append(s.ay).append(',').append(s.az).append(',')
            .append(s.gx).append(',').append(s.gy).append(',').append(s.gz).append(',')
            .append(s.tempC).append(',')
            .append(signal).append(',')
            .append(if (active) 1 else 0).append(',')
            .append(
                when (event) {
                    RepEvent.REP -> 1
                    RepEvent.REJECTED -> 2
                    RepEvent.NONE -> 0
                }
            )
            .append('\n')
    }

    /** Writes the buffer. Returns the path relative to the gym/ root, or null. */
    fun flush(setId: String): String? {
        if (!enabled || count == 0) return null
        return runCatching {
            val f = recordingsDir.resolve("$setId.csv")
            f.writeText(HEADER + rows)
            "recordings/${f.name}"
        }.getOrNull()
    }

    private companion object {
        const val HEADER =
            "t_host_ms,t_device_ms,seq,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps," +
                "temp_c,signal,active,rep_event\n"

        /** ~10 minutes at 33 Hz; a single set should never approach this. */
        const val MAX_SAMPLES = 20_000
    }
}

package dev.froa.xiaoimu

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One decoded reading from the XIAO. Mirrors the 20-byte `ImuPacket` the
 * firmware notifies; see firmware/README.md for the wire format.
 */
data class ImuSample(
    val seq: Int,
    val flags: Int,
    val tMs: Long,
    val ax: Float, val ay: Float, val az: Float,   // g
    val gx: Float, val gy: Float, val gz: Float,   // deg/s
    val tempC: Float,
) {
    /** Magnitude of the acceleration vector. Reads ~1.0 g when at rest. */
    val accelMagnitude: Float get() = sqrt(ax * ax + ay * ay + az * az)

    /**
     * Tilt from the gravity vector, in degrees. Only meaningful while the
     * board is roughly still -- any linear acceleration is indistinguishable
     * from gravity to an accelerometer on its own.
     */
    val rollDeg: Float get() = Math.toDegrees(atan2(ay.toDouble(), az.toDouble())).toFloat()
    val pitchDeg: Float
        get() = Math.toDegrees(
            atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))
        ).toFloat()

    companion object {
        const val SIZE_BYTES = 20
        const val FLAG_DROPPED = 0x01

        /** Decodes the firmware's little-endian fixed-point packet. */
        fun parse(b: ByteArray): ImuSample? {
            if (b.size < SIZE_BYTES) return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val seq = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val tMs = buf.int.toLong() and 0xFFFFFFFFL
            // Fixed-point scales chosen by the firmware so everything fits in
            // 20 bytes: milli-g, deci-deg/s, centi-degC.
            val ax = buf.short / 1000f
            val ay = buf.short / 1000f
            val az = buf.short / 1000f
            val gx = buf.short / 10f
            val gy = buf.short / 10f
            val gz = buf.short / 10f
            val temp = buf.short / 100f
            return ImuSample(seq, flags, tMs, ax, ay, az, gx, gy, gz, temp)
        }
    }
}

/** How the app is getting its data. */
enum class SourceKind { BLE, BRIDGE }

sealed interface ConnState {
    data object Idle : ConnState
    data object Scanning : ConnState
    data object Connecting : ConnState
    data class Connected(val detail: String) : ConnState
    data class Failed(val reason: String) : ConnState
}

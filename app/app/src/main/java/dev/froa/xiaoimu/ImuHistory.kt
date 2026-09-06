package dev.froa.xiaoimu

/**
 * Fixed-capacity ring buffer of recent samples, for the strip charts.
 *
 * Deliberately plain arrays and no allocation per sample: at tens of packets
 * per second, allocating a list entry each time would keep the collector busy
 * for no reason. Writes happen on the BLE/socket callback thread and reads on
 * the UI thread; the buffer is only ever appended to and readers tolerate
 * seeing a sample mid-write, which for a scrolling chart is invisible.
 */
class ImuHistory(val capacity: Int = 256) {
    private val ax = FloatArray(capacity)
    private val ay = FloatArray(capacity)
    private val az = FloatArray(capacity)
    private val gx = FloatArray(capacity)
    private val gy = FloatArray(capacity)
    private val gz = FloatArray(capacity)

    @Volatile var count: Int = 0; private set
    private var head = 0

    fun add(s: ImuSample) {
        val i = head
        ax[i] = s.ax; ay[i] = s.ay; az[i] = s.az
        gx[i] = s.gx; gy[i] = s.gy; gz[i] = s.gz
        head = (i + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() { count = 0; head = 0 }

    /** Copies the last [n] values of one channel in oldest-to-newest order. */
    fun series(channel: Channel, out: FloatArray): Int {
        val src = when (channel) {
            Channel.AX -> ax; Channel.AY -> ay; Channel.AZ -> az
            Channel.GX -> gx; Channel.GY -> gy; Channel.GZ -> gz
        }
        val n = minOf(count, out.size)
        // Walk backwards from the newest sample so a concurrent append can
        // only ever add data we simply do not draw this frame.
        for (k in 0 until n) {
            val idx = ((head - 1 - k) % capacity + capacity) % capacity
            out[n - 1 - k] = src[idx]
        }
        return n
    }

    enum class Channel { AX, AY, AZ, GX, GY, GZ }
}

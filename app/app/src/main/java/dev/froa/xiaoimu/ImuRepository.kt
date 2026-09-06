package dev.froa.xiaoimu

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns whichever source is active and accumulates stats. The UI observes
 * [state], [latest] and [tick]; charts read [history] directly.
 */
class ImuRepository(private val context: Context) {

    val history = ImuHistory(capacity = 256)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _latest = MutableStateFlow<ImuSample?>(null)
    val latest: StateFlow<ImuSample?> = _latest

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    /**
     * Bumped at most every [FRAME_MS] so the charts repaint at a steady frame
     * rate instead of once per packet. Recomposing on every notification would
     * do far more work than the display can show.
     */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    // Extra consumers of every sample (the workout tracker). Kept separate
    // from `latest` because rep detection must see every packet, not just the
    // ones that survive the UI's frame-rate throttling.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ImuSample) -> Unit>()

    private var source: ImuSource? = null
    private var lastSeq = -1
    private var rateWindowStart = 0L
    private var rateWindowCount = 0
    private var lastTickAt = 0L

    data class Stats(
        val rateHz: Float = 0f,
        val received: Long = 0,
        val dropped: Long = 0,
    )

    /** The emulator has no Bluetooth radio, so default it to the bridge. */
    fun defaultSource(): SourceKind =
        if (isEmulator()) SourceKind.BRIDGE else SourceKind.BLE

    fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.lowercase().contains("emulator") ||
            Build.MODEL.contains("sdk_gphone") ||
            Build.MODEL.contains("Emulator") ||
            Build.PRODUCT.contains("sdk")

    fun connect(kind: SourceKind, bridgeHost: String, bridgePort: Int) {
        disconnect()
        resetStats()
        val s = when (kind) {
            SourceKind.BLE -> BleSource(context, ::onSample, ::onState)
            SourceKind.BRIDGE -> BridgeSource(bridgeHost, bridgePort, ::onSample, ::onState)
        }
        source = s
        s.start()
    }

    /** True when the active source can send commands back to the board. */
    fun canControlDevice(): Boolean = source is BleSource && state.value is ConnState.Connected

    /**
     * Asks the board to power down. It will disconnect and stop advertising;
     * only the RESET button brings it back.
     */
    fun sleepSensor(): Boolean {
        val cfg = ImuRepository.sleepCommand()
        return source?.writeConfig(cfg) ?: false
    }

    fun disconnect() {
        source?.stop()
        source = null
        _state.value = ConnState.Idle
    }

    private fun resetStats() {
        history.clear()
        lastSeq = -1
        rateWindowStart = 0
        rateWindowCount = 0
        _stats.value = Stats()
        _latest.value = null
    }

    private fun onState(s: ConnState) { _state.value = s }

    fun addSampleListener(l: (ImuSample) -> Unit) { listeners.add(l) }
    fun removeSampleListener(l: (ImuSample) -> Unit) { listeners.remove(l) }

    private fun onSample(s: ImuSample) {
        history.add(s)
        _latest.value = s
        listeners.forEach { it(s) }

        val now = System.currentTimeMillis()
        var dropped = _stats.value.dropped
        if (lastSeq >= 0) {
            // seq is a byte, so gaps are counted modulo 256.
            val gap = ((s.seq - lastSeq) + 256) % 256
            if (gap > 1) dropped += (gap - 1).toLong()
        }
        lastSeq = s.seq

        rateWindowCount++
        if (rateWindowStart == 0L) rateWindowStart = now
        var rate = _stats.value.rateHz
        val elapsed = now - rateWindowStart
        if (elapsed >= 1000) {
            rate = rateWindowCount * 1000f / elapsed
            rateWindowStart = now
            rateWindowCount = 0
        }
        _stats.value = Stats(rate, _stats.value.received + 1, dropped)

        if (now - lastTickAt >= FRAME_MS) {
            lastTickAt = now
            _tick.value = _tick.value + 1
        }
    }

    companion object {
        private const val FRAME_MS = 33L   // ~30 fps

        /** ConfigPacket with the sleep bit set; other fields are left alone. */
        fun sleepCommand(): ByteArray = byteArrayOf(50, 4, 3, CFG_SLEEP)

        /** ConfigPacket.flags bit0 — matches the firmware's CFG_SLEEP. */
        const val CFG_SLEEP: Byte = 0x01
    }
}

/** A live feed of samples from somewhere. */
interface ImuSource {
    fun start()
    fun stop()

    /**
     * Writes the 4-byte config characteristic. Only the direct BLE source can
     * do this: the bridge is a one-way relay of notifications, with no path
     * back to the board.
     */
    fun writeConfig(data: ByteArray): Boolean = false
}

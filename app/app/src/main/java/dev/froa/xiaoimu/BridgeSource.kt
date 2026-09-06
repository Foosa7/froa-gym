package dev.froa.xiaoimu

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Reads samples from the laptop-side bridge over TCP.
 *
 * This exists because the Android emulator has no Bluetooth radio -- it cannot
 * see the XIAO at all. The bridge (tools/imu_bridge.py) connects to the board
 * over the laptop's own Bluetooth and re-streams the identical 20-byte packets
 * on a socket, so the emulator shows real live data from real hardware rather
 * than a simulation.
 *
 * From inside the emulator the host is reachable at 10.0.2.2.
 */
class BridgeSource(
    private val host: String,
    private val port: Int,
    private val onSample: (ImuSample) -> Unit,
    private val onState: (ConnState) -> Unit,
) : ImuSource {

    @Volatile private var running = false
    private var socket: Socket? = null
    private var worker: Thread? = null

    override fun start() {
        running = true
        onState(ConnState.Connecting)
        worker = thread(name = "imu-bridge", isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true      // 20-byte packets: never wait to coalesce
                socket = s
                onState(ConnState.Connected("$host:$port"))

                val input = DataInputStream(s.getInputStream())
                val buf = ByteArray(ImuSample.SIZE_BYTES)
                while (running) {
                    // Packets are fixed size and framed by that alone, so a
                    // full read is the whole frame.
                    input.readFully(buf)
                    ImuSample.parse(buf)?.let(onSample)
                }
            } catch (e: Exception) {
                if (running) onState(ConnState.Failed(e.message ?: "bridge error"))
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        worker = null
    }

    private companion object { const val CONNECT_TIMEOUT_MS = 4000 }
}

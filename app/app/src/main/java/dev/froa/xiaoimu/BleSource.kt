package dev.froa.xiaoimu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Talks to the XIAO directly over BLE. Used on a real phone. */
@SuppressLint("MissingPermission")   // the UI requests these before connecting
class BleSource(
    private val context: Context,
    private val onSample: (ImuSample) -> Unit,
    private val onState: (ConnState) -> Unit,
) : ImuSource {

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onState(ConnState.Failed("Bluetooth is off"))
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            onState(ConnState.Failed("No BLE scanner (emulator? use the bridge)"))
            return
        }
        onState(ConnState.Scanning)
        // Filter on the service UUID rather than the name: the firmware puts
        // the name in the scan response, and a filter on service UUID matches
        // the advertisement itself.
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                stopScan()
                onState(ConnState.Failed("No XIAO-IMU found. Is it powered?"))
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    override fun stop() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            onState(ConnState.Connecting)
            // TRANSPORT_LE, not AUTO: this peripheral is BLE-only, and
            // letting the stack pick can end up attempting BR/EDR first.
            gatt = result.device.connectGatt(
                context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onState(ConnState.Failed("Scan failed (code $errorCode)"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onState(ConnState.Failed("Disconnected"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            val chr = svc?.getCharacteristic(SAMPLE_UUID)
            if (chr == null) {
                onState(ConnState.Failed("IMU service not found on device"))
                return
            }
            g.setCharacteristicNotification(chr, true)
            // Enabling notifications needs the CCCD written too; the local
            // setCharacteristicNotification call alone does not tell the peer.
            val cccd = chr.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            onState(ConnState.Connected(g.device.address ?: "BLE"))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (chr.uuid == SAMPLE_UUID) ImuSample.parse(value)?.let(onSample)
        }

        @Deprecated("Pre-API 33 callback; kept so older phones still receive data")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic,
        ) {
            if (chr.uuid == SAMPLE_UUID) chr.value?.let { ImuSample.parse(it)?.let(onSample) }
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a3c87500-8ed3-4bdf-8a39-a01bebede295")
        val SAMPLE_UUID: UUID = UUID.fromString("a3c87501-8ed3-4bdf-8a39-a01bebede295")
        val CONFIG_UUID: UUID = UUID.fromString("a3c87502-8ed3-4bdf-8a39-a01bebede295")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 12_000L
    }
}

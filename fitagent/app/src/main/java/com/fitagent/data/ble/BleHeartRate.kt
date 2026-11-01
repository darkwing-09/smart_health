package com.fitagent.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/*
 * Live heart rate over the standard Bluetooth Heart Rate Profile (R3): chest straps and watches with
 * "broadcast heart rate" (Garmin, Amazfit, Polar, Coros…). Closed watches like Noise/boAt don't broadcast it.
 */

val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** One Heart Rate Measurement notification. */
data class HrMeasurement(val bpm: Int, val rrMs: List<Int>, val contact: Boolean?)

/**
 * Parses a Heart Rate Measurement characteristic (Bluetooth SIG GATT spec 0x2A37): flags byte, 8- or 16-bit bpm,
 * optional energy expended, optional RR intervals in 1/1024 s. Returns null for malformed packets.
 */
fun parseHeartRate(bytes: ByteArray): HrMeasurement? {
    if (bytes.size < 2) return null
    val flags = bytes[0].toInt() and 0xFF
    val wide = flags and 0x01 != 0
    var i = 1
    val bpm = if (wide) {
        if (bytes.size < 3) return null
        (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
    } else bytes[1].toInt() and 0xFF
    i += if (wide) 2 else 1
    val contact = when ((flags shr 1) and 0x03) { 0x02 -> false; 0x03 -> true; else -> null }
    if (flags and 0x08 != 0) i += 2 // energy expended
    val rr = mutableListOf<Int>()
    if (flags and 0x10 != 0) {
        while (i + 1 < bytes.size) {
            val raw = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            rr += raw * 1000 / 1024
            i += 2
        }
    }
    return if (bpm in 25..250) HrMeasurement(bpm, rr, contact) else null
}

sealed interface BleState {
    data object Off : BleState
    data object NoPermission : BleState
    data class Scanning(val devices: List<Pair<String, String>>) : BleState
    data class Connecting(val name: String) : BleState
    data class Connected(val name: String, val bpm: Int?) : BleState
    data class Error(val message: String) : BleState
}

/** Scans for, connects to and streams from one heart-rate sensor. Reconnects automatically while wanted. */
@SuppressLint("MissingPermission") // every entry point checks hasPermission() first
class BleHeartRateMonitor(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ble_hr", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<BleState>(BleState.Off)
    val state: StateFlow<BleState> = _state.asStateFlow()
    private var gatt: BluetoothGatt? = null
    private var wanted: String? = null
    private val found = linkedMapOf<String, String>()

    val rememberedAddress: String? get() = prefs.getString("address", null)

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            found[d.address] = d.name ?: result.scanRecord?.deviceName ?: "Heart-rate sensor"
            _state.value = BleState.Scanning(found.map { it.key to it.value })
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = BleState.Error("Bluetooth scan failed ($errorCode)")
        }
    }

    fun scan() {
        if (!hasPermission()) { _state.value = BleState.NoPermission; return }
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: run { _state.value = BleState.Error("Turn on Bluetooth to find heart-rate sensors"); return }
        found.clear()
        _state.value = BleState.Scanning(emptyList())
        runCatching {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        }.onFailure { _state.value = BleState.Error(it.message ?: "Scan failed") }
        main.postDelayed({ stopScan() }, SCAN_MS)
    }

    private fun stopScan() {
        if (!hasPermission()) return
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    fun connect(address: String) {
        if (!hasPermission()) { _state.value = BleState.NoPermission; return }
        stopScan()
        val device = runCatching { manager?.adapter?.getRemoteDevice(address) }.getOrNull()
            ?: run { _state.value = BleState.Error("Sensor not found"); return }
        wanted = address
        prefs.edit().putString("address", address).apply()
        _state.value = BleState.Connecting(found[address] ?: device.name ?: "Heart-rate sensor")
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wanted = null
        stopScan()
        if (hasPermission()) gatt?.close()
        gatt = null
        _state.value = BleState.Off
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    val address = wanted
                    if (address != null) { // dropped mid-workout: retry
                        _state.value = BleState.Connecting(g.device.name ?: "Heart-rate sensor")
                        main.postDelayed({ if (wanted == address) connect(address) }, RECONNECT_MS)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
                ?: run { _state.value = BleState.Error("This device doesn't share heart rate"); return }
            g.setCharacteristicNotification(ch, true)
            val descriptor = ch.getDescriptor(CLIENT_CONFIG) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            _state.value = BleState.Connected(g.device.name ?: "Heart-rate sensor", null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = emit(g, value)

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) emit(g, ch.value ?: return)
        }

        private fun emit(g: BluetoothGatt, value: ByteArray) {
            val m = parseHeartRate(value) ?: return
            _state.value = BleState.Connected(g.device.name ?: "Heart-rate sensor", m.bpm)
        }
    }

    companion object {
        private const val SCAN_MS = 15_000L
        private const val RECONNECT_MS = 3_000L
    }
}

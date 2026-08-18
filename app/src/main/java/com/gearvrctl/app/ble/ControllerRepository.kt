package com.gearvrctl.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import com.gearvrctl.app.protocol.RawPacketLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScanSighting(val name: String?, val address: String, val rssi: Int)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Disconnected(val reason: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Owns the single BLE connection to the Gear VR Controller. Meant to live as long as the
 * process (held by the Application), so both the UI and (later) the AccessibilityService can
 * observe the same connection instead of each opening their own GATT link.
 */
class ControllerRepository(private val appContext: Context) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _rawPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val rawPackets = _rawPackets.asSharedFlow()

    /** Every BLE device seen while scanning — debug aid for confirming the controller's advertised name. */
    private val _scanLog = MutableSharedFlow<ScanSighting>(extraBufferCapacity = 64)
    val scanLog = _scanLog.asSharedFlow()

    /**
     * Full-session hex dump of every raw packet, uncapped — for exporting a capture. Opt-in and
     * off by default: the BLE connection stays open for as long as the Accessibility Service is
     * enabled (i.e. all the time during normal use), so leaving this on unconditionally grows
     * this buffer forever and eventually crashes with an OOM. Only the Logger/dev-tools screen
     * turns it on, for actual debugging sessions.
     */
    var debugCaptureEnabled = false
    private val captureBuffer = StringBuilder()
    private var captureCount = 0

    private var gatt: BluetoothGatt? = null
    private val bluetoothManager by lazy {
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val scanner by lazy { BleScanner(bluetoothManager.adapter.bluetoothLeScanner) }

    @SuppressLint("MissingPermission")
    fun connect() {
        if (!BlePermissions.hasAll(appContext)) {
            _connectionState.value = ConnectionState.Error("Missing Bluetooth permissions")
            return
        }
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is off")
            return
        }

        _connectionState.value = ConnectionState.Scanning
        scanner.start(
            onFound = { device -> connectToDevice(device) },
            onAnySeen = { name, address, rssi ->
                _scanLog.tryEmit(ScanSighting(name, address, rssi))
            },
            onError = { code -> _connectionState.value = ConnectionState.Error("Scan failed ($code)") },
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        val callback = ControllerGattCallback(
            onConnected = { _connectionState.value = ConnectionState.Connected },
            onDisconnected = { status -> _connectionState.value = ConnectionState.Disconnected("status=$status") },
            onNotificationsReady = { enableSensorStreaming() },
            onRawPacket = { bytes ->
                if (debugCaptureEnabled) {
                    captureCount += 1
                    captureBuffer.append(RawPacketLogger.summaryLine(captureCount, bytes)).append('\n')
                }
                _rawPackets.tryEmit(bytes)
            },
        )
        gatt = device.connectGatt(appContext, false, callback)
    }

    fun captureText(): String = captureBuffer.toString()

    fun clearCapture() {
        captureBuffer.clear()
        captureCount = 0
    }

    @SuppressLint("MissingPermission")
    private fun enableSensorStreaming() {
        val g = gatt ?: return
        val characteristic = g.getService(GattUuids.SERVICE)
            ?.getCharacteristic(GattUuids.WRITE_CHARACTERISTIC) ?: return
        writeCharacteristic(g, characteristic, GattUuids.ENABLE_SENSOR_MODE)
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        scanner.stop()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Idle
    }
}

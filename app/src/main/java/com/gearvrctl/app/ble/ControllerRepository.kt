package com.gearvrctl.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    private var lastPacketArrivalMs = 0L
    private val timingGaps = ArrayList<Long>()

    private var gatt: BluetoothGatt? = null

    // Confirmed on real hardware: Android silently drops the connection back to a slow ~90ms
    // interval within ~1-2s of connecting, overriding a one-time requestConnectionPriority(HIGH)
    // call — a known flaky Android/OEM Bluetooth-stack behavior, not a peripheral limit (the
    // interval WAS fast right after connecting). Re-asserting periodically counteracts it.
    private val priorityHandler = Handler(Looper.getMainLooper())
    private val reassertPriorityRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            priorityHandler.postDelayed(this, PRIORITY_REASSERT_INTERVAL_MS)
        }
    }

    // The controller cleanly disconnects (status=0, not an error) after roughly 10-15s of
    // streaming — confirmed on real hardware, and not something our own code triggers (we never
    // call disconnect() ourselves in that flow). Sending the documented KEEP_ALIVE command
    // periodically to see if that's what's expected to keep the streaming session alive.
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val g = gatt
            val characteristic = g?.getService(GattUuids.SERVICE)?.getCharacteristic(GattUuids.WRITE_CHARACTERISTIC)
            if (g != null && characteristic != null) {
                writeCharacteristic(g, characteristic, GattUuids.KEEP_ALIVE)
            }
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

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
            onConnected = {
                _connectionState.value = ConnectionState.Connected
                priorityHandler.removeCallbacks(reassertPriorityRunnable)
                priorityHandler.post(reassertPriorityRunnable)
                keepAliveHandler.removeCallbacks(keepAliveRunnable)
                keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)
            },
            onDisconnected = { status ->
                priorityHandler.removeCallbacks(reassertPriorityRunnable)
                keepAliveHandler.removeCallbacks(keepAliveRunnable)
                _connectionState.value = ConnectionState.Disconnected("status=$status")
            },
            onNotificationsReady = { enableSensorStreaming() },
            onRawPacket = { bytes ->
                if (debugCaptureEnabled) {
                    captureCount += 1
                    captureBuffer.append(RawPacketLogger.summaryLine(captureCount, bytes)).append('\n')

                    // Accumulate in memory and flush in one batched Log.d call every
                    // TIMING_FLUSH_SIZE samples — logging every single packet (~65Hz) has real
                    // Binder/logd overhead of its own and risks measuring the probe, not the BLE
                    // arrival pattern it's trying to observe.
                    val now = SystemClock.elapsedRealtime()
                    if (lastPacketArrivalMs != 0L) {
                        timingGaps.add(now - lastPacketArrivalMs)
                        if (timingGaps.size >= TIMING_FLUSH_SIZE) {
                            Log.d("GearVrTiming", timingGaps.joinToString(","))
                            timingGaps.clear()
                        }
                    }
                    lastPacketArrivalMs = now
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

        // Matches the init sequence of a known-working reference implementation
        // (mijuu/GearVR-Controller-Bridge): disable Low Power Mode first, wait, then enable
        // sensor mode, wait. We'd never sent LPM_DISABLE before — likely explains both the
        // ~12Hz bursty data and the clean (status=0) disconnect after ~10-15s seen on real
        // hardware, both textbook power-saving behavior.
        //
        // Tried ENABLE_VR_MODE for a steadier/higher rate — confirmed via real crash log that it
        // emits at least one non-60-byte packet (a 2-byte one seen so far), a format we haven't
        // reverse-engineered. Staying on sensor mode, whose 60-byte layout is fully verified.
        writeCharacteristic(g, characteristic, GattUuids.LPM_DISABLE)
        Handler(Looper.getMainLooper()).postDelayed({
            writeCharacteristic(g, characteristic, GattUuids.ENABLE_SENSOR_MODE)
        }, INIT_STEP_DELAY_MS)
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
        priorityHandler.removeCallbacks(reassertPriorityRunnable)
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        scanner.stop()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Idle
    }

    companion object {
        private const val TIMING_FLUSH_SIZE = 50
        // Confirmed on real hardware: Android grants CONNECTION_PRIORITY_HIGH only as a short
        // boost (~500-800ms) then silently reverts to the slow interval, in lockstep with a 1s
        // reassert loop (interval oscillated 12<->72 units, tracking each request). Reasserting
        // faster than that revert window keeps it pinned in the fast state almost continuously.
        private const val PRIORITY_REASSERT_INTERVAL_MS = 300L
        private const val KEEP_ALIVE_INTERVAL_MS = 5000L
        private const val INIT_STEP_DELAY_MS = 100L
    }
}

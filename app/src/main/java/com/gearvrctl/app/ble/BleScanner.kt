package com.gearvrctl.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

/**
 * Scans for a Gear VR Controller and stops at first name match.
 *
 * Deliberately scans with NO ScanFilter: the controller's advertisement packet may not carry
 * the custom GATT service UUID (that UUID might only be visible after connecting and doing
 * service discovery) — filtering on it upfront can silently return zero results forever.
 * Matching by advertised device name is more robust, at the cost of seeing all nearby BLE
 * devices in [onAnySeen].
 */
class BleScanner(private val scanner: BluetoothLeScanner) {

    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(
        onFound: (BluetoothDevice) -> Unit,
        onAnySeen: (name: String?, address: String, rssi: Int) -> Unit,
        onError: (Int) -> Unit,
    ) {
        stop()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                onAnySeen(name, result.device.address, result.rssi)
                if (name != null && name.contains("Gear VR", ignoreCase = true)) {
                    stop()
                    onFound(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onError(errorCode)
            }
        }
        callback = cb
        scanner.startScan(emptyList(), settings, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { scanner.stopScan(it) }
        callback = null
    }
}

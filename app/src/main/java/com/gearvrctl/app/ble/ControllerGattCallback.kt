package com.gearvrctl.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log

private const val TAG = "GearVrGatt"

class ControllerGattCallback(
    private val onConnected: () -> Unit,
    private val onDisconnected: (status: Int) -> Unit,
    private val onNotificationsReady: () -> Unit,
    private val onRawPacket: (ByteArray) -> Unit,
) : BluetoothGattCallback() {

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected, discovering services")
                onConnected()
                gatt.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected, status=$status")
                onDisconnected(status)
                gatt.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        val service = gatt.getService(GattUuids.SERVICE)
        if (service == null) {
            Log.w(TAG, "Custom service not found on device")
            return
        }
        val notifyChar = service.getCharacteristic(GattUuids.NOTIFY_CHARACTERISTIC)
        if (notifyChar == null) {
            Log.w(TAG, "Notify characteristic not found")
            return
        }

        gatt.setCharacteristicNotification(notifyChar, true)
        val descriptor = notifyChar.getDescriptor(GattUuids.CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            Log.w(TAG, "CCC descriptor missing on notify characteristic")
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.uuid == GattUuids.CLIENT_CHARACTERISTIC_CONFIG) {
            Log.i(TAG, "Notifications enabled, starting sensor stream")
            onNotificationsReady()
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == GattUuids.NOTIFY_CHARACTERISTIC) {
            onRawPacket(value)
        }
    }

    @Deprecated("Pre-API33 callback path", ReplaceWith(""))
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        // Pre-API33 callback path; API33+ devices use the overload above.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU &&
            characteristic.uuid == GattUuids.NOTIFY_CHARACTERISTIC
        ) {
            characteristic.value?.let(onRawPacket)
        }
    }
}

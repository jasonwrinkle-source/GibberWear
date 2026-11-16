package app.gibberwear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GibberLink BLE GATT Server & Advertiser.
 *
 * Contract UUIDs matching Sexcapades PWA:
 *   Service UUID: 0000FFF0-0000-1000-8000-00805F9B34FB
 *   Write UUID (phone -> watch): 0000FFF1-0000-1000-8000-00805F9B34FB
 *   Notify UUID (watch -> phone): 0000FFF2-0000-1000-8000-00805F9B34FB
 */
@SuppressLint("MissingPermission")
class BleServer(
    private val context: Context,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onMessageReceived: (String) -> Unit
) {

    companion object {
        private const val TAG = "GibberLinkBLE"

        val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "GibberLink BLE advertising started successfully!")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "GibberLink BLE advertising failed with error code: $errorCode")
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE device connected: ${device.address}")
                connectedDevices.add(device)
                mainHandler.post { onConnectionChange(true) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE device disconnected: ${device.address}")
                connectedDevices.remove(device)
                mainHandler.post { onConnectionChange(connectedDevices.isNotEmpty()) }
                startAdvertising()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            val payload = value?.toString(Charsets.UTF_8)
                ?.replace("\u0000", "")
                ?.trim()
                ?: return
            if (payload.isEmpty()) return
            Log.i(TAG, "Received BLE write payload: $payload")
            mainHandler.post { onMessageReceived(payload) }
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                descriptor.value = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    fun start() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled or not supported on this device")
            return
        }

        try {
            bluetoothAdapter.name = "GibberLink"
        } catch (e: Exception) {
            Log.w(TAG, "Could not set Bluetooth device name: ${e.message}")
        }

        setupGattServer()
        startAdvertising()
    }

    private fun setupGattServer() {
        if (gattServer != null) return
        val manager = bluetoothManager ?: return

        gattServer = manager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open BLE GATT Server")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val writeChar = BluetoothGattCharacteristic(
            WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyChar = BluetoothGattCharacteristic(
            NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyChar.addDescriptor(cccd)

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)

        notifyCharacteristic = notifyChar
        gattServer?.addService(service)
        Log.i(TAG, "GATT Server service added successfully with UUID $SERVICE_UUID")
    }

    private fun startAdvertising() {
        if (isAdvertising) return
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE advertising: ${e.message}")
        }
    }

    /**
     * Send a notification message over BLE to all connected phone clients.
     * Full payload format: <key>:<MESSAGE> e.g. "adam:TAP", "adam:E40".
     */
    @Suppress("DEPRECATION")
    fun sendNotification(fullPayload: String) {
        val bytes = fullPayload.toByteArray(Charsets.UTF_8)
        val char = notifyCharacteristic ?: return

        char.value = bytes

        for (device in connectedDevices) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServer?.notifyCharacteristicChanged(device, char, false, bytes)
                } else {
                    @Suppress("DEPRECATION")
                    gattServer?.notifyCharacteristicChanged(device, char, false)
                }
                Log.d(TAG, "Sent BLE notification to ${device.address}: $fullPayload")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send BLE notification to ${device.address}: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
            gattServer?.close()
            gattServer = null
            connectedDevices.clear()
            Log.i(TAG, "BLE Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE Server: ${e.message}")
        }
    }
}

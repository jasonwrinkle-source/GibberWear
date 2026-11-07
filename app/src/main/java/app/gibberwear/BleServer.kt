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
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GibberLink BLE GATT Server & Advertiser (GibberWear v2).
 *
 * Advertises a generic custom BLE service ("GibberLink") so the phone-side
 * Web App (Scene Sentinel PWA) can connect and communicate bi-directionally
 * over Web Bluetooth.
 *
 * Contract UUIDs (fixed with PWA byte-for-byte):
 *   Service UUID: 7a1e5b90-3c4d-4e2f-8a6b-5d9c0e1f2a3b
 *   Write UUID (phone -> watch): 7a1e5b91-3c4d-4e2f-8a6b-5d9c0e1f2a3b
 *   Notify UUID (watch -> phone): 7a1e5b92-3c4d-4e2f-8a6b-5d9c0e1f2a3b
 *
 * Messages (same short IDs as chirps, prefixed with profile ID):
 *   Watch -> Phone (notify): <ID>:TAP, <ID>:DBL, <ID>:HLD, <ID>:E40, <ID>:H-20, etc.
 *   Phone -> Watch (write):
 *     - CHK / CHK_PULSE: single pulse check-in reminder (vibrate once + display ✓ ♥)
 *     - CHK_TIMER: timer check-in reminder (vibrate twice + display ✓ ⏱)
 *     - ID:<new_id>: updates stored profile ID.
 */
@SuppressLint("MissingPermission")
class BleServer(
    private val context: Context,
    private val onCheckInPulse: () -> Unit,
    private val onCheckInTimer: () -> Unit,
    private val onProfileIdUpdated: (String) -> Unit
) {

    companion object {
        private const val TAG = "GibberLinkBLE"

        val SERVICE_UUID: UUID = UUID.fromString("7a1e5b90-3c4d-4e2f-8a6b-5d9c0e1f2a3b")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("7a1e5b91-3c4d-4e2f-8a6b-5d9c0e1f2a3b")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("7a1e5b92-3c4d-4e2f-8a6b-5d9c0e1f2a3b")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val PREFS_NAME = "gibberwear_prefs"
        const val KEY_PROFILE_ID = "profile_id"
        const val DEFAULT_PROFILE_ID = "A1"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var profileId: String = prefs.getString(KEY_PROFILE_ID, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
        private set

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
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE device disconnected: ${device.address}")
                connectedDevices.remove(device)
                // Auto-re-advertise if link drops
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

            val payload = value?.toString(Charsets.UTF_8)?.trim() ?: return
            Log.i(TAG, "Received BLE write payload: $payload")

            when {
                payload == "CHK" || payload == "CHK_PULSE" -> onCheckInPulse()
                payload == "CHK_TIMER" -> onCheckInTimer()
                payload.startsWith("ID:") -> {
                    val newId = payload.substringAfter("ID:").trim()
                    if (newId.isNotEmpty()) {
                        profileId = newId
                        prefs.edit().putString(KEY_PROFILE_ID, newId).apply()
                        onProfileIdUpdated(newId)
                        Log.i(TAG, "Updated GibberWear profile ID to: $newId")
                    }
                }
            }
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

        // Set device name to generic name
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
        Log.i(TAG, "GATT Server service added successfully")
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

        // Service UUID in primary advertising packet (fits in 31-byte limit)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Device Name in scan response packet
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
     * Payload format: <ID>:<MESSAGE> e.g. "A1:TAP", "A1:E40", "A1:H-20".
     */
    @Suppress("DEPRECATION")
    fun sendNotification(messagePayload: String) {
        val fullMessage = "$profileId:$messagePayload"
        val bytes = fullMessage.toByteArray(Charsets.UTF_8)
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
                Log.d(TAG, "Sent BLE notification to ${device.address}: $fullMessage")
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
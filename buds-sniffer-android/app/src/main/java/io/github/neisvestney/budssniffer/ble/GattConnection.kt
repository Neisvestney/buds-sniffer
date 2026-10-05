package io.github.neisvestney.budssniffer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

data class GattNotification(val uuid: UUID, val value: ByteArray)

// Callers must hold BLUETOOTH_CONNECT.
@SuppressLint("MissingPermission")
class GattConnection(private val context: Context, private val device: BluetoothDevice) {
    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    // Closed on disconnect, so consumers can simply iterate until the link is gone.
    private val _notifications = Channel<GattNotification>(Channel.UNLIMITED)
    val notifications: ReceiveChannel<GattNotification> = _notifications

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connected.value = true
                connectWaiter?.complete(Unit)
            } else {
                _connected.value = false
                val error = GattException("disconnected, status=$status")
                connectWaiter?.completeExceptionally(error)
                pending?.completeExceptionally(error)
                _notifications.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.trySend(GattNotification(c.uuid, value))
        }
    }

    suspend fun connect(timeoutMs: Long = 15_000) {
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(timeoutMs) { waiter.await() }
    }

    suspend fun discoverServices(): List<BluetoothGattService> {
        val g = requireGatt()
        val status = op { g.discoverServices() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("discoverServices status=$status")
        return g.services
    }

    suspend fun requestMtu(mtu: Int): Int = op { requireGatt().requestMtu(mtu) }

    suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        val g = requireGatt()
        if (!g.setCharacteristicNotification(c, true)) throw GattException("setCharacteristicNotification failed")
        val cccd = c.getDescriptor(CCCD) ?: throw GattException("no CCCD on ${c.uuid}")
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val status = op { g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("CCCD write status=$status")
    }

    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray) {
        val g = requireGatt()
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val status = op { g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("write status=$status")
    }

    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connected.value = false
        _notifications.close()
    }

    private suspend fun op(timeoutMs: Long = 10_000, start: () -> Boolean): Int = opMutex.withLock {
        val d = CompletableDeferred<Int>()
        pending = d
        try {
            if (!start()) throw GattException("GATT operation rejected (busy or not connected)")
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    private fun requireGatt() = gatt ?: throw GattException("not connected")

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

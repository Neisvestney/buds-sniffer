package io.github.neisvestney.budssniffer.rcsp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import io.github.neisvestney.budssniffer.ble.GattConnection
import io.github.neisvestney.budssniffer.ble.GattException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

// One RCSP session over JieLi's AE00 GATT service: open → authenticate → packets.
class RcspLink(
    context: Context,
    device: BluetoothDevice,
    private val log: (String) -> Unit = {},
) {
    private val gatt = GattConnection(context, device)
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var sn = 0
    private val parser = RcspFrameParser()
    @Volatile private var authInbox: Channel<ByteArray>? = null
    private var readerJob: Job? = null

    // Closed when the GATT link goes down.
    private val _packets = Channel<RcspPacket>(Channel.UNLIMITED)
    val packets: ReceiveChannel<RcspPacket> = _packets

    val connected: StateFlow<Boolean> get() = gatt.connected

    suspend fun open(scope: CoroutineScope) {
        gatt.connect()
        log("connected")

        val services = gatt.discoverServices()
        for (s in services) {
            log("service ${s.uuid.short()}")
            for (ch in s.characteristics) log("  char ${ch.uuid.short()} ${props(ch.properties)}")
        }

        mtu = gatt.requestMtu(512).takeIf { it > 0 } ?: 23
        log("mtu = $mtu")

        val ae00 = services.firstOrNull { it.uuid == AE00 } ?: throw GattException("AE00 service not found")
        writeChar = ae00.getCharacteristic(AE01) ?: ae00.characteristics.firstOrNull { it.isWritable() }
            ?: throw GattException("no writable characteristic in AE00")

        readerJob = scope.launch { readLoop() }
        for (ch in ae00.characteristics.filter { it.isNotifiable() }) {
            gatt.enableNotifications(ch)
            log("notifications on ${ch.uuid.short()}")
        }
    }

    suspend fun authenticate() {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        authInbox = inbox
        try {
            RcspAuth.handshake(
                send = ::send,
                receive = { withTimeout(5_000) { inbox.receive() } },
                log = log,
            )
        } finally {
            authInbox = null
        }
    }

    suspend fun send(data: ByteArray) {
        val ch = writeChar ?: throw GattException("not opened")
        log("tx ${data.toHex()}")
        for (chunk in data.asList().chunked(mtu - 3)) gatt.write(ch, chunk.toByteArray())
    }

    fun nextSn() = sn.also { sn = (sn + 1) and 0xFF }

    fun close() {
        readerJob?.cancel()
        gatt.close()
        _packets.close()
    }

    private suspend fun readLoop() {
        for (n in gatt.notifications) {
            log("rx ${n.uuid.short()}: ${n.value.toHex()}")
            val inbox = authInbox
            if (inbox != null) {
                inbox.send(n.value)
                continue
            }
            for (p in parser.feed(n.value)) {
                if (p.isCommand && p.needsResponse) {
                    runCatching { send(RcspFrame.response(p.opcode, p.sn)) }
                        .onFailure { log("reply failed: ${it.message}") }
                }
                _packets.send(p)
            }
        }
        _packets.close()
    }

    companion object {
        val AE00: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        val AE01: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")

        private fun UUID.short(): String {
            val s = toString()
            return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).uppercase() else s
        }

        private fun BluetoothGattCharacteristic.isWritable() = properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        private fun BluetoothGattCharacteristic.isNotifiable() = properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        private fun props(p: Int) = buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }.joinToString(",", "[", "]")
    }
}

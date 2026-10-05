package io.github.neisvestney.budssniffer.rcsp

import java.io.ByteArrayOutputStream

// FE DC BA | flags | opcode | len (2, BE) | [status] sn | params | EF
// flags: 0x80 = command (else response), 0x40 = response expected
data class RcspPacket(
    val flags: Int,
    val opcode: Int,
    val status: Int?,
    val sn: Int,
    val params: ByteArray,
) {
    val isCommand get() = flags and FLAG_COMMAND != 0
    val needsResponse get() = flags and FLAG_NEED_RESPONSE != 0

    companion object {
        const val FLAG_COMMAND = 0x80
        const val FLAG_NEED_RESPONSE = 0x40
    }
}

object RcspFrame {
    private val HEADER = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
    private const val END = 0xEF.toByte()

    const val CMD_GET_TARGET_INFO = 0x02
    const val ATTR_ALL = -1

    fun command(opcode: Int, sn: Int, params: ByteArray, needResponse: Boolean = true): ByteArray {
        val flags = RcspPacket.FLAG_COMMAND or (if (needResponse) RcspPacket.FLAG_NEED_RESPONSE else 0)
        val len = 1 + params.size
        return ByteArrayOutputStream().apply {
            write(HEADER)
            write(flags)
            write(opcode)
            write(len ushr 8)
            write(len)
            write(sn)
            write(params)
            write(END.toInt())
        }.toByteArray()
    }

    fun response(opcode: Int, sn: Int, status: Int = 0, params: ByteArray = ByteArray(0)): ByteArray {
        val len = 2 + params.size
        return ByteArrayOutputStream().apply {
            write(HEADER)
            write(0)
            write(opcode)
            write(len ushr 8)
            write(len)
            write(status)
            write(sn)
            write(params)
            write(END.toInt())
        }.toByteArray()
    }

    fun getTargetInfo(sn: Int, mask: Int = ATTR_ALL) = command(
        CMD_GET_TARGET_INFO,
        sn,
        byteArrayOf((mask ushr 24).toByte(), (mask ushr 16).toByte(), (mask ushr 8).toByte(), mask.toByte()),
    )
}

// BLE notifications may split or glue frames, so bytes are buffered until a full frame is available.
class RcspFrameParser {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<RcspPacket> {
        buf += chunk
        val out = mutableListOf<RcspPacket>()
        while (true) {
            val start = indexOfHeader()
            if (start < 0) {
                buf = buf.takeLast(2).toByteArray()
                return out
            }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 7) return out

            val len = ((buf[5].toInt() and 0xFF) shl 8) or (buf[6].toInt() and 0xFF)
            val total = 7 + len + 1
            if (len <= MAX_PAYLOAD && buf.size < total) return out
            if (len > MAX_PAYLOAD || buf[total - 1] != 0xEF.toByte()) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            decode(buf.copyOfRange(0, total))?.let(out::add)
            buf = buf.copyOfRange(total, buf.size)
        }
    }

    private companion object {
        const val MAX_PAYLOAD = 2048
    }

    private fun indexOfHeader(): Int {
        for (i in 0..buf.size - 3) {
            if (buf[i] == 0xFE.toByte() && buf[i + 1] == 0xDC.toByte() && buf[i + 2] == 0xBA.toByte()) return i
        }
        return -1
    }

    private fun decode(frame: ByteArray): RcspPacket? {
        val flags = frame[3].toInt() and 0xFF
        val opcode = frame[4].toInt() and 0xFF
        val body = frame.copyOfRange(7, frame.size - 1)
        val isCommand = flags and RcspPacket.FLAG_COMMAND != 0
        return if (isCommand) {
            if (body.isEmpty()) return null
            RcspPacket(flags, opcode, null, body[0].toInt() and 0xFF, body.copyOfRange(1, body.size))
        } else {
            if (body.size < 2) return null
            RcspPacket(flags, opcode, body[0].toInt() and 0xFF, body[1].toInt() and 0xFF, body.copyOfRange(2, body.size))
        }
    }
}

data class RcspAttr(val type: Int, val value: ByteArray)

data class MultiBattery(val left: Int?, val right: Int?, val case: Int?)

object RcspAttrs {
    const val ATTR_TYPE_DEVICE_BATTERY = 0
    const val ATTR_TYPE_BATTERY = 2
    const val ATTR_TYPE_MULT_BATTERY = 7

    // [len][type][value × (len-1)]; returns null if the bytes don't form a clean TLV list
    fun parse(params: ByteArray): List<RcspAttr>? {
        val out = mutableListOf<RcspAttr>()
        var i = 0
        while (i < params.size) {
            val len = params[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > params.size) return null
            out += RcspAttr(params[i + 1].toInt() and 0xFF, params.copyOfRange(i + 2, i + 1 + len))
            i += 1 + len
        }
        return out
    }

    // [L, R, Box]; 255 = no data, bit 7 = charging (masked out here)
    fun multiBattery(value: ByteArray): MultiBattery {
        fun at(i: Int) = value.getOrNull(i)?.toInt()?.and(0xFF)?.takeIf { it != 0xFF }?.and(0x7F)
        return MultiBattery(at(0), at(1), at(2))
    }
}

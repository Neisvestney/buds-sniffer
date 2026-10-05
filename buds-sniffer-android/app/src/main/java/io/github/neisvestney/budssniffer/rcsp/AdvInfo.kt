package io.github.neisvestney.budssniffer.rcsp

import io.github.neisvestney.budssniffer.buds.BudLevel
import io.github.neisvestney.budssniffer.buds.BudsBattery

// JieLi NotifyAdvInfo push (opcode 0xC2), layout observed on Redmi Buds 4:
// vid(2) uid(2) pid(2) [6]? edrMac(6) [13]action L R case [17]?
object AdvInfo {
    const val CMD_NOTIFY_ADV_INFO = 0xC2
    const val CMD_DEVICE_REQUEST_OP = 0xC4

    private const val MIN_SIZE = 17

    fun parse(params: ByteArray, now: Long = System.currentTimeMillis()): BudsBattery? {
        if (params.size < MIN_SIZE) return null
        return BudsBattery(level(params[14]), level(params[15]), level(params[16]), now)
    }

    fun edrMac(params: ByteArray): String? =
        if (params.size < MIN_SIZE) null else params.copyOfRange(7, 13).joinToString(":") { "%02X".format(it) }

    // 0x00 / 0xFF = no data; bit 7 = charging
    private fun level(b: Byte): BudLevel? {
        val v = b.toInt() and 0xFF
        if (v == 0x00 || v == 0xFF) return null
        return BudLevel((v and 0x7F).coerceAtMost(100), v and 0x80 != 0)
    }
}

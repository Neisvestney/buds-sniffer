package io.github.neisvestney.budssniffer.rcsp

import java.security.SecureRandom

class RcspAuthException(message: String) : Exception(message)

// Raw 6-step JieLi handshake over AE01/AE02 (no FE DC BA framing).
object RcspAuth {
    private const val STEP_CHALLENGE: Byte = 0x00
    private const val STEP_ENCRYPTED: Byte = 0x01
    val PASS = byteArrayOf(0x02, 'p'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 's'.code.toByte())

    suspend fun handshake(
        send: suspend (ByteArray) -> Unit,
        receive: suspend () -> ByteArray,
        log: (String) -> Unit,
    ) {
        val ourRandom = ByteArray(JlAuthCipher.BLOCK_SIZE).also { SecureRandom().nextBytes(it) }
        log("auth 1: tx random")
        send(byteArrayOf(STEP_CHALLENGE) + ourRandom)

        val resp2 = receive()
        if (resp2.size != 17 || resp2[0] != STEP_ENCRYPTED) throw RcspAuthException("auth 2: unexpected ${resp2.toHex()}")
        val expected = JlAuthCipher.encrypt(ourRandom)
        if (!resp2.copyOfRange(1, 17).contentEquals(expected)) {
            throw RcspAuthException("auth 2: cipher mismatch, expected ${expected.toHex()}")
        }
        log("auth 2: device cipher matches ours")

        send(PASS)
        log("auth 3: tx pass")

        val resp4 = receive()
        if (resp4.size != 17 || resp4[0] != STEP_CHALLENGE) throw RcspAuthException("auth 4: unexpected ${resp4.toHex()}")
        log("auth 4: rx device challenge")

        send(byteArrayOf(STEP_ENCRYPTED) + JlAuthCipher.encrypt(resp4.copyOfRange(1, 17)))
        log("auth 5: tx encrypted challenge")

        val resp6 = receive()
        if (!resp6.contentEquals(PASS)) throw RcspAuthException("auth 6: expected pass, got ${resp6.toHex()}")
        log("auth 6: handshake complete")
    }
}

package io.github.neisvestney.budssniffer.rcsp

import org.junit.Assert.assertEquals
import org.junit.Test

class JlAuthCipherTest {
    // KAT from hybridherbst/web-bluetooth-e87 jl_auth_v3.py (captured E87 auth exchange)
    @Test
    fun encrypt_matchesCapturedExchange() {
        val challenge = "B6 E0 80 EC AF F3 22 91 6D 88 FA D5 AA 34 C2 AC".hexToBytes()
        val expected = "1D 88 97 AC 46 04 D3 32 E8 17 5E 81 BB 29 25 24"
        assertEquals(expected, JlAuthCipher.encrypt(challenge).toHex())
    }
}

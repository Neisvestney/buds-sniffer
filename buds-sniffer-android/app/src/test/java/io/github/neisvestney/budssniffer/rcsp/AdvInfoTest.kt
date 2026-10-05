package io.github.neisvestney.budssniffer.rcsp

import io.github.neisvestney.budssniffer.buds.BudLevel
import io.github.neisvestney.budssniffer.buds.BudsBattery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvInfoTest {
    // Captured on Redmi Buds 4: right bud charging in the case, case on cable at 45%.
    private val captured = "05 D6 00 02 00 33 22 7C C9 5E 25 81 D8 02 64 E4 AD 7E".hexToBytes()

    @Test
    fun parse_levelsAndChargingFlags() {
        val b = AdvInfo.parse(captured, now = 1)!!
        assertEquals(BudLevel(100, charging = false), b.left)
        assertEquals(BudLevel(100, charging = true), b.right)
        assertEquals(BudLevel(45, charging = true), b.case)
        assertEquals("7C:C9:5E:25:81:D8", AdvInfo.edrMac(captured))
    }

    @Test
    fun parse_zeroMeansNoData() {
        val b = AdvInfo.parse("05 D6 00 02 00 33 22 7C C9 5E 25 81 D8 00 64 00 00 87".hexToBytes(), now = 1)!!
        assertNull(b.right)
        assertNull(b.case)
    }

    @Test
    fun parse_rejectsShortPayload() {
        assertNull(AdvInfo.parse("05 D6 00".hexToBytes()))
    }

    @Test
    fun merge_keepsLastKnownForMissing() {
        val old = BudsBattery(BudLevel(90, false), BudLevel(80, true), BudLevel(40, false), 1)
        val fresh = BudsBattery(BudLevel(85, false), null, null, 2)
        assertEquals(BudsBattery(BudLevel(85, false), BudLevel(80, true), BudLevel(40, false), 2), fresh.mergedOnto(old))
    }

    @Test
    fun response_frame() {
        assertEquals("FE DC BA 00 C4 00 02 00 8A EF", RcspFrame.response(0xC4, 0x8A).toHex())
    }
}

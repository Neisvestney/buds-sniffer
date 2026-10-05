package io.github.neisvestney.budssniffer.rcsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcspFrameTest {
    @Test
    fun getTargetInfo_allAttributes() {
        assertEquals("FE DC BA C0 02 00 05 00 FF FF FF FF EF", RcspFrame.getTargetInfo(sn = 0).toHex())
    }

    @Test
    fun parser_reassemblesSplitResponse() {
        val parser = RcspFrameParser()
        // response: status 0, sn 3, attr [len=4][type=7][L R Box]
        assertTrue(parser.feed("00 11 FE DC BA 00 02 00 07".hexToBytes()).isEmpty())
        val packets = parser.feed("00 03 04 07 E4 50 FF EF".hexToBytes())

        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x02, p.opcode)
        assertEquals(0, p.status)
        assertEquals(3, p.sn)

        val attrs = RcspAttrs.parse(p.params)!!
        assertEquals(RcspAttrs.ATTR_TYPE_MULT_BATTERY, attrs.single().type)
        assertEquals(MultiBattery(left = 100, right = 80, case = null), RcspAttrs.multiBattery(attrs.single().value))
    }

    @Test
    fun parser_skipsGarbageAndHandlesGluedFrames() {
        val frame = "FE DC BA 00 02 00 02 00 01 EF"
        val packets = RcspFrameParser().feed("AA BB $frame $frame".hexToBytes())
        assertEquals(listOf(1, 1), packets.map { it.sn })
    }

    @Test
    fun attrs_rejectsMalformedTlv() {
        assertNull(RcspAttrs.parse("05 07 01".hexToBytes()))
    }
}

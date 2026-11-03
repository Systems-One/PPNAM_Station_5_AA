package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackQrTest {
    @Test fun parsesThreeFields() {
        val s = "510019399,OUTER-001,BAG-20261102-0001"
        val q = PackQr.parse(s)!!
        assertEquals("510019399", q.productionOrder)
        assertEquals("OUTER-001", q.outerCode)
        assertEquals("BAG-20261102-0001", q.bagId)
        assertEquals(s, q.raw)
    }

    @Test fun qrIsTrimmed() {
        assertEquals("510019399,OUTER-001,BAG-1", PackQr.parse("  510019399,OUTER-001,BAG-1\r\n")!!.raw)
    }

    @Test fun rejectsTwoFields() = assertNull(PackQr.parse("OUTER-001,BAG-1"))
    @Test fun rejectsEmptyField() = assertNull(PackQr.parse("510019399,,BAG-1"))
    @Test fun rejectsFourFields() = assertNull(PackQr.parse("1,2,3,4"))
    @Test fun rejectsOver212() = assertNull(PackQr.parse("1," + "A".repeat(210) + ",B"))
    @Test fun rejectsControlChar() = assertNull(PackQr.parse("1,A,B\u0007"))
}

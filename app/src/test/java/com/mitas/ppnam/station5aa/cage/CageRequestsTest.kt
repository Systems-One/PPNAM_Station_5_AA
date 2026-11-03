package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CageRequestsTest {
    private val t = Instant.parse("2026-11-03T10:00:00.123456Z")
    private val r = CageRequests("scanner_ab12", { t }, { "m-1" })

    @Test fun selectEnvelopeAndTopic() {
        val p = r.select("s-1", "E200ABC")
        assertEquals("PPNAM/station_5/scanner_ab12/req/cage_select_requested", p.topic)
        assertEquals("cage_select_requested", p.requestType)
        assertEquals("cage_context_result", p.responseType)
        assertEquals(RequestIntent.SELECT, p.intent)
        assertEquals("s-1", p.operatorSessionId)
        assertEquals("m-1", p.messageId)
        val b = JSONObject(p.body)
        assertEquals("m-1", b.getString("messageId"))
        assertEquals("4.1", b.getString("schemaVersion"))
        assertEquals("scanner_ab12", b.getString("deviceId"))
        assertEquals("s-1", b.getString("operatorSessionId"))
        assertEquals("2026-11-03T10:00:00.123456Z", b.getString("timestampUtc"))
        assertEquals("E200ABC", b.getString("rfidTag"))
    }

    @Test fun readActiveHasNoLoadId() {
        val p = r.readActive("s-1")
        assertEquals("cage_context_requested", p.requestType)
        assertEquals("cage_context_result", p.responseType)
        assertEquals(RequestIntent.READ_ACTIVE, p.intent)
        assertFalse(JSONObject(p.body).has("loadId"))
    }

    @Test fun readLoadCarriesLoadIdAndBag() {
        val p = r.readLoad("s-1", "L1", RequestIntent.RECOVER_ALLOCATION, "BAG-1")
        assertEquals("L1", JSONObject(p.body).getString("loadId"))
        assertEquals("L1", p.loadId)
        assertEquals("BAG-1", p.bagId)
        assertEquals(RequestIntent.RECOVER_ALLOCATION, p.intent)
        assertFalse(JSONObject(p.body).has("bagId"))
    }

    @Test fun allocateSendsRawBarcodeOnly() {
        val qr = PackQr.parse("510019399,OUTER-001,BAG-1")!!
        val p = r.allocate("s-1", "L1", qr)
        val b = JSONObject(p.body)
        assertEquals(qr.raw, b.getString("barcode"))
        assertEquals("L1", b.getString("loadId"))
        assertFalse(b.has("jobCard"))
        assertFalse(b.has("bagId"))
        assertEquals("finished_good_allocate_requested", p.requestType)
        assertEquals("finished_good_allocation_result", p.responseType)
        assertEquals(RequestIntent.ALLOCATE, p.intent)
        assertEquals(qr.bagId, p.bagId)
        assertEquals("L1", p.loadId)
    }

    @Test fun confirmCarriesToken() {
        val p = r.confirm("s-1", "L1", "tok")
        assertEquals("cage_complete_confirmed", p.requestType)
        assertEquals("cage_completion_result", p.responseType)
        assertEquals(RequestIntent.CONFIRM, p.intent)
        val b = JSONObject(p.body)
        assertEquals("tok", b.getString("confirmationToken"))
        assertEquals("L1", b.getString("loadId"))
    }

    @Test fun bodyIsUnder16k() {
        val qr = PackQr.parse("1," + "A".repeat(208) + ",B")!!
        assertEquals(212, qr.raw.length)
        assertTrue(r.allocate("s-1", "L1", qr).body.toByteArray(Charsets.UTF_8).size < 16384)
    }

    @Test fun firstSentAtIsClockMillis() {
        assertEquals(t.toEpochMilli(), r.readActive("s-1").firstSentAtMs)
    }
}

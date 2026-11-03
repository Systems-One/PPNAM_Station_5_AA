package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboxTest {
    private fun sample(loadId: String? = "L1", bagId: String? = "B1") = PendingRequest(
        requestType = "finished_good_allocate_requested",
        responseType = "finished_good_allocation_result",
        topic = "t/x",
        messageId = "m-1",
        body = "{\"b\":1,  \"a\":\"x\"}",
        operatorSessionId = "s-1",
        intent = RequestIntent.ALLOCATE,
        loadId = loadId,
        bagId = bagId,
        firstSentAtMs = 1234567890123L,
    )

    @Test fun roundTripPreservesBodyExactly() {
        val p = sample()
        val d = PendingRequestCodec.decode(PendingRequestCodec.encode(p))!!
        assertEquals("{\"b\":1,  \"a\":\"x\"}", d.body)
        assertEquals(p, d)
    }

    @Test fun nullableFieldsRoundTripAsNull() {
        val p = sample(loadId = null, bagId = null)
        val d = PendingRequestCodec.decode(PendingRequestCodec.encode(p))!!
        assertNull(d.loadId)
        assertNull(d.bagId)
        assertEquals(p, d)
    }

    @Test fun decodeGarbageIsNull() {
        assertNull(PendingRequestCodec.decode("not json"))
        assertNull(PendingRequestCodec.decode(null))
    }

    @Test fun unknownIntentIsNull() {
        val s = PendingRequestCodec.encode(sample()).replace("ALLOCATE", "BOGUS")
        assertNull(PendingRequestCodec.decode(s))
    }

    @Test fun memoryStoreSaveLoadClear() {
        val s = MemoryOutboxStore()
        assertNull(s.load())
        s.save(sample())
        assertEquals(sample(), s.load())
        s.clear()
        assertNull(s.load())
    }
}

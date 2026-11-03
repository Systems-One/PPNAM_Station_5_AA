package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseFilterTest {

    private val filter = ResponseFilter("scanner_ab12")

    private fun response(
        messageId: String = "r-1",
        inResponseTo: String = "m-1",
        deviceId: String = "scanner_ab12",
    ) = JSONObject().apply {
        put("messageId", messageId)
        put("inResponseToMessageId", inResponseTo)
        put("deviceId", deviceId)
        put("accepted", true)
    }

    @Test
    fun acceptsMatching() {
        assertTrue(filter.accept(response(), "m-1"))
    }

    @Test
    fun rejectsOtherDevice() {
        assertFalse(filter.accept(response(deviceId = "scanner_zz"), "m-1"))
    }

    @Test
    fun rejectsWrongCorrelation() {
        assertFalse(filter.accept(response(inResponseTo = "m-other"), "m-1"))
    }

    @Test
    fun rejectsRepeatResponseMessageId() {
        assertTrue(filter.accept(response(messageId = "r-7"), "m-1"))
        assertFalse(filter.accept(response(messageId = "r-7"), "m-1"))
    }

    @Test
    fun rejectsWhenNothingPending() {
        assertFalse(filter.accept(response(), null))
    }

    @Test
    fun forgetsOldestBeyondCapacity() {
        val small = ResponseFilter("scanner_ab12", capacity = 2)
        assertTrue(small.accept(response(messageId = "a"), "m-1"))
        assertTrue(small.accept(response(messageId = "b"), "m-1"))
        assertTrue(small.accept(response(messageId = "c"), "m-1"))
        assertTrue(small.accept(response(messageId = "a"), "m-1")) // evicted, so seen as new
        assertFalse(small.accept(response(messageId = "c"), "m-1"))
    }
}

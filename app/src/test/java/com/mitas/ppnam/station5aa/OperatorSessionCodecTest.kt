package com.mitas.ppnam.station5aa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Login proof result parsing per MQTT_CONTRACT §3. */
class OperatorSessionCodecTest {

    @Test
    fun readsOperatorNameAndTabs() {
        val json = JSONObject("""{"operatorSessionId":"s-1","operatorName":"Thabo M","role":"Operator","allowedTabs":["cage_loading"]}""")
        val s = OperatorSessionCodec.fromProofResult(json).getOrThrow()
        assertEquals("Thabo M", s.operatorName)
        assertTrue(s.canShow(StationTab.CAGE_LOADING))
    }

    @Test
    fun fallsBackToDisplayName() {
        val s = OperatorSessionCodec.fromProofResult(JSONObject("""{"operatorSessionId":"s","displayName":"X"}""")).getOrThrow()
        assertEquals("X", s.operatorName)
    }

    @Test
    fun blankSessionFails() {
        assertTrue(OperatorSessionCodec.fromProofResult(JSONObject("""{"operatorName":"X"}""")).isFailure)
    }

    @Test
    fun closedSessionFails() {
        assertTrue(OperatorSessionCodec.fromProofResult(JSONObject("""{"operatorSessionId":"s","operatorName":"X","sessionState":"closed"}""")).isFailure)
    }
}

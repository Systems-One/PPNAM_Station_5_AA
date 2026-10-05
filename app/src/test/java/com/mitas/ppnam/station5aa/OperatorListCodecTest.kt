package com.mitas.ppnam.station5aa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v3.2.0 §4.5: display-only operator directory for the login dropdown. */
class OperatorListCodecTest {

    private val response = JSONObject(
        """{"accepted":true,"operators":[
             {"username":"op.tag","displayName":"Thandi Tag"},
             {"username":"op.both","displayName":"Bongi Both","role":"Admin"},
             {"username":"","displayName":"Nobody"},
             {"username":"op.nodisplay"}
           ]}"""
    )

    @Test
    fun `parses username and displayName, drops blank usernames, defaults displayName`() {
        val list = OperatorListCodec.fromResponse(response)
        assertEquals(
            listOf(
                OperatorEntry("op.tag", "Thandi Tag"),
                OperatorEntry("op.both", "Bongi Both"),
                OperatorEntry("op.nodisplay", "op.nodisplay"),
            ),
            list,
        )
    }

    @Test
    fun `a response without operators yields an empty list`() {
        assertTrue(OperatorListCodec.fromResponse(JSONObject("""{"accepted":true}""")).isEmpty())
    }

    @Test
    fun `encode and decode round-trip`() {
        val list = listOf(OperatorEntry("a", "A Person"), OperatorEntry("b", "B"))
        assertEquals(list, OperatorListCodec.decode(OperatorListCodec.encode(list)))
    }

    @Test
    fun `decode tolerates null and garbage`() {
        assertTrue(OperatorListCodec.decode(null).isEmpty())
        assertTrue(OperatorListCodec.decode("not json").isEmpty())
        assertTrue(OperatorListCodec.decode("{}").isEmpty())
    }
}

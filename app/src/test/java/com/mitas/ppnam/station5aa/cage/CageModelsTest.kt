package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CageModelsTest {
    @Test fun parsesLoad() {
        val l = CageJson.load(JSONObject("""{"loadId":"L1","cageId":"CAGE-007","status":"Open","packCount":14,"totalKg":352.4,"completedAtUtc":null,"grvState":"Not queued","grvReference":null,"printState":"Not queued","error":null}"""))!!
        assertEquals(14, l.packCount)
        assertEquals(0, l.totalKg.compareTo(BigDecimal("352.4")))
        assertTrue(l.isOpen)
        assertNull(l.grvReference)
        assertNull(l.completedAtUtc)
        assertNull(l.error)
    }

    @Test fun nullLoadIsNull() {
        assertNull(CageJson.load(JSONObject("""{"load":null}""").optJSONObject("load")))
        assertNull(CageJson.load(null))
        assertNull(CageJson.load(JSONObject.NULL as? JSONObject))
    }

    @Test fun allocatedPackMapsActualKgAndOrder() {
        val p = CageJson.allocatedPack(JSONObject("""{"bagId":"B1","outerCode":"O","productionOrderNumber":"510019399","itemCode":"FG","itemName":"N","actualKg":25.18,"disposition":"Pass"}"""))!!
        assertEquals("510019399", p.jobCard)
        assertEquals(0, p.weightKg.compareTo(BigDecimal("25.18")))
    }

    @Test fun packsSnapshotMapsWeightAndNullJobCard() {
        val l = CageJson.packs(org.json.JSONArray("""[{"bagId":"B1","outerCode":"O","itemCode":"FG","itemName":"N","jobCard":null,"weightKg":10.5,"disposition":"Pass"}]"""))
        assertEquals(1, l.size)
        assertNull(l[0].jobCard)
        assertEquals(0, l[0].weightKg.compareTo(BigDecimal("10.5")))
        assertTrue(CageJson.packs(null).isEmpty())
    }
}

package com.mitas.ppnam.station5aa.cage

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class CageLoad(
    val loadId: String,
    val cageId: String,
    val status: String,
    val packCount: Int,
    val totalKg: BigDecimal,
    val completedAtUtc: String?,
    val grvState: String,
    val grvReference: String?,
    val printState: String,
    val error: String?,
) {
    val isOpen: Boolean get() = status == "Open"
}

data class LoadedPack(
    val bagId: String,
    val outerCode: String,
    val itemCode: String,
    val itemName: String,
    val jobCard: String?,
    val weightKg: BigDecimal,
    val disposition: String,
)

/** Tolerant readers for the load / pack JSON; JSON null and missing both map to null. */
object CageJson {
    fun load(o: JSONObject?): CageLoad? {
        if (o == null || o === JSONObject.NULL) return null
        return CageLoad(
            loadId = o.optString("loadId"),
            cageId = o.optString("cageId"),
            status = o.optString("status"),
            packCount = o.optInt("packCount"),
            totalKg = number(o, "totalKg"),
            completedAtUtc = nullableString(o, "completedAtUtc"),
            grvState = o.optString("grvState"),
            grvReference = nullableString(o, "grvReference"),
            printState = o.optString("printState"),
            error = nullableString(o, "error"),
        )
    }

    fun packs(a: JSONArray?): List<LoadedPack> {
        if (a == null) return emptyList()
        val out = ArrayList<LoadedPack>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += pack(o, "weightKg", "jobCard")
        }
        return out
    }

    fun allocatedPack(o: JSONObject?): LoadedPack? {
        if (o == null || o === JSONObject.NULL) return null
        return pack(o, "actualKg", "productionOrderNumber")
    }

    private fun pack(o: JSONObject, weightField: String, jobCardField: String) = LoadedPack(
        bagId = o.optString("bagId"),
        outerCode = o.optString("outerCode"),
        itemCode = o.optString("itemCode"),
        itemName = o.optString("itemName"),
        jobCard = nullableString(o, jobCardField),
        weightKg = number(o, weightField),
        disposition = o.optString("disposition"),
    )

    private fun nullableString(o: JSONObject, name: String): String? =
        if (!o.has(name) || o.isNull(name)) null else o.optString(name)

    private fun number(o: JSONObject, name: String): BigDecimal =
        if (!o.has(name) || o.isNull(name)) {
            BigDecimal.ZERO
        } else {
            try { BigDecimal(o.get(name).toString()) } catch (_: NumberFormatException) { BigDecimal.ZERO }
        }
}

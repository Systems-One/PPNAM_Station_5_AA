package com.mitas.ppnam.station5aa.cage

enum class AfterRefusal { STAY, TO_IDLE, REFRESH_LOAD }

data class Refusal(val headline: String, val detail: String, val code: String, val after: AfterRefusal)

object RefusalCatalog {
    private const val NO_LOAD = "Do NOT load this Pack"
    private const val NOT_AVAILABLE = "Cage not available"
    private const val NOT_ACCEPTED = "Not accepted"

    val TRANSIENT: Set<String> = setOf("processing_paused", "confirmation_busy")

    private fun conflict(code: String) = Refusal(
        NOT_ACCEPTED,
        "This request clashed with an earlier one. Note the time and contact support.",
        code, AfterRefusal.STAY,
    )

    /** Null when the code is not in the allocation table. */
    private fun allocationOrNull(code: String, bagId: String?): Refusal? {
        fun r(detail: String) = Refusal(NO_LOAD, detail, code, AfterRefusal.STAY)
        return when (code) {
            "pack_allocated" -> r("${bagId ?: "This Pack"} is already in another cage. Put it aside and check it on the desktop.")
            "pack_not_found" -> r("This Pack hasn't been weighed. Take it back to the weigh station.")
            "qc_required" -> r("This Pack is waiting for QC on the desktop.")
            "invalid_barcode", "outer_code_changed" ->
                r("Label doesn't match the weighed Pack. Put it aside for checking.")
            "production_order_mismatch", "production_order_missing", "production_order_changed" ->
                r("Job card on this label doesn't match. Put it aside for checking.")
            "mixed_outer_code" -> r("This Pack's outer code doesn't match the cage. Use a matching cage.")
            "cage_locked" -> Refusal(NOT_AVAILABLE, "This cage is already completed.", code, AfterRefusal.TO_IDLE)
            "cage_not_active", "cage_context_changed" -> Refusal(
                NOT_AVAILABLE, "This cage is no longer active on this handheld. Rescan the cage tag.",
                code, AfterRefusal.TO_IDLE)
            else -> null
        }
    }

    private fun unknown(code: String, reason: String) = Refusal(NOT_ACCEPTED, reason, code, AfterRefusal.STAY)

    fun forAllocation(code: String, reason: String, bagId: String?): Refusal {
        if (code == "message_id_conflict") return conflict(code)
        return allocationOrNull(code, bagId) ?: unknown(code, reason)
    }

    fun forSelection(code: String, reason: String): Refusal = when (code) {
        "message_id_conflict" -> conflict(code)
        "unknown_cage" -> Refusal("Cage not recognised", "This cage tag isn't registered — ask a Manager.", code, AfterRefusal.TO_IDLE)
        "empty_cage", "completion_blocked" -> Refusal("Cannot complete", "Add Packs before completing.", code, AfterRefusal.STAY)
        else -> allocationOrNull(code, null) ?: unknown(code, reason)
    }

    fun forCompletion(code: String, reason: String): Refusal {
        val headline = "Cage not completed"
        return when (code) {
            "message_id_conflict" -> conflict(code)
            "confirmation_expired" -> Refusal(headline, "The summary expired. Rescan the cage tag to complete.", code, AfterRefusal.REFRESH_LOAD)
            "cage_contents_changed" -> Refusal(
                headline, "Another Pack was added to this cage. Check the new count and rescan the cage tag to complete.",
                code, AfterRefusal.REFRESH_LOAD)
            else -> Refusal(headline, reason, code, AfterRefusal.STAY)
        }
    }

    fun labelNotRecognised(): Refusal = Refusal(
        NO_LOAD, "Label not recognised. Scan the Pack's QR label again or put it aside for checking.",
        "local_invalid_qr", AfterRefusal.STAY)
}

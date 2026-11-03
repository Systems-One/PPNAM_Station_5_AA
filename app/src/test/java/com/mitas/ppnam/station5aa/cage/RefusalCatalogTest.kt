package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalCatalogTest {
    private val noLoad = "Do NOT load this Pack"

    private fun check(r: Refusal, headline: String, detail: String, code: String, after: AfterRefusal) {
        assertEquals(headline, r.headline)
        assertEquals(detail, r.detail)
        assertEquals(code, r.code)
        assertEquals(after, r.after)
    }

    @Test fun packAllocated() = check(
        RefusalCatalog.forAllocation("pack_allocated", "x", "B1"), noLoad,
        "B1 is already in another cage. Put it aside and check it on the desktop.", "pack_allocated", AfterRefusal.STAY)

    @Test fun packAllocatedNullBag() = assertEquals(
        "This Pack is already in another cage. Put it aside and check it on the desktop.",
        RefusalCatalog.forAllocation("pack_allocated", "x", null).detail)

    @Test fun packNotFound() = check(
        RefusalCatalog.forAllocation("pack_not_found", "x", null), noLoad,
        "This Pack hasn't been weighed. Take it back to the weigh station.", "pack_not_found", AfterRefusal.STAY)

    @Test fun qcRequired() = assertEquals("This Pack is waiting for QC on the desktop.",
        RefusalCatalog.forAllocation("qc_required", "x", null).detail)

    @Test fun labelMismatch() {
        for (c in listOf("invalid_barcode", "outer_code_changed")) {
            val r = RefusalCatalog.forAllocation(c, "x", null)
            check(r, noLoad, "Label doesn't match the weighed Pack. Put it aside for checking.", c, AfterRefusal.STAY)
        }
    }

    @Test fun productionOrder() {
        for (c in listOf("production_order_mismatch", "production_order_missing", "production_order_changed")) {
            assertEquals("Job card on this label doesn't match. Put it aside for checking.",
                RefusalCatalog.forAllocation(c, "x", null).detail)
        }
    }

    @Test fun mixedOuter() = assertEquals("This Pack's outer code doesn't match the cage. Use a matching cage.",
        RefusalCatalog.forAllocation("mixed_outer_code", "x", null).detail)

    @Test fun cageLocked() = check(
        RefusalCatalog.forAllocation("cage_locked", "x", null), "Cage not available",
        "This cage is already completed.", "cage_locked", AfterRefusal.TO_IDLE)

    @Test fun cageNotActive() {
        for (c in listOf("cage_not_active", "cage_context_changed")) {
            check(RefusalCatalog.forAllocation(c, "x", null), "Cage not available",
                "This cage is no longer active on this handheld. Rescan the cage tag.", c, AfterRefusal.TO_IDLE)
        }
    }

    @Test fun unknownCage() = check(
        RefusalCatalog.forSelection("unknown_cage", "x"), "Cage not recognised",
        "This cage tag isn't registered — ask a Manager.", "unknown_cage", AfterRefusal.TO_IDLE)

    @Test fun emptyCage() {
        for (c in listOf("empty_cage", "completion_blocked")) {
            check(RefusalCatalog.forSelection(c, "x"), "Cannot complete", "Add Packs before completing.", c, AfterRefusal.STAY)
        }
    }

    @Test fun selectionFallsBackToAllocationTable() = assertEquals(
        "This cage is already completed.", RefusalCatalog.forSelection("cage_locked", "x").detail)

    @Test fun confirmationExpired() = check(
        RefusalCatalog.forCompletion("confirmation_expired", "x"), "Cage not completed",
        "The summary expired. Rescan the cage tag to complete.", "confirmation_expired", AfterRefusal.REFRESH_LOAD)

    @Test fun contentsChanged() = check(
        RefusalCatalog.forCompletion("cage_contents_changed", "x"), "Cage not completed",
        "Another Pack was added to this cage. Check the new count and rescan the cage tag to complete.",
        "cage_contents_changed", AfterRefusal.REFRESH_LOAD)

    @Test fun unknownCode() {
        check(RefusalCatalog.forAllocation("weird", "Something", null), "Not accepted", "Something", "weird", AfterRefusal.STAY)
        assertEquals("Something", RefusalCatalog.forSelection("weird", "Something").detail)
        assertEquals("Something", RefusalCatalog.forCompletion("weird", "Something").detail)
    }

    @Test fun messageIdConflict() {
        listOf(
            RefusalCatalog.forAllocation("message_id_conflict", "", null),
            RefusalCatalog.forSelection("message_id_conflict", ""),
            RefusalCatalog.forCompletion("message_id_conflict", ""),
        ).forEach {
            assertTrue(it.detail.contains("contact support", ignoreCase = true))
            assertEquals(AfterRefusal.STAY, it.after)
        }
    }

    @Test fun labelNotRecognised() = check(
        RefusalCatalog.labelNotRecognised(), noLoad,
        "Label not recognised. Scan the Pack's QR label again or put it aside for checking.",
        "local_invalid_qr", AfterRefusal.STAY)

    @Test fun transient() = assertEquals(setOf("processing_paused", "confirmation_busy"), RefusalCatalog.TRANSIENT)
}

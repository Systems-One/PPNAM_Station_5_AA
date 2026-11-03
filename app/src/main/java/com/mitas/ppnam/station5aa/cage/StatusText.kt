package com.mitas.ppnam.station5aa.cage

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object StatusText {
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** A load's `completedAtUtc` (ISO-8601 with any offset) as local `HH:mm` in [zone]; null when missing or unparseable. */
    fun completedTime(completedAtUtc: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (completedAtUtc.isNullOrBlank()) return null
        // The desktop sends DateTimeOffset with its own offset (e.g. "+02:00"), not always "Z".
        val instant = try {
            OffsetDateTime.parse(completedAtUtc).toInstant()
        } catch (_: DateTimeParseException) {
            try { Instant.parse(completedAtUtc) } catch (_: DateTimeParseException) { return null }
        }
        return HH_MM.format(instant.atZone(zone))
    }

    fun grv(state: String): String = when (state) {
        "Not queued" -> "Not sent yet"
        "Pending" -> "Pending"
        "Processing" -> "Sending to SAP"
        "Succeeded" -> "Posted to SAP"
        "Failed" -> "Failed — check the desktop"
        "OutcomeUnknown" -> "Unconfirmed — check the desktop"
        "Reconcile" -> "Needs review on the desktop"
        else -> state
    }

    fun print(state: String): String = when (state) {
        "Not queued" -> "Not printed yet"
        "Processing" -> "Printing"
        "WaitingForGrv" -> "Waiting for receipt"
        "Succeeded" -> "Printed"
        "NeedsReview" -> "Needs review on the desktop"
        "Failed" -> "Failed — check the desktop"
        "Pending" -> "Pending"
        "OutcomeUnknown" -> "Unconfirmed — check the desktop"
        "Reconcile" -> "Needs review on the desktop"
        else -> state
    }
}

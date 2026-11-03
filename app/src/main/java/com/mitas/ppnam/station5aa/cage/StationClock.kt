package com.mitas.ppnam.station5aa.cage

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Countdowns measured on the station's own clock, so handheld clock skew cannot shorten or
 * lengthen them: remaining = expiry − the station's send time of the reply. The host anchors the
 * result at local receipt time (`nowMs() + remaining`).
 */
object StationClock {
    /** Milliseconds from [serverNowUtc] to [expiryUtc] (ISO-8601 instants), at least 0; null if either is missing or unparseable. */
    fun remainingMs(expiryUtc: String?, serverNowUtc: String?): Long? {
        if (expiryUtc == null || serverNowUtc == null) return null
        return try {
            val left = Instant.parse(expiryUtc).toEpochMilli() - Instant.parse(serverNowUtc).toEpochMilli()
            if (left < 0) 0 else left
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

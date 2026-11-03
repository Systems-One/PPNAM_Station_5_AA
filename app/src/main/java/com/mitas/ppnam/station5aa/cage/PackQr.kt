package com.mitas.ppnam.station5aa.cage

/** Station 5 Pack QR: `productionOrder,outerCode,bagId`, at most 212 characters. */
data class PackQr(val raw: String, val productionOrder: String, val outerCode: String, val bagId: String) {
    companion object {
        private const val MAX_LENGTH = 212

        fun parse(scanned: String): PackQr? {
            val raw = scanned.trim()
            if (raw.length > MAX_LENGTH || raw.any { it.isISOControl() }) return null
            val parts = raw.split(',')
            if (parts.size != 3 || parts.any { it.isBlank() }) return null
            return PackQr(raw, parts[0], parts[1], parts[2])
        }
    }
}

package com.mitas.ppnam.station5aa

/** Inactivity auto-logout setting rules (Station 1 spec section 3): whole minutes, 0 = never, max one day. */
object AutoLogout {
    const val DEFAULT_MINUTES = 15
    const val MAX_MINUTES = 1440

    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 0..MAX_MINUTES }

    fun timeoutMs(minutes: Int): Long = if (minutes <= 0) 0L else minutes * 60_000L
}

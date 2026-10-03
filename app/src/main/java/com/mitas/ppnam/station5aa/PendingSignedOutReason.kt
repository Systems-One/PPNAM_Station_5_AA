package com.mitas.ppnam.station5aa

/**
 * The reason for the most recent sign-out that LoginActivity has not shown yet.
 *
 * SessionGuard.signOut starts Login from the Application context, which Android 10+ blocks
 * while the app is in the background; the session is cleared regardless. This holder lets a
 * session-requiring screen that resumes later (see SessionActivity.onResume) hand the reason
 * to Login so the operator still learns why they were signed out.
 */
object PendingSignedOutReason {
    @Volatile
    private var reason: String? = null

    fun set(value: String) {
        reason = value.takeIf { it.isNotBlank() }
    }

    /** Returns the pending reason once, then forgets it. */
    @Synchronized
    fun take(): String? {
        val r = reason
        reason = null
        return r
    }
}

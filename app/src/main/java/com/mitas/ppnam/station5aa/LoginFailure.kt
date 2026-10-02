package com.mitas.ppnam.station5aa

/** What the operator can do about a failed login. */
enum class LoginFailureKind { WRONG_CREDENTIALS, TIMEOUT, NOT_CONNECTED, OTHER }

/**
 * Classifies a login failure so LoginActivity can show an operator-facing string instead of
 * protocol text (UI audit group (f): "SCRAM proof rejected." was reaching the screen).
 * OTHER is passed through: the contract says the station's free-text `reason` is already
 * sanitised for display, so an unrecognised reason is still better than a generic one.
 */
object LoginFailure {
    fun classify(rawMessage: String?): LoginFailureKind {
        val m = rawMessage.orEmpty().trim().lowercase()
        return when {
            m.isEmpty() -> LoginFailureKind.OTHER
            "scram" in m || "proof" in m || "password" in m || "credential" in m || "auth_failed" in m ->
                LoginFailureKind.WRONG_CREDENTIALS
            "did not respond" in m || "timed out" in m || "timeout" in m -> LoginFailureKind.TIMEOUT
            "not connected" in m || "could not reach" in m -> LoginFailureKind.NOT_CONNECTED
            else -> LoginFailureKind.OTHER
        }
    }
}

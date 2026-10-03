package com.mitas.ppnam.station5aa

/** What the operator can do about a failed login. */
enum class LoginFailureKind { WRONG_CREDENTIALS, REFUSED, TIMEOUT, NOT_CONNECTED, OTHER }

/** A station rejection that carries the contract's machine-readable `errorCode`. */
class StationRejection(val errorCode: String, message: String) : Exception(message)

/**
 * Classifies a login failure so LoginActivity can show an operator-facing string instead of
 * protocol text (UI audit group (f): "SCRAM proof rejected." was reaching the screen).
 *
 * Only credential-type rejection codes mean "Incorrect username or password" - a replayed or
 * expired challenge, a refused envelope etc. are not the operator's typing, so they get the
 * "refused by the station (code)" wording instead. Local failures (no response, no broker link)
 * are recognised from AuthClient's own messages; anything else is passed through, since the
 * contract says the station's free-text `reason` is already sanitised for display.
 */
object LoginFailure {
    private val CREDENTIAL_CODES = setOf(
        "authentication_failed", "invalid_credentials", "scram_proof_invalid",
    )

    fun classify(rawMessage: String?, errorCode: String? = null): LoginFailureKind {
        val code = errorCode.orEmpty().trim().lowercase()
        if (code.isNotEmpty()) {
            return when {
                code in CREDENTIAL_CODES -> LoginFailureKind.WRONG_CREDENTIALS
                "badge" in code -> LoginFailureKind.OTHER // reason ("Badge not recognized.") is operator-readable
                else -> LoginFailureKind.REFUSED
            }
        }
        val m = rawMessage.orEmpty().trim().lowercase()
        return when {
            m.isEmpty() -> LoginFailureKind.OTHER
            "did not respond" in m || "timed out" in m || "timeout" in m -> LoginFailureKind.TIMEOUT
            "not connected" in m || "could not reach" in m -> LoginFailureKind.NOT_CONNECTED
            else -> LoginFailureKind.OTHER
        }
    }
}

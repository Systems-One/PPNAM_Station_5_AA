package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Raw protocol text ("SCRAM proof rejected.") must never reach the operator (UI audit
 * group (f)); only credential-type error codes are reported as wrong credentials.
 */
class LoginFailureTest {

    @Test
    fun `credential-type codes are wrong-credentials`() {
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("SCRAM proof rejected.", "authentication_failed"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("x", "scram_proof_invalid"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("x", "INVALID_CREDENTIALS"))
    }

    @Test
    fun `other rejection codes are refused, not wrong-credentials`() {
        assertEquals(LoginFailureKind.REFUSED, LoginFailure.classify("Challenge expired", "scram_challenge_expired"))
        assertEquals(LoginFailureKind.REFUSED, LoginFailure.classify("Challenge reused", "scram_challenge_reused"))
        assertEquals(LoginFailureKind.REFUSED, LoginFailure.classify("Bad envelope", "invalid_envelope"))
    }

    @Test
    fun `reason text alone never means wrong credentials`() {
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify("SCRAM proof rejected."))
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify("Invalid password"))
    }

    @Test
    fun `badge rejections pass their reason through`() {
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify("Badge not recognized.", "badge_rejected"))
    }

    @Test
    fun `AuthClient's silence message is a timeout`() {
        assertEquals(LoginFailureKind.TIMEOUT, LoginFailure.classify("Station did not respond"))
        assertEquals(LoginFailureKind.TIMEOUT, LoginFailure.classify("Timed out waiting for 10000 ms"))
    }

    @Test
    fun `broker-side failures are not-connected`() {
        assertEquals(LoginFailureKind.NOT_CONNECTED, LoginFailure.classify("Not connected to the station"))
        assertEquals(LoginFailureKind.NOT_CONNECTED, LoginFailure.classify("Could not reach the station"))
    }

    @Test
    fun `anything else, including blank, is passed through as other`() {
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify("Badge not registered"))
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify(""))
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify(null))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(LoginFailureKind.TIMEOUT, LoginFailure.classify("STATION DID NOT RESPOND"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("x", "Authentication_Failed"))
    }
}

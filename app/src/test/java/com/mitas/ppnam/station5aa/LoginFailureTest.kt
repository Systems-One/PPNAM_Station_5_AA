package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Raw protocol text ("SCRAM proof rejected.") must never reach the operator (UI audit
 * group (f)); the classifier maps what AuthClient/Schema41 produce onto operator-facing kinds.
 */
class LoginFailureTest {

    @Test
    fun `SCRAM proof rejection is a wrong-credentials failure`() {
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("SCRAM proof rejected."))
    }

    @Test
    fun `password and credential wording is wrong-credentials`() {
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("Invalid password"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("Unknown credential"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("AUTH_FAILED"))
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
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("scram PROOF Rejected"))
    }
}

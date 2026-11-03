package com.mitas.ppnam.station5aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScramCryptoTest {
    // RFC 7677 §3 test vector.
    private val salt = "W22ZaJ0SNY7soEsUEjb6gQ=="
    private val clientNonce = "rOprNGfwEbeRWgbNEkqO"
    private val serverFirst = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0,s=$salt,i=4096"
    private val auth = ScramCrypto.authMessage(
        ScramCrypto.clientFirstBare("user", clientNonce),
        serverFirst,
        ScramCrypto.clientFinalWithoutProof("rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0"),
    )

    /** "ｐｅｎｃｉｌ" — full-width letters, which NFKC would fold to "pencil". */
    private val fullWidth = "ｐｅｎｃｉｌ"

    @Test fun rfc7677Vector() {
        val p = ScramCrypto.computeProof("pencil", salt, 4096, auth)
        assertEquals("dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=", p.clientProofBase64)
        assertEquals("6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=", p.expectedServerSignatureBase64)
    }

    @Test fun passwordBytesAreRawUtf8WithoutNormalisation() {
        assertArrayEquals(fullWidth.toByteArray(Charsets.UTF_8), ScramCrypto.normalizePassword(fullWidth))
    }

    /**
     * The desktop (StationAuthentication.cs, Rfc2898DeriveBytes.Pbkdf2(string, …)) derives from the
     * plain UTF-8 password; expected values computed independently (Python hashlib over the raw bytes).
     */
    @Test fun fullWidthPasswordDerivesFromItsRawUtf8Bytes() {
        val p = ScramCrypto.computeProof(fullWidth, salt, 4096, auth)
        assertEquals("wzKxmkRql44oo5FFILcGDjkv1D/V/vgRUuSsx5/Sw70=", p.clientProofBase64)
        assertEquals("TEMlartfJ8GVfGYTFkqhvd0/aX33fOejrgCOOGay6Y0=", p.expectedServerSignatureBase64)
        assertNotEquals(ScramCrypto.computeProof("pencil", salt, 4096, auth).clientProofBase64, p.clientProofBase64)
    }
}

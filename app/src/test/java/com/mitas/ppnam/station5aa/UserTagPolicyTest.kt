package com.mitas.ppnam.station5aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTagPolicyTest {

    @Test
    fun `badge epc with prefix is a user tag`() {
        assertTrue(UserTagPolicy.isUserTag("50505501AABBCCDDEEFF001122334455"))
    }

    @Test
    fun `lower case with surrounding whitespace is a user tag`() {
        assertTrue(UserTagPolicy.isUserTag("  50505501aabbccddeeff001122334455 "))
    }

    @Test
    fun `item epc is not a user tag`() {
        assertFalse(UserTagPolicy.isUserTag("E280689400005015ABCD1234"))
    }

    @Test
    fun `legacy badge epc is not a user tag`() {
        assertFalse(UserTagPolicy.isUserTag("5A39F436A9F28F75D72A24205F22E81A"))
    }

    @Test
    fun `null and blank are not user tags`() {
        assertFalse(UserTagPolicy.isUserTag(null))
        assertFalse(UserTagPolicy.isUserTag(""))
        assertFalse(UserTagPolicy.isUserTag("   "))
    }
}
package com.mitas.ppnam.station5aa

/** Badges issued by Account Management start with these 3 bytes (hex). Item EPCs never do. */
object UserTagPolicy {
    const val USER_TAG_PREFIX = "505055"
    fun isUserTag(epc: String?): Boolean =
        epc?.trim()?.uppercase()?.startsWith(USER_TAG_PREFIX) == true
}
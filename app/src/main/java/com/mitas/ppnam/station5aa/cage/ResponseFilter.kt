package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject

/**
 * Drops responses that are not for this handheld's pending request before they reach the
 * workflow: another device's payload, an answer to an older request, or a redelivery (QoS 1)
 * of a response already handled. Remembers the last [capacity] response message IDs.
 */
class ResponseFilter(private val deviceId: String, private val capacity: Int = 64) {

    private val seen = LinkedHashSet<String>()

    /** True when [json] is for this device, answers [pendingMessageId], and its own messageId was not seen before. */
    @Synchronized
    fun accept(json: JSONObject, pendingMessageId: String?): Boolean {
        if (pendingMessageId == null) return false
        if (json.optString("deviceId") != deviceId) return false
        if (json.optString("inResponseToMessageId") != pendingMessageId) return false
        val messageId = json.optString("messageId")
        if (messageId.isNotEmpty()) {
            if (messageId in seen) return false
            seen += messageId
            if (seen.size > capacity) seen.remove(seen.first())
        }
        return true
    }
}

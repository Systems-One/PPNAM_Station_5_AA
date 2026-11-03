package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject

interface OutboxStore {
    fun load(): PendingRequest?
    fun save(p: PendingRequest)
    fun clear()
}

class MemoryOutboxStore : OutboxStore {
    private var current: PendingRequest? = null
    override fun load(): PendingRequest? = current
    override fun save(p: PendingRequest) { current = p }
    override fun clear() { current = null }
}

object PendingRequestCodec {
    fun encode(p: PendingRequest): String = JSONObject().apply {
        put("requestType", p.requestType)
        put("responseType", p.responseType)
        put("topic", p.topic)
        put("messageId", p.messageId)
        put("body", p.body) // stored as an opaque string, never re-parsed
        put("operatorSessionId", p.operatorSessionId)
        put("intent", p.intent.name)
        put("loadId", p.loadId ?: JSONObject.NULL)
        put("bagId", p.bagId ?: JSONObject.NULL)
        put("firstSentAtMs", p.firstSentAtMs)
    }.toString()

    fun decode(s: String?): PendingRequest? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            val intent = RequestIntent.entries.firstOrNull { it.name == o.getString("intent") } ?: return null
            PendingRequest(
                requestType = o.getString("requestType"),
                responseType = o.getString("responseType"),
                topic = o.getString("topic"),
                messageId = o.getString("messageId"),
                body = o.getString("body"),
                operatorSessionId = o.getString("operatorSessionId"),
                intent = intent,
                loadId = if (o.isNull("loadId")) null else o.getString("loadId"),
                bagId = if (o.isNull("bagId")) null else o.getString("bagId"),
                firstSentAtMs = o.getLong("firstSentAtMs"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

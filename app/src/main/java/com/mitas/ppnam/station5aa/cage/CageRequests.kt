package com.mitas.ppnam.station5aa.cage

import com.mitas.ppnam.station5aa.MqttTopics
import com.mitas.ppnam.station5aa.Schema41
import org.json.JSONObject
import java.time.Instant

enum class RequestIntent { SELECT, READ_ACTIVE, READ_LOAD, RECOVER_ALLOCATION, RECOVER_COMPLETION, ALLOCATE, PREVIEW, CONFIRM }

data class PendingRequest(
    val requestType: String,
    val responseType: String,
    val topic: String,
    val messageId: String,
    val body: String,
    val operatorSessionId: String,
    val intent: RequestIntent,
    val loadId: String?,
    val bagId: String?,
    val firstSentAtMs: Long,
)

class CageRequests(
    private val deviceId: String,
    private val now: () -> Instant,
    private val newId: () -> String,
) {
    fun select(sessionId: String, rfidTag: String): PendingRequest =
        build(sessionId, "cage_select_requested", CONTEXT_RESULT, RequestIntent.SELECT, null, null) {
            put("rfidTag", rfidTag)
        }

    fun readActive(sessionId: String): PendingRequest =
        build(sessionId, "cage_context_requested", CONTEXT_RESULT, RequestIntent.READ_ACTIVE, null, null) {}

    fun readLoad(sessionId: String, loadId: String, intent: RequestIntent, bagId: String? = null): PendingRequest =
        build(sessionId, "cage_context_requested", CONTEXT_RESULT, intent, loadId, bagId) {
            put("loadId", loadId)
        }

    fun allocate(sessionId: String, loadId: String, qr: PackQr): PendingRequest =
        build(sessionId, "finished_good_allocate_requested", "finished_good_allocation_result",
            RequestIntent.ALLOCATE, loadId, qr.bagId) {
            put("loadId", loadId)
            put("barcode", qr.raw)
        }

    /** cage_complete_requested: asks the station for a completion summary and confirmation token. */
    fun preview(sessionId: String, loadId: String): PendingRequest =
        build(sessionId, "cage_complete_requested", "cage_completion_result",
            RequestIntent.PREVIEW, loadId, null) {
            put("loadId", loadId)
        }

    fun confirm(sessionId: String, loadId: String, token: String): PendingRequest =
        build(sessionId, "cage_complete_confirmed", "cage_completion_result",
            RequestIntent.CONFIRM, loadId, null) {
            put("loadId", loadId)
            put("confirmationToken", token)
        }

    private fun build(
        sessionId: String,
        requestType: String,
        responseType: String,
        intent: RequestIntent,
        loadId: String?,
        bagId: String?,
        fields: JSONObject.() -> Unit,
    ): PendingRequest {
        val at = now()
        val messageId = newId()
        val body = JSONObject().apply {
            put("messageId", messageId)
            put("schemaVersion", Schema41.SCHEMA_VERSION)
            put("deviceId", deviceId)
            put("operatorSessionId", sessionId)
            put("timestampUtc", Schema41.timestampUtc(at))
            fields()
        }.toString()
        return PendingRequest(
            requestType = requestType,
            responseType = responseType,
            topic = MqttTopics.deviceRequest(deviceId, requestType),
            messageId = messageId,
            body = body,
            operatorSessionId = sessionId,
            intent = intent,
            loadId = loadId,
            bagId = bagId,
            firstSentAtMs = at.toEpochMilli(),
        )
    }

    private companion object {
        const val CONTEXT_RESULT = "cage_context_result"
    }
}

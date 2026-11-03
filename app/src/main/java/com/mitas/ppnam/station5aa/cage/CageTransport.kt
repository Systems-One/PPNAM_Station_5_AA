package com.mitas.ppnam.station5aa.cage

import android.os.Handler
import android.util.Log
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import com.mitas.ppnam.station5aa.MqttManager
import com.mitas.ppnam.station5aa.MqttTopics
import org.json.JSONException
import org.json.JSONObject

/**
 * Receives this handheld's responses (`PPNAM/station_5/{deviceId}/res/+`), hands them to the main
 * thread, and passes on only those [ResponseFilter] accepts for the pending request.
 */
class CageTransport(
    private val mqtt: MqttManager,
    deviceId: String,
    private val main: Handler,
    private val pending: () -> PendingRequest?,
    private val deliver: (JSONObject) -> Unit,
) {
    private val topic = MqttTopics.deviceResponseWildcard(deviceId)
    private val filter = ResponseFilter(deviceId)
    private var subscribed = false

    private val callback: (Mqtt3Publish) -> Unit = { publish ->
        // HiveMQ thread: parse here, decide on the main thread where the outbox is read and changed.
        val json = parse(publish)
        if (json != null) main.post { onMain(json) }
    }

    fun start() {
        if (subscribed) return
        subscribed = true
        mqtt.subscribe(topic, callback)
    }

    private fun onMain(json: JSONObject) {
        val request = pending()
        if (!filter.accept(json, request?.messageId)) return
        if (request != null && json.optString("errorCode") == MESSAGE_ID_CONFLICT) {
            // Ruling R7: the workflow drops this request, so keep its exact bytes for support.
            Log.w(TAG, "message_id_conflict for messageId=${request.messageId} topic=${request.topic} body=${request.body}")
        }
        deliver(json)
    }

    private fun parse(publish: Mqtt3Publish): JSONObject? = try {
        JSONObject(String(publish.payloadAsBytes, Charsets.UTF_8))
    } catch (e: JSONException) {
        Log.w(TAG, "Ignoring non-JSON response on ${publish.topic}", e)
        null
    }

    private companion object {
        const val TAG = "CageController"
        const val MESSAGE_ID_CONFLICT = "message_id_conflict"
    }
}

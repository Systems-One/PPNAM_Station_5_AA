package com.mitas.ppnam.station5aa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One row of the login dropdown (contract v3.2.0 §4.5). Display-only. */
data class OperatorEntry(val username: String, val displayName: String)

object OperatorListCodec {

    /** Reads `operators` from an accepted `operator_list` response. Blank usernames are dropped. */
    fun fromResponse(json: JSONObject): List<OperatorEntry> = fromArray(json.optJSONArray("operators"))

    fun encode(list: List<OperatorEntry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("username", it.username).put("displayName", it.displayName)) }
    }.toString()

    fun decode(text: String?): List<OperatorEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            fromArray(JSONArray(text))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fromArray(array: JSONArray?): List<OperatorEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val username = o.optString("username", "").trim()
            if (username.isEmpty()) return@mapNotNull null
            OperatorEntry(username, o.optString("displayName", "").trim().ifEmpty { username })
        }
    }
}

/**
 * The login screen's operator directory: asks the station for the list and keeps the last
 * accepted one on the device so the dropdown is populated before (or without) an answer.
 *
 * No Station 5 station-side service exists in this tree yet, so nothing answers
 * `PPNAM/station_5/+/req/+`: until one ships the request simply times out, the dropdown stays
 * empty (or shows the last cached list) and a typed username logs in as before, by contract design.
 */
class OperatorDirectory(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("operator_directory", Context.MODE_PRIVATE)

    fun cached(): List<OperatorEntry> = OperatorListCodec.decode(prefs.getString(KEY_OPERATORS, null))

    /** Requests a fresh list; [onUpdated] runs on the main thread only when the station accepted. */
    fun refresh(onUpdated: (List<OperatorEntry>) -> Unit) {
        AuthClient(appContext).operatorList { result ->
            result.onSuccess { list ->
                prefs.edit().putString(KEY_OPERATORS, OperatorListCodec.encode(list)).apply()
                onUpdated(list)
            }
        }
    }

    private companion object {
        const val KEY_OPERATORS = "operators"
    }
}

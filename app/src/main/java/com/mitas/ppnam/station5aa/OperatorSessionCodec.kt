package com.mitas.ppnam.station5aa

import org.json.JSONArray
import org.json.JSONObject

/** Parses the `scram_proof_result` / `operator_context` login result (MQTT_CONTRACT §3). */
object OperatorSessionCodec {

    /** The result carries `operatorName`; older stations sent `displayName`, kept as a fallback. */
    fun fromProofResult(json: JSONObject): Result<OperatorSession> {
        val operatorSessionId = json.optString("operatorSessionId", "")
        val sessionState = json.optString("sessionState", "")
        return when {
            operatorSessionId.isBlank() ->
                Result.failure(Exception("Station accepted the login but issued no session"))
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            sessionState.equals("closed", ignoreCase = true) ->
                Result.failure(Exception("Station closed this session immediately"))
            else -> Result.success(
                OperatorSession(
                    operatorSessionId = operatorSessionId,
                    operatorId = json.optString("operatorId", ""),
                    operatorName = json.optString("operatorName", "").ifBlank { json.optString("displayName", "") },
                    role = json.optString("role", ""),
                    allowedActions = json.optJSONArray("allowedActions").toStringList(),
                    allowedTabs = json.optJSONArray("allowedTabs").toStringList(),
                )
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
    }
}

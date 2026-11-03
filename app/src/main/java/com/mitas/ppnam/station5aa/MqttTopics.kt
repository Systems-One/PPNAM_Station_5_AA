package com.mitas.ppnam.station5aa

/**
 * Per-station namespace topic structure per MQTT_CONTRACT §1
 * (`../PPNAM-Station-5/MQTT_CONTRACT.md`, 2 Nov 2026):
 *
 *   PPNAM/station_5/status                     station status (raw online/offline text)
 *   PPNAM/station_5/{deviceId}                 device presence (retained, LWT)
 *   PPNAM/station_5/{deviceId}/req/{suffix}    request  FROM device TO the station
 *   PPNAM/station_5/{deviceId}/res/{suffix}    response FROM station TO device
 *
 * This app serves Station 5 only, so the station segment is a constant rather than a parameter.
 */
object MqttTopics {

    private const val STATION_BASE = "PPNAM/station_5"

    /** The station's status topic — raw `online` / `offline` text. */
    fun stationStatus(): String = "$STATION_BASE/status"

    /** This scanner's base node — carries its retained presence payload and Last Will. */
    fun devicePresence(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "$STATION_BASE/$deviceId"
    }

    fun deviceRequest(deviceId: String, suffix: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(suffix, "suffix")
        return "$STATION_BASE/$deviceId/req/$suffix"
    }

    fun deviceResponse(deviceId: String, suffix: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(suffix, "suffix")
        return "$STATION_BASE/$deviceId/res/$suffix"
    }

    /** Single subscription covering every response addressed to this device. */
    fun deviceResponseWildcard(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "$STATION_BASE/$deviceId/res/+"
    }

    // The contract forbids '/', '+' and '#' in a topic segment. A segment carrying one of these
    // would silently reshape the topic (or subscribe to a wildcard), so fail loudly instead.
    private fun validateSegment(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it == '/' || it == '+' || it == '#' }) {
            "$name must not contain '/', '+' or '#': was '$value'"
        }
    }
}

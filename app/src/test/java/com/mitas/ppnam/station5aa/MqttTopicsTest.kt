package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Per-station namespace per MQTT_CONTRACT §1: all Station 5 traffic nests under
 * PPNAM/station_5/...; the station publishes raw online/offline on PPNAM/station_5 itself.
 *
 * This app serves Station 5 only, so the station segment is fixed rather than a parameter.
 */
class MqttTopicsTest {

    @Test
    fun `station status is the station base node`() {
        assertEquals("PPNAM/station_5", MqttTopics.stationStatus())
    }

    @Test
    fun `the legacy status subtopic is still read as station status`() {
        assertEquals("PPNAM/station_5/status", MqttTopics.legacyStationStatus())
        assertEquals(true, MqttTopics.isStationStatus("PPNAM/station_5"))
        assertEquals(true, MqttTopics.isStationStatus("PPNAM/station_5/status"))
        assertEquals(false, MqttTopics.isStationStatus("PPNAM/station_5/scanner_1"))
    }

    @Test
    fun `device response wildcard covers all of this devices responses`() {
        assertEquals("PPNAM/station_5/scanner_ab12/res/+", MqttTopics.deviceResponseWildcard("scanner_ab12"))
    }

    @Test
    fun `device presence lives on the device base node`() {
        assertEquals("PPNAM/station_5/scanner_2", MqttTopics.devicePresence("scanner_2"))
    }

    @Test
    fun `device request nests under the station namespace`() {
        assertEquals(
            "PPNAM/station_5/scanner_1/req/assignment_v2",
            MqttTopics.deviceRequest("scanner_1", "assignment_v2")
        )
    }

    @Test
    fun `device response nests under the station namespace`() {
        assertEquals(
            "PPNAM/station_5/scanner_1/res/assignment_result",
            MqttTopics.deviceResponse("scanner_1", "assignment_result")
        )
    }

    @Test
    fun `a deviceId containing a topic separator is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceRequest("scanner/1", "assignment")
        }
    }

    @Test
    fun `a suffix containing a wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceResponse("scanner_1", "res+ult")
        }
    }

    @Test
    fun `a blank suffix is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceResponse("scanner_1", " ")
        }
    }
}

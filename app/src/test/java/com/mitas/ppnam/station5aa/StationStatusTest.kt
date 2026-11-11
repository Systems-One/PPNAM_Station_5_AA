package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Station status is raw online/offline text on PPNAM/station_5 (MQTT_CONTRACT §1). */
class StationStatusTest {

    @Test
    fun onlineIsTrimmedAndCaseInsensitive() = assertEquals(true, MqttManager.parseStationStatus(" Online\n"))

    @Test
    fun offlineIsFalse() = assertEquals(false, MqttManager.parseStationStatus("offline"))

    @Test
    fun anythingElseIsIgnored() = assertNull(MqttManager.parseStationStatus("{\"state\":\"online\"}"))
}

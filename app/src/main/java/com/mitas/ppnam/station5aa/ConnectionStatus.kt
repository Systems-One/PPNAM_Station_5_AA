package com.mitas.ppnam.station5aa

/**
 * Mirrors Station 2's ConnectionStatus precedence (broker link, then station presence).
 * No ClockSkewed case here — Station 5's MQTT flow has no request/response round trip
 * to measure clock skew from.
 */
enum class ConnectionStatus { OFFLINE, RECONNECTING, STATION_OFFLINE, CONNECTED }

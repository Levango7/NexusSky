package io.aerofleet.cloud.mission;

/**
 * JSON body of one mission item as sent by the web GCS:
 * {"cmd":"waypoint","lat":22.59,"lon":113.93,"alt":50,"holdTime":2}
 */
public record MissionItemRequest(String cmd, double lat, double lon, double alt, double holdTime) {
}

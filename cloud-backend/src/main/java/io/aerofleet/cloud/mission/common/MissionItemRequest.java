package io.aerofleet.cloud.mission.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * JSON body of one mission item as sent by the web GCS:
 * {"cmd":"waypoint","lat":22.59,"lon":113.93,"alt":50,"holdTime":2}
 * <p>
 * 字段约束（JSR303）：cmd 非空，lat -90~90，lon -180~180，holdTime >= 0。
 */
public record MissionItemRequest(
        @NotBlank String cmd,
        @Min(-90) @Max(90) double lat,
        @Min(-180) @Max(180) double lon,
        double alt,
        @Min(0) double holdTime) {
}

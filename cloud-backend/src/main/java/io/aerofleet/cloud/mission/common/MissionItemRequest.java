package io.aerofleet.cloud.mission.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * JSON body of one mission item as sent by the web GCS:
 * {"cmd":"waypoint","lat":22.59,"lon":113.93,"alt":50,"holdTime":2}
 * <p>
 * 字段约束（JSR303）：cmd 非空，lat -90~90，lon -180~180，holdTime >= 0。
 * <p>
 * <strong>这些注解在 mission 上传路径上不会自动触发</strong>：{@code DroneController}
 * 用 {@code @RequestBody JsonNode} 收原始 body，再由 {@code parseMissionItems()}
 * 手工 {@code new} 出本 record，不走 Spring 绑定，{@code @Valid} 无从挂载。
 * 实际生效的范围检查全在 {@code parseMissionItems()} 里手工做
 * （cmd 非空 / lat / lon / alt / holdTime 五条）。
 * <p>
 * 保留注解是为了让本record 的契约自解释，以及给将来改成 {@code @Valid} 绑定
 * 的调用方用；<strong>不要以为它们在生效</strong>。
 */
public record MissionItemRequest(
        @NotBlank String cmd,
        @Min(-90) @Max(90) double lat,
        @Min(-180) @Max(180) double lon,
        double alt,
        @Min(0) double holdTime) {
}

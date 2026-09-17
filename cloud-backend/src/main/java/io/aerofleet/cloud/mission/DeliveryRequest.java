package io.aerofleet.cloud.mission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建配送任务请求体 DTO（FR-23）。
 * <p>
 * 公共字段风格与 {@link SprayTaskRequest} 一致。
 * 字段约束（JSR303）：sysid 1~255，站点列表非空，坐标/负载物理量非负。
 */
public final class DeliveryRequest {

    /** 目标无人机 sysid。 */
    @Min(1)
    @Max(255)
    public int targetSysid;

    /** 站点序列（每站含坐标/负载/投放精度）。 */
    @NotNull
    @NotEmpty
    @Size(max = 128)
    @Valid
    public List<SiteDto> sites;

    /** 单站点 DTO。 */
    public static final class SiteDto {
        @Min(-90)
        @Max(90)
        public double lat;

        @Min(-180)
        @Max(180)
        public double lon;

        public double alt;

        @Min(0)
        public int payloadId;

        @Min(0)
        public double payloadWeightKg;

        @Min(0)
        public double payloadVolumeL;

        @Min(0)
        public double dropAccuracyM;
    }
}

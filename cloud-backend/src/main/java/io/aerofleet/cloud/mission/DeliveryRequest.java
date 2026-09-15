package io.aerofleet.cloud.mission;

import java.util.List;

/**
 * 创建配送任务请求体 DTO（FR-23）。
 * <p>
 * 公共字段风格与 {@link SprayTaskRequest} 一致。
 */
public final class DeliveryRequest {

    /** 目标无人机 sysid。 */
    public int targetSysid;

    /** 站点序列（每站含坐标/负载/投放精度）。 */
    public List<SiteDto> sites;

    /** 单站点 DTO。 */
    public static final class SiteDto {
        public double lat;
        public double lon;
        public double alt;
        public int payloadId;
        public double payloadWeightKg;
        public double payloadVolumeL;
        public double dropAccuracyM;
    }
}
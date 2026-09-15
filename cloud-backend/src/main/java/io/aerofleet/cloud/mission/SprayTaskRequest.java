package io.aerofleet.cloud.mission;

import java.util.List;

/**
 * 创建喷洒任务请求体 DTO（FR-12）。
 * <p>
 * 公共字段风格与 {@link FormationController.FormationCreateRequest} 一致，
 * 由 Spring 反序列化 JSON 注入。
 */
public final class SprayTaskRequest {

    /** 目标无人机 sysid（0 = 任意在线无人机）。 */
    public int targetSysid;

    /** 航点序列，每个元素为 {lat, lon}，至少 2 个航点。 */
    public List<double[]> waypoints;

    /** 目标流量 mL/s。 */
    public double targetRate;

    /** 药箱容量 mL。 */
    public double capacityMl;

    /** 喷幅 m。 */
    public double sprayWidth;
}
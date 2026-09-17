package io.aerofleet.cloud.mission;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建喷洒任务请求体 DTO（FR-12）。
 * <p>
 * 公共字段风格与 {@link FormationCreateRequest} 一致，
 * 由 Spring 反序列化 JSON 注入。
 * <p>
 * 字段约束（JSR303）：sysid 0~255，航点 >= 2，流量/容量/喷幅非负。
 */
public final class SprayTaskRequest {

    /** 目标无人机 sysid（0 = 任意在线无人机）。 */
    @Min(0)
    @Max(255)
    public int targetSysid;

    /** 航点序列，每个元素为 {lat, lon}，至少 2 个航点。 */
    @NotNull
    @NotEmpty
    @Size(max = 1024)
    public List<double[]> waypoints;

    /** 目标流量 mL/s。 */
    @Min(0)
    public double targetRate;

    /** 药箱容量 mL。 */
    @Min(0)
    public double capacityMl;

    /** 喷幅 m。 */
    @Min(0)
    public double sprayWidth;
}

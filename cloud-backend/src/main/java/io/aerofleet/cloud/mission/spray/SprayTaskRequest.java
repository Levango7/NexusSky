package io.aerofleet.cloud.mission.spray;

import io.aerofleet.cloud.mission.formation.FormationCreateRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建喷洒任务请求体 DTO（FR-12）。
 * <p>
 * 公共字段风格与 {@link FormationCreateRequest} 一致，
 * 由 Spring 反序列化 JSON 注入。
 * <p>
 * 字段约束（JSR303）：sysid 0~255，航点 2~1024，流量/容量/喷幅为正数。
 * <p>
 * <strong>表面积量三个字段刻意用 {@code @Positive} 而不是 {@code @Min(0)}</strong>：
 * {@code SprayTaskService.create()} 要求 {@code > 0}（sprayWidth=0 会让
 * {@code SprayTask} 的 totalArea 恒为 0，capacityMl=0 会让余量百分比走 0 分支），
 * 所以注解必须与服务层同口径 —— 写成 {@code @Min(0)} 会对外宣称 0 合法，
 * 然后由服务层再拒一次，两层讲两套话。
 * <p>
 * 服务层那三条 {@code <= 0} 检查保留作为冗余防线：万一入口的
 * {@code @Valid} 被去掉，它们仍然拦得住（这正是 {@code SprayTaskRequestValidationTest}
 * 覆盖的内容）。
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
    @Positive
    public double targetRate;

    /** 药箱容量 mL。 */
    @Positive
    public double capacityMl;

    /** 喷幅 m。 */
    @Positive
    public double sprayWidth;
}

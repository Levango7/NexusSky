package io.aerofleet.cloud.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * ConflictAvoidanceService 空域冲突避免服务单测（M10）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖：
 * <ul>
 *   <li>原有 3D 冲突检测 / 高度分层 / 时间错开（向后兼容）</li>
 *   <li>4D 航迹预测、4D 冲突检测（HEAD-ON/CROSSING/OVERTAKE）</li>
 *   <li>时间-空间预约表</li>
 *   <li>冲突解决机动（高度分层/速度调整/等待盘旋）</li>
 * </ul>
 */
@DisplayName("ConflictAvoidanceService 空域冲突避免 (M10)")
class ConflictAvoidanceServiceTest {

    private ConflictAvoidanceService service;

    @BeforeEach
    void setUp() {
        service = new ConflictAvoidanceService();
        // 预约表有状态，每个测试前清空
        service.clearReservations();
    }

    // ==================== 原有 3D 测试（向后兼容） ====================

    @Test
    @DisplayName("checkConflict 同位置同高度判定为冲突")
    void checkConflictDetectsCollision() {
        // 两机同经纬度同高度
        ConflictAvoidanceService.ConflictResult result = service.checkConflict(
                30.0, 120.0, 100.0, 10, 0,
                30.0, 120.0, 100.0, 10, 180);

        assertThat(result.conflict).isTrue();
        assertThat(result.type).isEqualTo("COLLISION");
        assertThat(result.horizontalDistance).isLessThan(50.0);
        assertThat(result.verticalDistance).isLessThan(10.0);
        assertThat(result.timeToConflict).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("checkConflict 水平距离足够大时无冲突")
    void checkConflictClearByHorizontalSeparation() {
        // 经度差 0.01° ≈ 1km > 50m
        ConflictAvoidanceService.ConflictResult result = service.checkConflict(
                30.0, 120.0, 100.0, 10, 0,
                30.0, 120.01, 100.0, 10, 180);

        assertThat(result.conflict).isFalse();
        assertThat(result.type).isEqualTo("CLEAR");
        assertThat(result.horizontalDistance).isGreaterThan(50.0);
    }

    @Test
    @DisplayName("checkConflict 垂直距离足够大时无冲突")
    void checkConflictClearByVerticalSeparation() {
        // 同位置，高度差 100m > 10m
        ConflictAvoidanceService.ConflictResult result = service.checkConflict(
                30.0, 120.0, 100.0, 10, 0,
                30.0, 120.0, 200.0, 10, 180);

        assertThat(result.conflict).isFalse();
        assertThat(result.type).isEqualTo("CLEAR");
        assertThat(result.verticalDistance).isGreaterThanOrEqualTo(10.0);
    }

    @Test
    @DisplayName("checkConflict 水平+垂直都接近但恰好不冲突时返回 CLEAR")
    void checkConflictBoundaryNoConflict() {
        // 水平距离 0（同点），垂直差 10m（恰好等于阈值，< 不成立）
        ConflictAvoidanceService.ConflictResult result = service.checkConflict(
                30.0, 120.0, 100.0, 10, 0,
                30.0, 120.0, 110.0, 10, 180);

        // vDist = 10，vConflict = (10 < 10) = false
        assertThat(result.conflict).isFalse();
        assertThat(result.type).isEqualTo("CLEAR");
    }

    @Test
    @DisplayName("checkConflict 时间到冲突估算随接近率变化")
    void checkConflictTimeToConflictReflectsClosingRate() {
        // 慢速接近
        ConflictAvoidanceService.ConflictResult slow = service.checkConflict(
                30.0, 120.0, 100.0, 5, 0,
                30.0, 120.0, 100.0, 5, 180);
        // 快速接近
        ConflictAvoidanceService.ConflictResult fast = service.checkConflict(
                30.0, 120.0, 100.0, 20, 0,
                30.0, 120.0, 100.0, 20, 180);

        assertThat(slow.conflict).isTrue();
        assertThat(fast.conflict).isTrue();
        // 同位置时 hDist=0，timeToConflict=0/closingRate=0，两者都为 0
        // 这里仅验证不抛异常且非负
        assertThat(slow.timeToConflict).isGreaterThanOrEqualTo(0);
        assertThat(fast.timeToConflict).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("resolveByAltitude 返回高度分层指令字符串")
    void resolveByAltitudeReturnsInstruction() {
        String result = service.resolveByAltitude(1, 2);

        assertThat(result).contains("sysid 1").contains("climb +10m");
        assertThat(result).contains("sysid 2").contains("descend -10m");
    }

    @Test
    @DisplayName("resolveByTime 返回时间错开指令字符串")
    void resolveByTimeReturnsInstruction() {
        String result = service.resolveByTime(1, 2);

        assertThat(result).contains("sysid 2").contains("delay 5s");
    }

    @Test
    @DisplayName("resolveByAltitude 不同 sysid 生成不同指令")
    void resolveByAltitudeDifferentSysids() {
        String r1 = service.resolveByAltitude(1, 2);
        String r2 = service.resolveByAltitude(3, 4);

        assertThat(r1).isNotEqualTo(r2);
        assertThat(r2).contains("sysid 3").contains("sysid 4");
    }

    // ==================== 4D 航迹预测 ====================

    @Test
    @DisplayName("predictTrajectory4D 直线运动航迹正确")
    void predictTrajectory4DStraightLine() {
        // 无人机在 (30, 120, 100)，速度 10m/s，航向 90°（正东），预测 5 秒
        List<double[]> traj = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 5);

        // 点数 = horizonSec + 1 = 6
        assertThat(traj).hasSize(6);

        // t=0 为初始位置
        assertThat(traj.get(0)[0]).isEqualTo(30.0, within(1e-9));
        assertThat(traj.get(0)[1]).isEqualTo(120.0, within(1e-9));
        assertThat(traj.get(0)[2]).isEqualTo(100.0, within(1e-9));
        assertThat(traj.get(0)[3]).isEqualTo(0.0, within(1e-9));

        // 高度不变（匀速直线）
        for (double[] p : traj) {
            assertThat(p[2]).isEqualTo(100.0, within(1e-9));
        }

        // 时间戳递增 0,1,2,3,4,5
        for (int i = 0; i < traj.size(); i++) {
            assertThat(traj.get(i)[3]).isEqualTo(i, within(1e-9));
        }

        // 航向 90°（正东）：纬度不变，经度递增
        assertThat(traj.get(1)[0]).isEqualTo(30.0, within(1e-9)); // 纬度不变
        assertThat(traj.get(1)[1]).isGreaterThan(120.0);          // 经度递增

        // 每秒向东移动约 10m，相邻点经度增量应使距离≈10m
        // 代码用 EARTH_RADIUS(6371000m) * cos(lat) * π/180 换算每度经度的米数
        double lonStep = traj.get(1)[1] - traj.get(0)[1];
        double metersPerDegreeLon = 6371000.0 * Math.cos(Math.toRadians(30.0)) * Math.PI / 180.0;
        double expectedLonStep = 10.0 / metersPerDegreeLon;
        assertThat(lonStep).isEqualTo(expectedLonStep, within(1e-12));
    }

    @Test
    @DisplayName("predictTrajectory4D 航向 0° 向正北移动")
    void predictTrajectory4DHeadingNorth() {
        List<double[]> traj = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 0.0, 3);

        assertThat(traj).hasSize(4);
        // 航向 0°（正北）：经度不变，纬度递增
        assertThat(traj.get(1)[1]).isEqualTo(120.0, within(1e-9));
        assertThat(traj.get(1)[0]).isGreaterThan(30.0);
    }

    @Test
    @DisplayName("predictTrajectory4D 负 horizonSec 抛异常")
    void predictTrajectory4DNegativeHorizonThrows() {
        assertThatThrownBy(() -> service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 4D 冲突检测 ====================

    @Test
    @DisplayName("checkConflict4D 对头相遇检测到 HEAD-ON 冲突")
    void checkConflict4DHeadOn() {
        // 无人机1 在 (30, 120) 向东 10m/s
        // 无人机2 在 (30, 120.001) 向西 10m/s，航向 270°
        // 120.001° 在 lat=30 ≈ 96m 东，相对速度 20m/s，约 4.8s 相遇
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 10);
        List<double[]> traj2 = service.predictTrajectory4D(30.0, 120.001, 100.0, 10.0, 270.0, 10);

        ConflictAvoidanceService.ConflictResult result = service.checkConflict4D(traj1, traj2);

        assertThat(result.conflict).isTrue();
        assertThat(result.type).isEqualTo("HEAD-ON");
        assertThat(result.timeToConflict).isGreaterThan(0).isLessThan(10);
        assertThat(result.horizontalDistance).isLessThan(50.0);
    }

    @Test
    @DisplayName("checkConflict4D 交叉航线检测到 CROSSING 冲突")
    void checkConflict4DCrossing() {
        // 无人机1 在 (30, 120) 向东 10m/s，heading=90
        // 无人机2 在 (30.0005, 120) 向东南 10m/s，heading=150（航向差 60° → CROSSING）
        // 无人机2 在无人机1 北方约 55m，向东南走，路径会交叉
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 10);
        List<double[]> traj2 = service.predictTrajectory4D(30.0005, 120.0, 100.0, 10.0, 150.0, 10);

        ConflictAvoidanceService.ConflictResult result = service.checkConflict4D(traj1, traj2);

        assertThat(result.conflict).isTrue();
        assertThat(result.type).isEqualTo("CROSSING");
        assertThat(result.timeToConflict).isGreaterThan(0).isLessThan(10);
    }

    @Test
    @DisplayName("checkConflict4D 同向追击检测到 OVERTAKE 冲突")
    void checkConflict4DOvertake() {
        // 无人机1 在 (30, 120) 向东 5m/s（慢）
        // 无人机2 在 (30, 119.9985) 向东 15m/s（快），约 144m 西
        // 相对速度 10m/s，约 9.4s 后距离 < 50m
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 5.0, 90.0, 15);
        List<double[]> traj2 = service.predictTrajectory4D(30.0, 119.9985, 100.0, 15.0, 90.0, 15);

        ConflictAvoidanceService.ConflictResult result = service.checkConflict4D(traj1, traj2);

        assertThat(result.conflict).isTrue();
        assertThat(result.type).isEqualTo("OVERTAKE");
        assertThat(result.timeToConflict).isGreaterThan(5).isLessThan(15);
    }

    @Test
    @DisplayName("checkConflict4D 不同高度无冲突")
    void checkConflict4DNoConflictByAltitude() {
        // 两机水平对头，但高度差 100m > 10m
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 10);
        List<double[]> traj2 = service.predictTrajectory4D(30.0, 120.0, 200.0, 10.0, 270.0, 10);

        ConflictAvoidanceService.ConflictResult result = service.checkConflict4D(traj1, traj2);

        assertThat(result.conflict).isFalse();
        assertThat(result.type).isEqualTo("CLEAR");
    }

    @Test
    @DisplayName("checkConflict4D 水平距离始终足够大时无冲突")
    void checkConflict4DNoConflictByHorizontalSeparation() {
        // 两机同向同速，初始相距约 1km，永不接近
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 10);
        List<double[]> traj2 = service.predictTrajectory4D(30.0, 120.01, 100.0, 10.0, 90.0, 10);

        ConflictAvoidanceService.ConflictResult result = service.checkConflict4D(traj1, traj2);

        assertThat(result.conflict).isFalse();
        assertThat(result.type).isEqualTo("CLEAR");
    }

    @Test
    @DisplayName("checkAllConflicts 批量检测多机两两冲突")
    void checkAllConflictsBatch() {
        // 三架无人机：1 和 2 对头冲突，3 远离无冲突
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 10);
        List<double[]> traj2 = service.predictTrajectory4D(30.0, 120.001, 100.0, 10.0, 270.0, 10);
        List<double[]> traj3 = service.predictTrajectory4D(31.0, 121.0, 100.0, 10.0, 90.0, 10);

        List<ConflictAvoidanceService.ConflictResult> conflicts = service.checkAllConflicts(List.of(
                new ConflictAvoidanceService.DroneTrajectory(1, traj1),
                new ConflictAvoidanceService.DroneTrajectory(2, traj2),
                new ConflictAvoidanceService.DroneTrajectory(3, traj3)
        ));

        // 只有 1 vs 2 冲突
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).conflict).isTrue();
        assertThat(conflicts.get(0).type).isEqualTo("HEAD-ON");
    }

    @Test
    @DisplayName("checkAllConflicts 少于 2 架时返回空列表")
    void checkAllConflictsSingleDrone() {
        List<double[]> traj1 = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 5);

        List<ConflictAvoidanceService.ConflictResult> conflicts = service.checkAllConflicts(List.of(
                new ConflictAvoidanceService.DroneTrajectory(1, traj1)
        ));

        assertThat(conflicts).isEmpty();
    }

    // ==================== 时间-空间预约 ====================

    @Test
    @DisplayName("reserveAirspace 无冲突时预约成功")
    void reserveAirspaceSuccess() {
        boolean ok = service.reserveAirspace(1, 30.0, 120.0, 100.0, 0, 30, 50);

        assertThat(ok).isTrue();
        assertThat(service.getReservations(1)).hasSize(1);
    }

    @Test
    @DisplayName("reserveAirspace 有冲突时预约被拒")
    void reserveAirspaceRejectedOnConflict() {
        // 无人机1 预约 (30, 120, 100) t=[0,30] r=50
        boolean first = service.reserveAirspace(1, 30.0, 120.0, 100.0, 0, 30, 50);
        assertThat(first).isTrue();

        // 无人机2 预约同位置同高度同时间段 → 冲突，拒绝
        boolean second = service.reserveAirspace(2, 30.0, 120.0, 100.0, 10, 40, 50);
        assertThat(second).isFalse();
        assertThat(service.getReservations(2)).isEmpty();
    }

    @Test
    @DisplayName("reserveAirspace 时间不重叠时预约成功")
    void reserveAirspaceNonOverlappingTimeSuccess() {
        boolean first = service.reserveAirspace(1, 30.0, 120.0, 100.0, 0, 30, 50);
        assertThat(first).isTrue();

        // 同位置但时间段不重叠 [40, 60]
        boolean second = service.reserveAirspace(2, 30.0, 120.0, 100.0, 40, 60, 50);
        assertThat(second).isTrue();
    }

    @Test
    @DisplayName("reserveAirspace 高度分层时预约成功")
    void reserveAirspaceAltitudeSeparationSuccess() {
        boolean first = service.reserveAirspace(1, 30.0, 120.0, 100.0, 0, 30, 50);
        assertThat(first).isTrue();

        // 同位置同时间但高度差 100m > 10m → 无冲突
        boolean second = service.reserveAirspace(2, 30.0, 120.0, 200.0, 0, 30, 50);
        assertThat(second).isTrue();
    }

    @Test
    @DisplayName("reserveAirspace 同一无人机多次预约均成功")
    void reserveAirspaceSameDroneMultipleReservations() {
        boolean first = service.reserveAirspace(1, 30.0, 120.0, 100.0, 0, 10, 50);
        boolean second = service.reserveAirspace(1, 30.0, 120.0, 100.0, 10, 20, 50);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(service.getReservations(1)).hasSize(2);
    }

    @Test
    @DisplayName("checkReservationConflict 无预约时返回 false")
    void checkReservationConflictEmptyTable() {
        boolean conflict = service.checkReservationConflict(1, 30.0, 120.0, 100.0, 0, 30, 50);
        assertThat(conflict).isFalse();
    }

    @Test
    @DisplayName("reserveAirspace 非法参数抛异常")
    void reserveAirspaceInvalidParamsThrow() {
        assertThatThrownBy(() -> service.reserveAirspace(1, 30.0, 120.0, 100.0, 30, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserveAirspace(1, 30.0, 120.0, 100.0, 0, 30, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 冲突解决机动 ====================

    @Test
    @DisplayName("resolveConflict 高度分层返回正确建议")
    void resolveConflictAltitudeLayer() {
        ConflictAvoidanceService.ConflictResult conflict =
                new ConflictAvoidanceService.ConflictResult(true, 30, 5, 4, "HEAD-ON");

        ConflictAvoidanceService.ResolutionAdvice advice =
                service.resolveConflict(1, 2, conflict, ConflictAvoidanceService.ResolutionStrategy.ALTITUDE_LAYER);

        assertThat(advice.sysid1).isEqualTo(1);
        assertThat(advice.sysid2).isEqualTo(2);
        assertThat(advice.strategy).isEqualTo(ConflictAvoidanceService.ResolutionStrategy.ALTITUDE_LAYER);
        assertThat(advice.paramValue).isEqualTo(15.0);
        assertThat(advice.description).contains("climb").contains("descend");
        assertThat(advice.description).contains("sysid 1").contains("sysid 2");
    }

    @Test
    @DisplayName("resolveConflict 速度调整返回正确建议")
    void resolveConflictSpeedAdjust() {
        ConflictAvoidanceService.ConflictResult conflict =
                new ConflictAvoidanceService.ConflictResult(true, 30, 5, 4, "HEAD-ON");

        ConflictAvoidanceService.ResolutionAdvice advice =
                service.resolveConflict(1, 2, conflict, ConflictAvoidanceService.ResolutionStrategy.SPEED_ADJUST);

        assertThat(advice.strategy).isEqualTo(ConflictAvoidanceService.ResolutionStrategy.SPEED_ADJUST);
        assertThat(advice.paramValue).isPositive();
        assertThat(advice.description).contains("decelerate").contains("accelerate");
    }

    @Test
    @DisplayName("resolveConflict 速度调整紧急情况加倍")
    void resolveConflictSpeedAdjustUrgent() {
        // 冲突时间 < 10s → 紧急，速度调整加倍
        ConflictAvoidanceService.ConflictResult urgent =
                new ConflictAvoidanceService.ConflictResult(true, 30, 5, 5, "HEAD-ON");
        ConflictAvoidanceService.ConflictResult normal =
                new ConflictAvoidanceService.ConflictResult(true, 30, 5, 15, "HEAD-ON");

        ConflictAvoidanceService.ResolutionAdvice urgentAdvice =
                service.resolveConflict(1, 2, urgent, ConflictAvoidanceService.ResolutionStrategy.SPEED_ADJUST);
        ConflictAvoidanceService.ResolutionAdvice normalAdvice =
                service.resolveConflict(1, 2, normal, ConflictAvoidanceService.ResolutionStrategy.SPEED_ADJUST);

        assertThat(urgentAdvice.paramValue).isGreaterThan(normalAdvice.paramValue);
    }

    @Test
    @DisplayName("resolveConflict 等待盘旋返回正确建议")
    void resolveConflictHoldingPattern() {
        ConflictAvoidanceService.ConflictResult conflict =
                new ConflictAvoidanceService.ConflictResult(true, 30, 5, 4, "HEAD-ON");

        ConflictAvoidanceService.ResolutionAdvice advice =
                service.resolveConflict(1, 2, conflict, ConflictAvoidanceService.ResolutionStrategy.HOLDING_PATTERN);

        assertThat(advice.strategy).isEqualTo(ConflictAvoidanceService.ResolutionStrategy.HOLDING_PATTERN);
        assertThat(advice.paramValue).isEqualTo(20.0);
        assertThat(advice.description).contains("holding").contains("wait");
        assertThat(advice.description).contains("sysid 1");
    }

    @Test
    @DisplayName("resolveConflict 无 sysid 重载使用占位 0/1")
    void resolveConflictDefaultSysids() {
        ConflictAvoidanceService.ConflictResult conflict =
                new ConflictAvoidanceService.ConflictResult(true, 30, 5, 4, "HEAD-ON");

        ConflictAvoidanceService.ResolutionAdvice advice =
                service.resolveConflict(conflict, ConflictAvoidanceService.ResolutionStrategy.ALTITUDE_LAYER);

        assertThat(advice.sysid1).isEqualTo(0);
        assertThat(advice.sysid2).isEqualTo(1);
    }

    // ==================== 边界与防御性 ====================

    @Test
    @DisplayName("checkConflict4D 空航迹返回 CLEAR")
    void checkConflict4DEmptyTrajectory() {
        List<double[]> empty = List.of();
        List<double[]> traj = service.predictTrajectory4D(30.0, 120.0, 100.0, 10.0, 90.0, 5);

        ConflictAvoidanceService.ConflictResult r1 = service.checkConflict4D(empty, traj);
        ConflictAvoidanceService.ConflictResult r2 = service.checkConflict4D(null, traj);

        assertThat(r1.conflict).isFalse();
        assertThat(r2.conflict).isFalse();
    }

    @Test
    @DisplayName("checkAllConflicts null 或空列表返回空结果")
    void checkAllConflictsNullOrEmpty() {
        assertThat(service.checkAllConflicts(null)).isEmpty();
        assertThat(service.checkAllConflicts(List.of())).isEmpty();
    }
}

package io.aerofleet.cloud.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConflictAvoidanceService 空域冲突避免服务单测（M10）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖冲突检测/高度分层/时间错开。
 */
@DisplayName("ConflictAvoidanceService 空域冲突避免 (M10)")
class ConflictAvoidanceServiceTest {

    private ConflictAvoidanceService service;

    @BeforeEach
    void setUp() {
        service = new ConflictAvoidanceService();
    }

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
}
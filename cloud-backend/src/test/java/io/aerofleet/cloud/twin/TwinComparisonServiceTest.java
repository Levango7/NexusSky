package io.aerofleet.cloud.twin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TwinComparisonService 虚实对比单测（M13）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖位置/速度/航向误差计算。
 */
@DisplayName("TwinComparisonService 虚实对比 (M13)")
class TwinComparisonServiceTest {

    private TwinComparisonService service;

    @BeforeEach
    void setUp() {
        service = new TwinComparisonService();
    }

    @Test
    @DisplayName("compare 完全一致状态返回零误差")
    void compareIdenticalStatesGivesZeroError() {
        TwinState actual = new TwinState(1, 30.0, 120.0, 100, 45, 10, 80, 0, 0);
        TwinState predicted = new TwinState(1, 30.0, 120.0, 100, 45, 10, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);

        assertThat(result.sysid).isEqualTo(1);
        assertThat(result.positionErrorMeters).isEqualTo(0.0);
        assertThat(result.velocityError).isEqualTo(0.0);
        assertThat(result.headingError).isEqualTo(0.0);
    }

    @Test
    @DisplayName("compare 位置误差随纬度差增大")
    void comparePositionErrorScalesWithLatitudeDiff() {
        TwinState actual = new TwinState(1, 30.0, 120.0, 100, 0, 10, 80, 0, 0);
        TwinState predicted = new TwinState(1, 30.001, 120.0, 100, 0, 10, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);

        // 0.001° ≈ 111m
        assertThat(result.positionErrorMeters).isGreaterThan(100.0).isLessThan(120.0);
    }

    @Test
    @DisplayName("compare 位置误差随经度差增大")
    void comparePositionErrorScalesWithLongitudeDiff() {
        TwinState actual = new TwinState(1, 30.0, 120.0, 100, 0, 10, 80, 0, 0);
        TwinState predicted = new TwinState(1, 30.0, 120.001, 100, 0, 10, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);

        assertThat(result.positionErrorMeters).isGreaterThan(100.0).isLessThan(120.0);
    }

    @Test
    @DisplayName("compare 速度误差为绝对差")
    void compareVelocityErrorIsAbsoluteDiff() {
        TwinState actual = new TwinState(1, 30.0, 120.0, 100, 0, 15, 80, 0, 0);
        TwinState predicted = new TwinState(1, 30.0, 120.0, 100, 0, 10, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);
        assertThat(result.velocityError).isEqualTo(5.0);
    }

    @Test
    @DisplayName("compare 航向误差为绝对差")
    void compareHeadingErrorIsAbsoluteDiff() {
        TwinState actual = new TwinState(1, 30.0, 120.0, 100, 90, 10, 80, 0, 0);
        TwinState predicted = new TwinState(1, 30.0, 120.0, 100, 45, 10, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);
        assertThat(result.headingError).isEqualTo(45.0);
    }

    @Test
    @DisplayName("compare sysid 取自 actual")
    void compareSysidFromActual() {
        TwinState actual = new TwinState(7, 30.0, 120.0, 100, 0, 10, 80, 0, 0);
        TwinState predicted = new TwinState(9, 30.0, 120.0, 100, 0, 10, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);
        assertThat(result.sysid).isEqualTo(7);
    }

    @Test
    @DisplayName("compare 同时存在位置/速度/航向误差时全部反映")
    void compareAllErrorsCombined() {
        TwinState actual = new TwinState(1, 30.0, 120.0, 100, 0, 10, 80, 0, 0);
        TwinState predicted = new TwinState(1, 30.001, 120.001, 100, 90, 20, 80, 0, 0);

        ComparisonResult result = service.compare(actual, predicted);

        assertThat(result.positionErrorMeters).isGreaterThan(100.0);
        assertThat(result.velocityError).isEqualTo(10.0);
        assertThat(result.headingError).isEqualTo(90.0);
    }
}
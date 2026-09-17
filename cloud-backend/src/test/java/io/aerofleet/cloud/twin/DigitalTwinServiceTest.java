package io.aerofleet.cloud.twin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DigitalTwinService 数字孪生管理单测（M13）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖同步/查询/漂移计算。
 */
@DisplayName("DigitalTwinService 数字孪生管理 (M13)")
class DigitalTwinServiceTest {

    private DigitalTwinService service;

    @BeforeEach
    void setUp() {
        service = new DigitalTwinService();
    }

    @Test
    @DisplayName("syncTwin 首次同步后 getTwin 返回状态")
    void syncTwinStoresState() {
        service.syncTwin(1, 30.0, 120.0, 100.0, 45.0, 10.0, 80.0);

        TwinState state = service.getTwin(1);
        assertThat(state).isNotNull();
        assertThat(state.sysid).isEqualTo(1);
        assertThat(state.lat).isEqualTo(30.0);
        assertThat(state.lon).isEqualTo(120.0);
        assertThat(state.alt).isEqualTo(100.0);
        assertThat(state.heading).isEqualTo(45.0);
        assertThat(state.velocity).isEqualTo(10.0);
        assertThat(state.battery).isEqualTo(80.0);
        assertThat(state.syncTimestamp).isPositive();
    }

    @Test
    @DisplayName("syncTwin 首次同步漂移为 0")
    void syncTwinFirstTimeZeroDrift() {
        service.syncTwin(1, 30.0, 120.0, 100.0, 45.0, 10.0, 80.0);

        TwinState state = service.getTwin(1);
        assertThat(state.driftMeters).isEqualTo(0.0);
    }

    @Test
    @DisplayName("syncTwin 二次同步计算位置漂移（米）")
    void syncTwinCalculatesDriftOnUpdate() {
        service.syncTwin(1, 30.0, 120.0, 100.0, 45.0, 10.0, 80.0);
        // 移动 0.001° 纬度 ≈ 111m
        service.syncTwin(1, 30.001, 120.0, 100.0, 45.0, 10.0, 80.0);

        TwinState state = service.getTwin(1);
        assertThat(state.driftMeters).isGreaterThan(100.0).isLessThan(120.0);
    }

    @Test
    @DisplayName("getTwin 未同步的 sysid 返回 null")
    void getTwinReturnsNullForUnknown() {
        assertThat(service.getTwin(99)).isNull();
    }

    @Test
    @DisplayName("getAllTwins 初始为空，同步后包含所有孪生")
    void getAllTwinsTracksAll() {
        assertThat(service.getAllTwins()).isEmpty();

        service.syncTwin(1, 30.0, 120.0, 100.0, 45.0, 10.0, 80.0);
        service.syncTwin(2, 31.0, 121.0, 200.0, 90.0, 15.0, 60.0);

        Collection<TwinState> all = service.getAllTwins();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(t -> t.sysid).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("syncTwin 同一 sysid 多次同步只保留最新状态")
    void syncTwinOverwritesPreviousState() {
        service.syncTwin(1, 30.0, 120.0, 100.0, 45.0, 10.0, 80.0);
        service.syncTwin(1, 31.0, 121.0, 200.0, 90.0, 15.0, 60.0);

        TwinState state = service.getTwin(1);
        assertThat(state.lat).isEqualTo(31.0);
        assertThat(state.lon).isEqualTo(121.0);
        assertThat(state.battery).isEqualTo(60.0);
        assertThat(service.getAllTwins()).hasSize(1);
    }

    @Test
    @DisplayName("syncTwin 经度漂移正确计算")
    void syncTwinLongitudeDrift() {
        service.syncTwin(1, 30.0, 120.0, 100.0, 45.0, 10.0, 80.0);
        // 仅经度变化 0.001° ≈ 111m * cos(30°) ≈ 96m
        service.syncTwin(1, 30.0, 120.001, 100.0, 45.0, 10.0, 80.0);

        TwinState state = service.getTwin(1);
        // 漂移 = sqrt(0 + 0.001^2) * 111000 ≈ 111m
        assertThat(state.driftMeters).isGreaterThan(100.0).isLessThan(120.0);
    }
}
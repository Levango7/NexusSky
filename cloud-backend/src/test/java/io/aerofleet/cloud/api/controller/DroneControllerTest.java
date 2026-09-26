package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DroneController REST 端点单测（机队列表 / 详情 / 遥测 / 航迹）。
 * <p>
 * 直接实例化 Controller（无 MockMvc / Spring 上下文）。DroneCommandService 和
 * FlightLogService 传 null：本测试只覆盖不涉及命令执行与日志写入的只读端点。
 */
@DisplayName("DroneController 机队只读端点")
class DroneControllerTest {

    /** 构造 Controller：commands 和 flightLog 传 null（只读端点不触及它们）。 */
    private DroneController newController(DeviceRegistry registry) {
        return new DroneController(registry, null, null);
    }

    @Test
    @DisplayName("空注册表 listDrones 返回空列表")
    void listDrones_empty_returnsEmptyList() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneController controller = newController(registry);

        List<Map<String, Object>> result = controller.listDrones();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("注册无人机后 listDrones 返回非空列表")
    void listDrones_withRegistered_returnsNonEmpty() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = newController(registry);

        List<Map<String, Object>> result = controller.listDrones();

        assertThat(result).isNotEmpty();
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getDrone 未知 sysid 抛 NotFoundException")
    void getDrone_unknownSysid_throwsNotFound() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneController controller = newController(registry);

        assertThatThrownBy(() -> controller.getDrone(99))
                .isInstanceOf(ApiExceptionHandler.NotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("getDrone 已注册 sysid 返回详情 Map")
    void getDrone_knownSysid_returnsDetail() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = newController(registry);

        Map<String, Object> result = controller.getDrone(1);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("sysid");
        assertThat(result.get("sysid")).isEqualTo(1);
    }

    @Test
    @DisplayName("getTelemetry 未知 sysid 抛 NotFoundException")
    void getTelemetry_unknownSysid_throwsNotFound() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneController controller = newController(registry);

        assertThatThrownBy(() -> controller.getTelemetry(42))
                .isInstanceOf(ApiExceptionHandler.NotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    @DisplayName("getTrack 未知 sysid 抛 NotFoundException")
    void getTrack_unknownSysid_throwsNotFound() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneController controller = newController(registry);

        assertThatThrownBy(() -> controller.getTrack(77))
                .isInstanceOf(ApiExceptionHandler.NotFoundException.class)
                .hasMessageContaining("77");
    }

    @Test
    @DisplayName("getTelemetry 已注册 sysid 返回遥测 Map")
    void getTelemetry_knownSysid_returnsTelemetry() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = newController(registry);

        Map<String, Object> result = controller.getTelemetry(1);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("sysid");
        assertThat(result.get("sysid")).isEqualTo(1);
    }
}
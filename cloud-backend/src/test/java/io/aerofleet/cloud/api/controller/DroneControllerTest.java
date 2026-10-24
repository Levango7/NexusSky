package io.aerofleet.cloud.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import io.aerofleet.cloud.flightlog.FlightLogService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /** 构造任务 body：合法航点字段 + 可覆盖的 lat/lon/alt。 */
    private static JsonNode missionBody(double lat, double lon, double alt) throws Exception {
        return new ObjectMapper().readTree(String.format(
                "{\"items\":[{\"cmd\":\"waypoint\",\"lat\":%s,\"lon\":%s,\"alt\":%s,\"holdTime\":0}]}",
                lat, lon, alt));
    }

    @Test
    @DisplayName("uploadMission 非法纬度(>90) 抛 BadRequestException")
    void uploadMission_latAbove90_throwsBadRequest() throws Exception {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = newController(registry);
        JsonNode body = missionBody(95.0, 113.9345, 30);

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("lat must be in [-90, 90]");
    }

    @Test
    @DisplayName("uploadMission 非法经度(<-180) 抛 BadRequestException")
    void uploadMission_lonBelowMinus180_throwsBadRequest() throws Exception {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = newController(registry);
        JsonNode body = missionBody(22.5907, -200.0, 30);

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("lon must be in [-180, 180]");
    }

    @Test
    @DisplayName("uploadMission 非法高度(<0) 抛 BadRequestException")
    void uploadMission_negativeAlt_throwsBadRequest() throws Exception {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = newController(registry);
        JsonNode body = missionBody(22.5907, 113.9345, -10);

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("alt must be >= 0");
    }

    // ── 下面五条覆盖 uploadMission 里另外五个校验点。
    // 此前 lat/lon/alt 三条有测试，另五个（cmd 非空、items 缺失、items 空数组、
    // 1000 条上限、holdTime 非负）一条测试都没有 —— 逐条变异确认过「0 杀」。
    // 其中 holdTime 更严重：MissionItemRequest 上声明了 @Min(0)，但 parseMissionItems
    // 手工校验时唯独漏了它，负值能一路走到 MAVLink ITEM_INT 编码。已修生产代码。

    /** 构造 controller（带一个已注册的 sysid=1）。 */
    private DroneController controllerForSysid1() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        return newController(registry);
    }

    /** 构造任意 items 的 body：itemsJson 是 items 数组的原文。 */
    @Test
    @DisplayName("land 命令被接受并发出 MAV_CMD_NAV_LAND(21)（此前落到 default 抛 BadRequest）")
    void landCommandAccepted() throws Exception {
        // 背景（2026-10-24）：/drones/{sysid}/commands 的 switch 里此前只有
        // arm/disarm/start_mission/rtl/takeoff/raw，**没有 land**；而
        // /roc/seats/{id}/commands 是有的。同一个动作两条路径两种支持度，
        // 第三方走 REST 想"降落"只能自己填 raw 的 cmd=21。
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneCommandService commands = mock(DroneCommandService.class);
        FlightLogService flightLog = mock(FlightLogService.class);
        when(commands.command(eq(1), eq(21), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(0);

        DroneController controller = new DroneController(registry, commands, flightLog);
        JsonNode body = new ObjectMapper().readTree("{\"type\":\"land\"}");

        Map<String, Object> resp = controller.sendCommand(1, body);

        assertThat(resp.get("status")).isEqualTo("ok");
        // 关键：不是"没抛错"就算过，要确认真的把 21 发出去了
        verify(commands).command(eq(1), eq(21), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisplayName("未支持的命令类型仍然抛 BadRequest（land 之外不得被静默接受）")
    void unknownCommandStillRejected() throws Exception {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        DroneController controller = new DroneController(registry,
                mock(DroneCommandService.class), mock(FlightLogService.class));
        JsonNode body = new ObjectMapper().readTree("{\"type\":\"self_destruct\"}");

        assertThatThrownBy(() -> controller.sendCommand(1, body))
                .hasMessageContaining("unsupported command type");
    }

    private static JsonNode rawBody(String itemsJson) throws Exception {
        return new ObjectMapper().readTree("{\"items\":" + itemsJson + "}");
    }

    @Test
    @DisplayName("uploadMission 负 holdTime 抛 BadRequestException（record 声明 @Min(0) 但解析曾漏检）")
    void uploadMission_negativeHoldTime_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        JsonNode body = rawBody(
                "[{\"cmd\":\"waypoint\",\"lat\":22.59,\"lon\":113.93,\"alt\":30,\"holdTime\":-1}]");

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("holdTime must be >= 0");
    }

    @Test
    @DisplayName("uploadMission 缺 items 字段抛 BadRequestException")
    void uploadMission_missingItems_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        JsonNode body = new ObjectMapper().readTree("{\"seq\":1}");

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("non-empty 'items' array");
    }

    @Test
    @DisplayName("uploadMission 空 items 数组抛 BadRequestException")
    void uploadMission_emptyItems_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        JsonNode body = rawBody("[]");

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("non-empty 'items' array");
    }

    @Test
    @DisplayName("uploadMission items 不是数组抛 BadRequestException")
    void uploadMission_itemsNotArray_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        JsonNode body = rawBody("\"waypoint\"");

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("non-empty 'items' array");
    }

    @Test
    @DisplayName("uploadMission 条目缺 cmd 字段抛 BadRequestException")
    void uploadMission_itemWithoutCmd_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        JsonNode body = rawBody("[{\"lat\":22.59,\"lon\":113.93,\"alt\":30,\"holdTime\":0}]");

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("each item needs a 'cmd' field");
    }

    @Test
    @DisplayName("uploadMission 条目 cmd 为空白串抛 BadRequestException（isBlank 不只是 isEmpty）")
    void uploadMission_itemWithBlankCmd_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        JsonNode body = rawBody("[{\"cmd\":\"   \",\"lat\":22.59,\"lon\":113.93,\"alt\":30,\"holdTime\":0}]");

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("each item needs a 'cmd' field");
    }

    @Test
    @DisplayName("uploadMission 超过 1000 条抛 BadRequestException（防超大 payload 耗尽内存）")
    void uploadMission_tooManyItems_throwsBadRequest() throws Exception {
        DroneController controller = controllerForSysid1();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1001; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"cmd\":\"waypoint\",\"lat\":22.59,\"lon\":113.93,\"alt\":30,\"holdTime\":0}");
        }
        sb.append(']');
        JsonNode body = rawBody(sb.toString());

        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .isInstanceOf(ApiExceptionHandler.BadRequestException.class)
                .hasMessageContaining("exceeds maximum of 1000");
    }

    @Test
    @DisplayName("uploadMission 恰好 1000 条不触发上限检查（边界闭合）")
    void uploadMission_exactly1000Items_passesSizeCap() throws Exception {
        DroneController controller = controllerForSysid1();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"cmd\":\"waypoint\",\"lat\":22.59,\"lon\":113.93,\"alt\":30,\"holdTime\":0}");
        }
        sb.append(']');
        JsonNode body = rawBody(sb.toString());

        // 边界应当越过上限检查、继续往下走；后面要么成功要么因下游（未连真机）失败，
        // 但**不能**是 "exceeds maximum of 1000"。
        assertThatThrownBy(() -> controller.uploadMission(1, body))
                .satisfies(t -> assertThat(t.getMessage() == null ? "" : t.getMessage())
                        .doesNotContain("exceeds maximum of 1000"));
    }
}
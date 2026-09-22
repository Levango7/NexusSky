package io.aerofleet.cloud.autodispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.tracking.FlightTrackStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AutoDispatchController} REST 端点测试（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 使用 MockMvc standaloneSetup 风格，不启动完整 Spring 上下文。
 * 验证触发/历史/活跃/中止/配置端点的状态码与响应体。
 */
@DisplayName("AutoDispatchController REST 端点 (P0-1)")
class AutoDispatchControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeviceRegistry registry;
    private FlightTrackStore trackStore;
    private AutoDispatchConfig config;
    private AutoDispatchService service;
    private AutoDispatchController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        trackStore = new FlightTrackStore();
        config = new AutoDispatchConfig();
        config.setEnabled(true);
        service = new AutoDispatchService(registry, trackStore, config);
        controller = new AutoDispatchController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    private void registerDrone(int sysid, double lat, double lon, int batteryPct) {
        DroneSnapshot drone = registry.registerIfAbsent(sysid);
        drone.online = true;
        drone.lat = lat;
        drone.lon = lon;
        drone.battery = batteryPct;
        drone.armed = false;
    }

    private static Map<String, Object> triggerBody(double lat, double lon, String alarmId, int droneCount) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("lat", lat);
        b.put("lon", lon);
        b.put("alarmId", alarmId);
        b.put("droneCount", droneCount);
        return b;
    }

    @Test
    @DisplayName("POST /trigger 有可用无人机时返回 SUCCESS")
    void triggerWithAvailableDroneReturnsSuccess() throws Exception {
        registerDrone(1, 39.901, 116.301, 80);

        mockMvc.perform(post("/api/v1/autodispatch/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(triggerBody(39.9, 116.3, "alarm-1", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.dispatchedDrones[0].sysid").value(1))
                .andExpect(jsonPath("$.dispatchedDrones[0].taskAssigned").value(true))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /trigger 无可用无人机时返回 NO_DRONE")
    void triggerNoDroneReturnsNoDrone() throws Exception {
        mockMvc.perform(post("/api/v1/autodispatch/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(triggerBody(39.9, 116.3, "alarm-1", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DRONE"))
                .andExpect(jsonPath("$.dispatchedDrones").isEmpty());
    }

    @Test
    @DisplayName("POST /trigger 部分可用时返回 PARTIAL")
    void triggerPartialReturnsPartial() throws Exception {
        registerDrone(1, 39.901, 116.301, 80);

        mockMvc.perform(post("/api/v1/autodispatch/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(triggerBody(39.9, 116.3, "alarm-1", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.dispatchedDrones.length()").value(1));
    }

    @Test
    @DisplayName("GET /history 返回出警历史列表")
    void historyReturnsRecords() throws Exception {
        registerDrone(1, 39.901, 116.301, 80);
        mockMvc.perform(post("/api/v1/autodispatch/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(triggerBody(39.9, 116.3, "alarm-1", 1))));

        mockMvc.perform(get("/api/v1/autodispatch/history").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].alarmId").value("alarm-1"));
    }

    @Test
    @DisplayName("GET /active 返回进行中的出警任务")
    void activeReturnsActiveRecords() throws Exception {
        registerDrone(1, 39.901, 116.301, 80);
        mockMvc.perform(post("/api/v1/autodispatch/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(triggerBody(39.9, 116.3, "alarm-1", 1))));

        mockMvc.perform(get("/api/v1/autodispatch/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("GET /active 无活跃任务时返回空列表")
    void activeEmptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/autodispatch/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("POST /{dispatchId}/abort 中止存在的出警任务返回 200")
    void abortExistingDispatchReturns200() throws Exception {
        registerDrone(1, 39.901, 116.301, 80);
        String response = mockMvc.perform(post("/api/v1/autodispatch/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(triggerBody(39.9, 116.3, "alarm-1", 1))))
                .andReturn().getResponse().getContentAsString();
        String dispatchId = MAPPER.readTree(response).get("dispatchId").asText();

        mockMvc.perform(post("/api/v1/autodispatch/" + dispatchId + "/abort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.abortTime").exists());
    }

    @Test
    @DisplayName("POST /{dispatchId}/abort 不存在时返回 404")
    void abortNonExistentReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/autodispatch/nonexistent/abort"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /config 返回当前配置")
    void getConfigReturnsCurrentConfig() throws Exception {
        mockMvc.perform(get("/api/v1/autodispatch/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.minBatteryPct").value(30))
                .andExpect(jsonPath("$.maxDispatchDistanceM").value(10000))
                .andExpect(jsonPath("$.defaultDroneCount").value(1))
                .andExpect(jsonPath("$.hoverAltitudeM").value(50))
                .andExpect(jsonPath("$.hoverDurationSec").value(300));
    }

    @Test
    @DisplayName("PUT /config 更新配置字段")
    void updateConfigChangesFields() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", false);
        body.put("minBatteryPct", 50);
        body.put("maxDispatchDistanceM", 5000);
        body.put("defaultDroneCount", 3);
        body.put("hoverAltitudeM", 80);
        body.put("hoverDurationSec", 600);

        mockMvc.perform(put("/api/v1/autodispatch/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.minBatteryPct").value(50))
                .andExpect(jsonPath("$.maxDispatchDistanceM").value(5000))
                .andExpect(jsonPath("$.defaultDroneCount").value(3))
                .andExpect(jsonPath("$.hoverAltitudeM").value(80))
                .andExpect(jsonPath("$.hoverDurationSec").value(600));
    }

    @Test
    @DisplayName("PUT /config 部分字段更新保留其他字段原值")
    void updateConfigPartialUpdate() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", false);
        // 仅提供 enabled，其他字段保留原值

        mockMvc.perform(put("/api/v1/autodispatch/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.minBatteryPct").value(30))
                .andExpect(jsonPath("$.maxDispatchDistanceM").value(10000));
    }

    @Test
    @DisplayName("POST /trigger 未提供 alarmId 时使用默认值")
    void triggerWithoutAlarmIdUsesDefault() throws Exception {
        registerDrone(1, 39.901, 116.301, 80);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lat", 39.9);
        body.put("lon", 116.3);
        body.put("droneCount", 1);

        mockMvc.perform(post("/api/v1/autodispatch/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
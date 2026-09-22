package io.aerofleet.cloud.health;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthController} REST 端点单测（P1-2 健康管理）。
 * <p>
 * 使用 MockMvc standaloneSetup（无 Spring 上下文），验证端点响应与状态码。
 */
@DisplayName("HealthController REST 端点")
class HealthControllerTest {

    private DeviceRegistry registry;
    private HealthMonitorService monitor;
    private HealthController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        monitor = new HealthMonitorService(registry);
        controller = new HealthController(monitor, registry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private TelemetrySnapshot healthySnapshot() {
        TelemetrySnapshot t = new TelemetrySnapshot(System.currentTimeMillis());
        t.setBatteryPct(80);
        t.setMotorRpms(Arrays.asList(5000.0, 5000.0, 5000.0, 5000.0));
        t.setVibrationG(0.2);
        t.setTemperatureC(40);
        t.setRssiDbm(-50);
        t.setImuDrift(0.1);
        t.setGpsSatellites(12);
        t.setGpsHdop(0.8);
        t.setBatteryCycles(50);
        return t;
    }

    private void registerAndScore(int sysid, TelemetrySnapshot t) {
        registry.registerIfAbsent(sysid);
        monitor.updateScore(monitor.calculateScore(sysid, t));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/health/{sysid}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /{sysid} 返回单机健康评分")
    void getHealth_returnsScore() throws Exception {
        registerAndScore(1, healthySnapshot());
        mockMvc.perform(get("/api/v1/health/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(1))
                .andExpect(jsonPath("$.overallScore").exists())
                .andExpect(jsonPath("$.grade").exists())
                .andExpect(jsonPath("$.componentScores").exists());
    }

    @Test
    @DisplayName("GET /{sysid} 未注册返回 404")
    void getHealth_unregistered_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/health/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{sysid} 已注册但无评分返回 404")
    void getHealth_noScore_returns404() throws Exception {
        registry.registerIfAbsent(1);
        mockMvc.perform(get("/api/v1/health/1"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/health/fleet
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /fleet 返回机队健康总览")
    void getFleetHealth_returnsList() throws Exception {
        registerAndScore(1, healthySnapshot());
        registerAndScore(2, healthySnapshot());
        mockMvc.perform(get("/api/v1/health/fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").exists());
    }

    @Test
    @DisplayName("GET /fleet 无数据返回空数组")
    void getFleetHealth_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/health/fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/health/{sysid}/history
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /{sysid}/history 返回历史列表")
    void getHistory_returnsList() throws Exception {
        registry.registerIfAbsent(1);
        monitor.updateScore(monitor.calculateScore(1, healthySnapshot()));
        monitor.updateScore(monitor.calculateScore(1, healthySnapshot()));
        mockMvc.perform(get("/api/v1/health/1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sysid").value(1));
    }

    @Test
    @DisplayName("GET /{sysid}/history 未注册返回 404")
    void getHistory_unregistered_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/health/99/history"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/health/{sysid}/components/{component}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /{sysid}/components/BATTERY 返回部件详情")
    void getComponent_returnsDetail() throws Exception {
        registerAndScore(1, healthySnapshot());
        mockMvc.perform(get("/api/v1/health/1/components/BATTERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentType").value("BATTERY"))
                .andExpect(jsonPath("$.score").exists())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("GET /{sysid}/components/{component} 未知部件返回 404")
    void getComponent_unknownComponent_returns404() throws Exception {
        registerAndScore(1, healthySnapshot());
        mockMvc.perform(get("/api/v1/health/1/components/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{sysid}/components/{component} 未注册返回 404")
    void getComponent_unregistered_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/health/99/components/BATTERY"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/health/warnings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /warnings 返回告警列表")
    void getWarnings_returnsList() throws Exception {
        TelemetrySnapshot t = healthySnapshot();
        t.setBatteryPct(10); // CRITICAL
        registerAndScore(1, t);
        mockMvc.perform(get("/api/v1/health/warnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].componentType").exists());
    }

    @Test
    @DisplayName("GET /warnings 无告警返回空数组")
    void getWarnings_empty_returnsEmptyArray() throws Exception {
        registerAndScore(1, healthySnapshot());
        mockMvc.perform(get("/api/v1/health/warnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
package io.aerofleet.cloud.scenario;

import io.aerofleet.cloud.api.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ScenarioLaunchController} REST API 单测（MockMvc standaloneSetup）。
 * <p>
 * 直接实例化依赖链（无 Spring 上下文），使用 MockMvc 验证 HTTP 请求/响应。
 * 依赖链：DeviceRegistry + ScenarioLauncherService + ScenarioTemplateController + ScenarioLaunchController
 */
@DisplayName("ScenarioLaunchController REST API (MockMvc)")
class ScenarioLaunchControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeviceRegistry registry;
    private ScenarioLauncherService launcherService;
    private ScenarioTemplateController templateController;
    private ScenarioLaunchController launchController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        launcherService = new ScenarioLauncherService(registry);
        templateController = new ScenarioTemplateController();
        launchController = new ScenarioLaunchController(launcherService, templateController);
        mockMvc = MockMvcBuilders.standaloneSetup(launchController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    /** 注册 N 架在线无人机。 */
    private void registerOnlineDrones(int n) {
        for (int i = 1; i <= n; i++) {
            DroneSnapshot s = registry.registerIfAbsent(i);
            s.online = true;
            s.lastHeartbeatMs = System.currentTimeMillis();
        }
    }

    /** 构造启动请求体。 */
    private static Map<String, Object> launchBody(double lat, double lon) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("lat", lat);
        b.put("lon", lon);
        return b;
    }

    // =====================================================================
    // 启动端点
    // =====================================================================

    @Test
    @DisplayName("POST /launch/{templateId} 启动场景成功")
    void launchScenarioSuccess() throws Exception {
        registerOnlineDrones(5);

        mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-SMALL")
                        .contentType("application/json")
                        .content(json(launchBody(39.9, 116.3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchId").exists())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.planId").isNumber())
                .andExpect(jsonPath("$.assignedDrones").isArray())
                .andExpect(jsonPath("$.estimatedCoveragePct").isNumber());
    }

    @Test
    @DisplayName("POST /launch/{templateId} 无无人机时返回 FAILED")
    void launchScenarioFailedNoDrones() throws Exception {
        mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-SMALL")
                        .contentType("application/json")
                        .content(json(launchBody(39.9, 116.3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.assignedDrones").isArray());
    }

    @Test
    @DisplayName("POST /launch/{templateId} 模板不存在时返回 404")
    void launchScenarioTemplateNotFound() throws Exception {
        mockMvc.perform(post("/api/scenarios/launch/non-existent")
                        .contentType("application/json")
                        .content(json(launchBody(39.9, 116.3))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /launch/{templateId} 带 overrides 覆盖参数")
    void launchScenarioWithOverrides() throws Exception {
        registerOnlineDrones(10);
        Map<String, Object> body = launchBody(39.9, 116.3);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("droneCount", 3);
        body.put("overrides", overrides);

        mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-LARGE")
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.assignedDrones.length()").value(3));
    }

    // =====================================================================
    // 查询端点
    // =====================================================================

    @Test
    @DisplayName("GET /launch/active 查询进行中的场景")
    void getActiveLaunches() throws Exception {
        registerOnlineDrones(10);

        // 启动两个场景
        mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-SMALL")
                .contentType("application/json")
                .content(json(launchBody(39.9, 116.3))));
        mockMvc.perform(post("/api/scenarios/launch/preset-FLOOD-SMALL")
                .contentType("application/json")
                .content(json(launchBody(40.0, 117.0))));

        // 查询进行中
        mockMvc.perform(get("/api/scenarios/launch/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /launch/history 查询历史启动记录")
    void getHistory() throws Exception {
        registerOnlineDrones(10);

        mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-SMALL")
                .contentType("application/json")
                .content(json(launchBody(39.9, 116.3))));

        mockMvc.perform(get("/api/scenarios/launch/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].templateId").value("preset-FIRE-SMALL"));
    }

    @Test
    @DisplayName("GET /launch/{launchId}/status 查询场景执行状态")
    void getStatus() throws Exception {
        registerOnlineDrones(5);
        String response = mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-SMALL")
                        .contentType("application/json")
                        .content(json(launchBody(39.9, 116.3))))
                .andReturn().getResponse().getContentAsString();
        String launchId = MAPPER.readTree(response).get("launchId").asText();

        mockMvc.perform(get("/api/scenarios/launch/" + launchId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchId").value(launchId))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.templateId").value("preset-FIRE-SMALL"));
    }

    @Test
    @DisplayName("GET /launch/{launchId}/status 不存在时返回 404")
    void getStatusNotFound() throws Exception {
        mockMvc.perform(get("/api/scenarios/launch/non-existent/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // =====================================================================
    // 中止端点
    // =====================================================================

    @Test
    @DisplayName("POST /launch/{launchId}/abort 中止进行中的场景")
    void abortRunningLaunch() throws Exception {
        registerOnlineDrones(5);
        String response = mockMvc.perform(post("/api/scenarios/launch/preset-FIRE-SMALL")
                        .contentType("application/json")
                        .content(json(launchBody(39.9, 116.3))))
                .andReturn().getResponse().getContentAsString();
        String launchId = MAPPER.readTree(response).get("launchId").asText();

        mockMvc.perform(post("/api/scenarios/launch/" + launchId + "/abort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchId").value(launchId))
                .andExpect(jsonPath("$.status").value("ABORTED"));

        // 中止后 active 应为空
        mockMvc.perform(get("/api/scenarios/launch/active"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /launch/{launchId}/abort 不存在时返回 404")
    void abortNonExistentReturns404() throws Exception {
        mockMvc.perform(post("/api/scenarios/launch/non-existent/abort"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
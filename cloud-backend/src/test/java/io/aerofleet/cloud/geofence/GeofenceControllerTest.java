package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link GeofenceController} REST API 单测（MockMvc standaloneSetup）。
 * <p>
 * 直接实例化依赖链（无 Spring 上下文），使用 MockMvc 验证 HTTP 请求/响应。
 * 依赖链：GeofenceStore + DeviceRegistry + GeofenceMonitor + GeofenceController
 * <p>
 * 风格与 {@code EmergencyCommandControllerTest} 一致。
 */
@DisplayName("GeofenceController REST API (MockMvc)")
class GeofenceControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeofenceStore store;
    private DeviceRegistry registry;
    private GeofenceMonitor monitor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
        registry = new DeviceRegistry();
        monitor = new GeofenceMonitor(store, registry);
        GeofenceController controller = new GeofenceController(store, monitor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    /** 圆形围栏请求体。 */
    private static Map<String, Object> circleBody(int id, String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("name", name);
        b.put("type", "CIRCLE");
        b.put("centerLat", 22.5);
        b.put("centerLon", 113.9);
        b.put("radiusM", 500.0);
        b.put("action", "WARN");
        return b;
    }

    /** 多边形围栏请求体。 */
    private static Map<String, Object> polygonBody(int id, String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("name", name);
        b.put("type", "POLYGON");
        b.put("points", List.of(
                Map.of("lat", 22.0, "lon", 113.0),
                Map.of("lat", 22.0, "lon", 114.0),
                Map.of("lat", 23.0, "lon", 114.0),
                Map.of("lat", 23.0, "lon", 113.0)));
        b.put("action", "LOCK_RTH");
        return b;
    }

    // =====================================================================
    // 围栏 CRUD
    // =====================================================================

    @Test
    @DisplayName("testCreateAndGetZone: 创建圆形围栏并获取详情")
    void testCreateAndGetZone() throws Exception {
        // POST 创建
        mockMvc.perform(post("/api/v1/geofence/zones")
                        .contentType("application/json")
                        .content(json(circleBody(1, "base-circle"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("base-circle"))
                .andExpect(jsonPath("$.type").value("CIRCLE"))
                .andExpect(jsonPath("$.centerLat").value(22.5))
                .andExpect(jsonPath("$.centerLon").value(113.9))
                .andExpect(jsonPath("$.radiusM").value(500.0))
                .andExpect(jsonPath("$.action").value("WARN"))
                .andExpect(jsonPath("$.enabled").value(true));

        // GET 单个
        mockMvc.perform(get("/api/v1/geofence/zones/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("base-circle"));

        // GET 列表
        mockMvc.perform(get("/api/v1/geofence/zones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(1));

        // 创建多边形围栏
        mockMvc.perform(post("/api/v1/geofence/zones")
                        .contentType("application/json")
                        .content(json(polygonBody(2, "poly-area"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.type").value("POLYGON"))
                .andExpect(jsonPath("$.action").value("LOCK_RTH"))
                .andExpect(jsonPath("$.points[0].lat").value(22.0));

        // 列表应有 2 个
        mockMvc.perform(get("/api/v1/geofence/zones"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("testDeleteZone: 删除围栏 + 404 场景")
    void testDeleteZone() throws Exception {
        // 先创建
        mockMvc.perform(post("/api/v1/geofence/zones")
                .contentType("application/json")
                .content(json(circleBody(1, "to-delete"))));

        // DELETE 删除
        mockMvc.perform(delete("/api/v1/geofence/zones/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.id").value(1));

        // 删除后再获取应 404
        mockMvc.perform(get("/api/v1/geofence/zones/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());

        // 删除不存在的围栏 404
        mockMvc.perform(delete("/api/v1/geofence/zones/999"))
                .andExpect(status().isNotFound());

        // PUT 更新不存在的围栏 404
        mockMvc.perform(put("/api/v1/geofence/zones/999")
                        .contentType("application/json")
                        .content(json(circleBody(999, "nope"))))
                .andExpect(status().isNotFound());
    }

    // =====================================================================
    // 越界历史
    // =====================================================================

    @Test
    @DisplayName("testGetBreaches: 越界历史查询 + sysid/zoneId 过滤")
    void testGetBreaches() throws Exception {
        // 创建围栏
        mockMvc.perform(post("/api/v1/geofence/zones")
                .contentType("application/json")
                .content(json(circleBody(1, "base-circle"))));

        // 通过 monitor 触发越界：sysid=1 在围栏外
        monitor.checkPosition(1, 22.55, 113.95);
        // sysid=2 在围栏外
        monitor.checkPosition(2, 22.6, 114.0);

        // 查询全部越界历史
        mockMvc.perform(get("/api/v1/geofence/breaches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].breachType").value("EXIT"));

        // 按 sysid 过滤
        mockMvc.perform(get("/api/v1/geofence/breaches").param("sysid", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sysid").value(1));

        // 按 zoneId 过滤
        mockMvc.perform(get("/api/v1/geofence/breaches").param("zoneId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        // sysid + zoneId 同时过滤
        mockMvc.perform(get("/api/v1/geofence/breaches")
                        .param("sysid", "2")
                        .param("zoneId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sysid").value(2));
    }

    // =====================================================================
    // 手动检查
    // =====================================================================

    @Test
    @DisplayName("testManualCheck: 手动触发全量检查")
    void testManualCheck() throws Exception {
        // 创建围栏
        mockMvc.perform(post("/api/v1/geofence/zones")
                .contentType("application/json")
                .content(json(circleBody(1, "base-circle"))));

        // 注册在线无人机在围栏外
        DroneSnapshot d = registry.registerIfAbsent(1);
        d.online = true;
        d.lastHeartbeatMs = System.currentTimeMillis();
        d.lat = 22.55;
        d.lon = 113.95;

        // POST /check 手动触发
        mockMvc.perform(post("/api/v1/geofence/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.newEvents[0].sysid").value(1))
                .andExpect(jsonPath("$.newEvents[0].breachType").value("EXIT"))
                .andExpect(jsonPath("$.timestamp").isNumber());

        // 再次检查：状态未变，无新事件
        mockMvc.perform(post("/api/v1/geofence/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // 越界历史应有 1 条
        mockMvc.perform(get("/api/v1/geofence/breaches"))
                .andExpect(jsonPath("$.total").value(1));
    }
}
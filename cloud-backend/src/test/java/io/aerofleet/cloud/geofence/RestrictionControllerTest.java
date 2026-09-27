package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link RestrictionController} REST API 单测（MockMvc standaloneSetup）。
 * <p>
 * 直接实例化依赖链（无 Spring 上下文），使用 MockMvc 验证 HTTP 请求/响应。
 * 依赖链：RestrictionCacheManager + InterceptLogStore + RestrictionController
 * <p>
 * 风格与 {@link GeofenceControllerTest} 一致。
 */
@DisplayName("RestrictionController REST API (MockMvc)")
class RestrictionControllerTest {

    private RestrictionCacheManager cacheManager;
    private InterceptLogStore logStore;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 创建配置：使用 MOCK 数据源
        RestrictionSourceConfig config = new RestrictionSourceConfig();
        config.setSourceType(RestrictionSourceConfig.SourceType.MOCK);
        config.setEnabled(true);

        cacheManager = new RestrictionCacheManager(config);
        // 手动触发 @PostConstruct 逻辑（单元测试中不会自动调用）
        cacheManager.init();

        logStore = new InterceptLogStore();
        RestrictionController controller = new RestrictionController(cacheManager, logStore);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // =====================================================================
    // 限飞区列表
    // =====================================================================

    @Test
    @DisplayName("testGetRestrictionZones: GET /restriction/zones → 返回限飞区列表")
    void testGetRestrictionZones() throws Exception {
        mockMvc.perform(get("/api/v1/geofence/restriction/zones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(3))  // MockRestrictionSource 返回 3 个限飞区
                .andExpect(jsonPath("$.stale").value(false))
                // 验证第一个限飞区（北京首都机场）
                .andExpect(jsonPath("$.items[0].zoneId").value("BJ-PEK-001"))
                .andExpect(jsonPath("$.items[0].name").value("北京首都机场禁飞区"))
                .andExpect(jsonPath("$.items[0].type").value("CIRCLE"))
                .andExpect(jsonPath("$.items[0].fenceType").value("KEEP_OUT"))
                .andExpect(jsonPath("$.items[0].source").value("mock"))
                // 验证第二个限飞区（上海虹桥机场）
                .andExpect(jsonPath("$.items[1].zoneId").value("SH-SHA-001"))
                .andExpect(jsonPath("$.items[1].name").value("上海虹桥机场禁飞区"))
                .andExpect(jsonPath("$.items[1].type").value("CIRCLE"))
                // 验证第三个限飞区（深圳城区多边形）
                .andExpect(jsonPath("$.items[2].zoneId").value("SZ-CITY-001"))
                .andExpect(jsonPath("$.items[2].name").value("深圳城区禁飞区"))
                .andExpect(jsonPath("$.items[2].type").value("POLYGON"));
    }

    // =====================================================================
    // 缓存状态
    // =====================================================================

    @Test
    @DisplayName("testGetRestrictionStatus: GET /restriction/status → 返回缓存状态")
    void testGetRestrictionStatus() throws Exception {
        mockMvc.perform(get("/api/v1/geofence/restriction/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneCount").value(3))
                .andExpect(jsonPath("$.sourceType").value("MOCK"))
                .andExpect(jsonPath("$.sourceId").value("mock"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.connected").value(true));
    }

    // =====================================================================
    // 手动刷新
    // =====================================================================

    @Test
    @DisplayName("testRefreshRestrictionCache: POST /restriction/refresh → 触发刷新返回结果")
    void testRefreshRestrictionCache() throws Exception {
        mockMvc.perform(post("/api/v1/geofence/restriction/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.zoneCount").value(3))
                .andExpect(jsonPath("$.previousCount").value(3))
                .andExpect(jsonPath("$.wasStale").value(false))
                .andExpect(jsonPath("$.nowStale").value(false))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    // =====================================================================
    // 拦截日志查询
    // =====================================================================

    @Test
    @DisplayName("testGetInterceptLogs: GET /intercept/logs → 返回拦截日志")
    void testGetInterceptLogs() throws Exception {
        // 预填充拦截日志
        InterceptLog log1 = new InterceptLog(1, "ARM", 22.5, 113.9,
                InterceptVerdict.Verdict.DENY,
                InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE,
                "{\"zoneId\":20,\"name\":\"keepout-zone\",\"fenceType\":\"KEEP_OUT\"}",
                System.currentTimeMillis());
        InterceptLog log2 = new InterceptLog(2, "TAKEOFF", 23.1, 113.3,
                InterceptVerdict.Verdict.ALLOW,
                null, null,
                System.currentTimeMillis());
        logStore.record(log1);
        logStore.record(log2);

        mockMvc.perform(get("/api/v1/geofence/intercept/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(2))
                // 第一条日志：DENY
                .andExpect(jsonPath("$.items[0].sysid").value(1))
                .andExpect(jsonPath("$.items[0].command").value("ARM"))
                .andExpect(jsonPath("$.items[0].verdict").value("DENY"))
                .andExpect(jsonPath("$.items[0].reason").value("IN_KEEP_OUT_ZONE"))
                .andExpect(jsonPath("$.items[0].zoneInfo").exists())
                // 第二条日志：ALLOW
                .andExpect(jsonPath("$.items[1].sysid").value(2))
                .andExpect(jsonPath("$.items[1].command").value("TAKEOFF"))
                .andExpect(jsonPath("$.items[1].verdict").value("ALLOW"));
    }

    @Test
    @DisplayName("testGetInterceptLogsBySysid: GET /intercept/logs?sysid=1 → 按 sysid 过滤")
    void testGetInterceptLogsBySysid() throws Exception {
        // 预填充拦截日志
        InterceptLog log1 = new InterceptLog(1, "ARM", 22.5, 113.9,
                InterceptVerdict.Verdict.DENY,
                InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE,
                "{\"zoneId\":20,\"name\":\"keepout-zone\",\"fenceType\":\"KEEP_OUT\"}",
                System.currentTimeMillis());
        InterceptLog log2 = new InterceptLog(2, "TAKEOFF", 23.1, 113.3,
                InterceptVerdict.Verdict.ALLOW,
                null, null,
                System.currentTimeMillis());
        InterceptLog log3 = new InterceptLog(1, "LAND", 22.5, 113.9,
                InterceptVerdict.Verdict.ALLOW,
                null, null,
                System.currentTimeMillis());
        logStore.record(log1);
        logStore.record(log2);
        logStore.record(log3);

        // 按 sysid=1 过滤，应返回 2 条
        mockMvc.perform(get("/api/v1/geofence/intercept/logs").param("sysid", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].sysid").value(1))
                .andExpect(jsonPath("$.items[1].sysid").value(1));

        // 按 sysid=2 过滤，应返回 1 条
        mockMvc.perform(get("/api/v1/geofence/intercept/logs").param("sysid", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sysid").value(2));
    }
}
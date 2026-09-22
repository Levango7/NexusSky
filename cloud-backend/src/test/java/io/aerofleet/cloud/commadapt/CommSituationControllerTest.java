package io.aerofleet.cloud.commadapt;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommSituationController 单元测试。
 * <p>
 * 验证通信态势可视化控制器的核心端点：
 * 通信质量查询、链路切换建议、手动切换、故障切换历史、拓扑、配置管理等。
 */
class CommSituationControllerTest {

    private LinkQualityMonitor monitor;
    private AdaptiveRouter router;
    private CommAdaptConfig config;
    private FailoverManager failoverManager;
    private DeviceRegistry registry;
    private CommSituationController controller;

    @BeforeEach
    void setUp() {
        monitor = new LinkQualityMonitor();
        config = new CommAdaptConfig();
        router = new AdaptiveRouter(monitor, config);
        failoverManager = new FailoverManager(monitor, router, config);
        registry = new DeviceRegistry();
        controller = new CommSituationController(monitor, router, failoverManager, config, registry);
    }

    @Test
    @DisplayName("GET /quality/{sysid} 未注册无人机应抛出 NotFoundException")
    void getQuality_unregisteredDrone_shouldThrowNotFound() {
        assertThrows(io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException.class,
                () -> controller.getQuality(999));
    }

    @Test
    @DisplayName("GET /quality/{sysid} 已注册但无数据应抛出 NotFoundException")
    void getQuality_registeredButNoData_shouldThrowNotFound() {
        int sysid = 1;
        registry.registerIfAbsent(sysid);
        assertThrows(io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException.class,
                () -> controller.getQuality(sysid));
    }

    @Test
    @DisplayName("GET /quality/{sysid} 采集后应返回质量评分")
    void getQuality_afterCollect_shouldReturnScore() {
        int sysid = 1;
        registry.registerIfAbsent(sysid);
        monitor.collectQuality(sysid);

        ResponseEntity<Map<String, Object>> response = controller.getQuality(sysid);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(sysid, body.get("sysid"));
        assertNotNull(body.get("overallScore"));
        assertNotNull(body.get("grade"));
        assertNotNull(body.get("bestLinkType"));
        assertNotNull(body.get("details"));
    }

    @Test
    @DisplayName("GET /quality/fleet 应返回机队质量总览")
    void getFleetQuality_shouldReturnFleetOverview() {
        monitor.collectQuality(1);
        monitor.collectQuality(2);

        ResponseEntity<Map<String, Object>> response = controller.getFleetQuality();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.get("total"));
        assertNotNull(body.get("items"));
    }

    @Test
    @DisplayName("GET /recommendations 应返回切换建议列表")
    void getRecommendations_shouldReturnRecommendations() {
        ResponseEntity<Map<String, Object>> response = controller.getRecommendations();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("items"));
        assertNotNull(body.get("total"));
    }

    @Test
    @DisplayName("POST /switch/{sysid} 未注册无人机应抛出 NotFoundException")
    void switchLink_unregisteredDrone_shouldThrowNotFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetLink", "SATELLITE");
        assertThrows(io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException.class,
                () -> controller.switchLink(999, body));
    }

    @Test
    @DisplayName("POST /switch/{sysid} 已注册无人机应返回切换结果")
    void switchLink_registeredDrone_shouldReturnResult() {
        int sysid = 1;
        registry.registerIfAbsent(sysid);
        monitor.collectQuality(sysid);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetLink", "MESH");

        ResponseEntity<Map<String, Object>> response = controller.switchLink(sysid, body);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> resultBody = response.getBody();
        assertNotNull(resultBody);
        assertEquals(sysid, resultBody.get("sysid"));
        assertNotNull(resultBody.get("status"));
        assertNotNull(resultBody.get("fromLink"));
        assertNotNull(resultBody.get("toLink"));
    }

    @Test
    @DisplayName("POST /switch/{sysid} 无效链路类型应抛出 BadRequestException")
    void switchLink_invalidLinkType_shouldThrowException() {
        int sysid = 1;
        registry.registerIfAbsent(sysid);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetLink", "INVALID_LINK");

        // P1-fix: 改为 BadRequestException，返回 HTTP 400 而非 500
        assertThrows(io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException.class,
                () -> controller.switchLink(sysid, body));
    }

    @Test
    @DisplayName("GET /failover/history/{sysid} 未注册无人机应抛出 NotFoundException")
    void getFailoverHistory_unregisteredDrone_shouldThrowNotFound() {
        assertThrows(io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException.class,
                () -> controller.getFailoverHistory(999));
    }

    @Test
    @DisplayName("GET /failover/history/{sysid} 已注册无人机应返回历史列表")
    void getFailoverHistory_registeredDrone_shouldReturnHistory() {
        int sysid = 1;
        registry.registerIfAbsent(sysid);

        ResponseEntity<Map<String, Object>> response = controller.getFailoverHistory(sysid);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("total"));
    }

    @Test
    @DisplayName("GET /topology 应返回通信拓扑信息")
    void getTopology_shouldReturnTopology() {
        ResponseEntity<Map<String, Object>> response = controller.getTopology();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("MESH"));
        assertNotNull(body.get("SATELLITE"));
        assertNotNull(body.get("CELLULAR"));
        assertNotNull(body.get("failedDrones"));
        assertNotNull(body.get("failedCount"));
    }

    @Test
    @DisplayName("GET /config 应返回当前配置")
    void getConfig_shouldReturnCurrentConfig() {
        ResponseEntity<Map<String, Object>> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(60, body.get("switchThreshold"));
        assertEquals(40, body.get("failoverThreshold"));
        assertEquals(5000L, body.get("detectionIntervalMs"));
        assertEquals(true, body.get("autoSwitchEnabled"));
        assertEquals(10000L, body.get("minStableTimeMs"));
    }

    @Test
    @DisplayName("PUT /config 应更新配置字段")
    void updateConfig_shouldUpdateFields() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("switchThreshold", 70);
        body.put("failoverThreshold", 50);
        body.put("autoSwitchEnabled", false);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> resultBody = response.getBody();
        assertNotNull(resultBody);
        assertEquals(70, resultBody.get("switchThreshold"));
        assertEquals(50, resultBody.get("failoverThreshold"));
        assertEquals(false, resultBody.get("autoSwitchEnabled"));
        // 未提供的字段应保留原值
        assertEquals(5000L, resultBody.get("detectionIntervalMs"));
        assertEquals(10000L, resultBody.get("minStableTimeMs"));
    }

    @Test
    @DisplayName("PUT /config 部分更新应保留未提供字段的原值")
    void updateConfig_partialUpdate_shouldKeepOriginalValues() {
        // 只更新一个字段
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("switchThreshold", 80);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<String, Object> resultBody = response.getBody();
        assertNotNull(resultBody);
        assertEquals(80, resultBody.get("switchThreshold"));
        // 其他字段保留原值
        assertEquals(40, resultBody.get("failoverThreshold"));
        assertEquals(5000L, resultBody.get("detectionIntervalMs"));
        assertEquals(true, resultBody.get("autoSwitchEnabled"));
    }
}
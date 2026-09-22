package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.DisasterCommService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DisasterCommController REST 端点单测（P2 灾害应急通讯组网扩展）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），验证端点逻辑与状态码。
 */
@DisplayName("DisasterCommController REST 端点 (P2 灾害通信监控)")
class DisasterCommControllerTest {

    private DisasterCommService service;
    private DisasterCommController controller;

    @BeforeEach
    void setUp() {
        service = new DisasterCommService(null);
        controller = new DisasterCommController(service);
    }

    @Test
    @DisplayName("GET /status 初始状态返回 inactive")
    void getStatusInitialInactive() {
        ResponseEntity<Map<String, Object>> resp = controller.getStatus();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("mode")).isEqualTo("inactive");
        assertThat(body.get("triggerReason")).isEqualTo("");
        assertThat(body.get("affectedNodes")).isEqualTo(0);
    }

    @Test
    @DisplayName("POST /activate 激活灾害模式返回 active")
    void activateReturnsActive() {
        ResponseEntity<Map<String, Object>> resp = controller.activate();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("mode")).isEqualTo("active");
        assertThat(body.get("triggerReason")).isEqualTo("manual");
        assertThat(body.get("message")).isEqualTo("灾害模式已激活");
    }

    @Test
    @DisplayName("POST /deactivate 退出灾害模式返回 inactive")
    void deactivateReturnsInactive() {
        // 先激活
        controller.activate();
        // 再退出
        ResponseEntity<Map<String, Object>> resp = controller.deactivate();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("mode")).isEqualTo("inactive");
        assertThat(body.get("message")).isEqualTo("灾害模式已退出");
    }

    @Test
    @DisplayName("GET /status 激活后返回 active 与 manual 原因")
    void getStatusAfterActivate() {
        controller.activate();
        ResponseEntity<Map<String, Object>> resp = controller.getStatus();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("mode")).isEqualTo("active");
        assertThat(resp.getBody().get("triggerReason")).isEqualTo("manual");
    }

    @Test
    @DisplayName("GET /clusters 初始返回 clusterCount=0")
    void getClustersInitialEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getClusters();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("clusterCount")).isEqualTo(0);
        assertThat((java.util.List<?>) resp.getBody().get("clusters")).isEmpty();
    }

    @Test
    @DisplayName("GET /qos 初始返回 queueCount=0")
    void getQoSInitialEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getQoS();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("queueCount")).isEqualTo(0);
        assertThat((java.util.List<?>) resp.getBody().get("queues")).isEmpty();
    }

    @Test
    @DisplayName("GET /links 初始返回 bridgeCount=0")
    void getLinksInitialEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getLinks();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("bridgeCount")).isEqualTo(0);
        assertThat((java.util.List<?>) resp.getBody().get("bridges")).isEmpty();
    }

    @Test
    @DisplayName("激活→退出→再激活 状态正确切换")
    void activateDeactivateReactivate() {
        // 激活
        controller.activate();
        assertThat(controller.getStatus().getBody().get("mode")).isEqualTo("active");
        // 退出
        controller.deactivate();
        assertThat(controller.getStatus().getBody().get("mode")).isEqualTo("inactive");
        assertThat(controller.getStatus().getBody().get("recoveryRate")).isEqualTo(100);
        // 再激活
        controller.activate();
        assertThat(controller.getStatus().getBody().get("mode")).isEqualTo("active");
    }
}
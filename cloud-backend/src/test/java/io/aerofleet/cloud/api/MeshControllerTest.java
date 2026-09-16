package io.aerofleet.cloud.api;

import io.aerofleet.mavlink.messages.MeshNeighborTableMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MeshController REST 端点单测（M5 应急 mesh，FR-28）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），验证端点逻辑与状态码。
 */
@DisplayName("MeshController REST 端点 (FR-28)")
class MeshControllerTest {

    private MeshTopologyService service;
    private MeshController controller;

    @BeforeEach
    void setUp() {
        service = new MeshTopologyService();
        controller = new MeshController(service);
    }

    private static MeshNeighborTableMsg reportOf(int sysid, int... neighborSysids) {
        List<MeshNeighborTableMsg.NeighborInfo> infos = new java.util.ArrayList<>();
        for (int n : neighborSysids) {
            infos.add(new MeshNeighborTableMsg.NeighborInfo(n, -65, 1));
        }
        return new MeshNeighborTableMsg(sysid, 0L, infos);
    }

    @Test
    @DisplayName("GET /topology 返回所有节点及 nodeCount")
    void getTopologyReturnsAllNodes() {
        service.onNeighborTable(1, reportOf(1, 2));
        service.onNeighborTable(2, reportOf(2, 1));

        ResponseEntity<Map<String, Object>> resp = controller.getTopology();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("nodeCount")).isEqualTo(2);
        assertThat((List<?>) body.get("nodes")).hasSize(2);
    }

    @Test
    @DisplayName("GET /topology 空拓扑返回 nodeCount=0")
    void getTopologyEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getTopology();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("nodeCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /topology/{sysid} 存在时返回 200 与节点视图")
    void getNodeTopologyReturns200() {
        service.onNeighborTable(5, reportOf(5, 6, 7));

        ResponseEntity<Map<String, Object>> resp = controller.getNodeTopology(5);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("sysid")).isEqualTo(5);
        assertThat(resp.getBody().get("neighborCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /topology/{sysid} 不存在时返回 404")
    void getNodeTopologyReturns404ForUnknown() {
        ResponseEntity<Map<String, Object>> resp = controller.getNodeTopology(99);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().get("error").toString()).contains("99");
    }

    @Test
    @DisplayName("GET /routes/{sysid} 返回空路由列表")
    void getRoutesReturnsEmpty() {
        service.onNeighborTable(1, reportOf(1, 2));

        ResponseEntity<Map<String, Object>> resp = controller.getRoutes(1);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("routes")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("GET /routes/{sysid} 不存在时返回 404")
    void getRoutesReturns404ForUnknown() {
        ResponseEntity<Map<String, Object>> resp = controller.getRoutes(88);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /neighbors/{sysid} 返回邻居列表与 neighborCount")
    void getNeighborsReturnsList() {
        service.onNeighborTable(1, reportOf(1, 2, 3, 4));

        ResponseEntity<Map<String, Object>> resp = controller.getNeighbors(1);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("neighborCount")).isEqualTo(3);
        List<?> neighbors = (List<?>) resp.getBody().get("neighbors");
        assertThat(neighbors).hasSize(3);
    }

    @Test
    @DisplayName("GET /neighbors/{sysid} 不存在时返回 404")
    void getNeighborsReturns404ForUnknown() {
        ResponseEntity<Map<String, Object>> resp = controller.getNeighbors(77);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /links 返回合并后的无向链路列表")
    void getLinksReturnsMergedLinks() {
        service.onNeighborTable(1, reportOf(1, 2));
        service.onNeighborTable(2, reportOf(2, 1));

        ResponseEntity<Map<String, Object>> resp = controller.getLinks();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("linkCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /links 空拓扑返回 linkCount=0")
    void getLinksEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getLinks();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("linkCount")).isEqualTo(0);
    }
}
package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.CellTowerTopologyService;
import io.aerofleet.mavlink.messages.CellTowerStatusMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CellTowerController REST 端点单测（M6 移动基站载荷抽象，FR-NFER-OBS-02）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），验证 GET 端点逻辑与状态码。
 * UdpGateway 传 null（GET 端点不使用 gateway）。
 */
@DisplayName("CellTowerController REST 端点 (FR-NFER-OBS-02)")
class CellTowerControllerTest {

    private CellTowerTopologyService service;
    private CellTowerController controller;

    @BeforeEach
    void setUp() {
        service = new CellTowerTopologyService();
        // GET 端点不使用 gateway，传 null 安全
        controller = new CellTowerController(service, null);
    }

    @Test
    @DisplayName("GET /celltowers 返回所有基站及 towerCount")
    void getAllTowersReturnsAll() {
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 399000000, 1163000000, 2000, 50, 25));
        service.onCellTowerStatus(2, new CellTowerStatusMsg(2, 1, 399000000, 1163000000, 500, 30, 60));

        ResponseEntity<Map<String, Object>> resp = controller.getAllTowers();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("towerCount")).isEqualTo(2);
        assertThat((List<?>) body.get("towers")).hasSize(2);
    }

    @Test
    @DisplayName("GET /celltowers 空拓扑返回 towerCount=0")
    void getAllTowersEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getAllTowers();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("towerCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /celltowers/{sysid} 存在时返回 200 与基站视图")
    void getTowerReturns200() {
        service.onCellTowerStatus(5, new CellTowerStatusMsg(5, 2, 399000000, 1163000000, 5000, 100, 10));

        ResponseEntity<Map<String, Object>> resp = controller.getTower(5);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("sysid")).isEqualTo(5);
        assertThat(resp.getBody().get("cellTypeName")).isEqualTo("LORA");
        assertThat(resp.getBody().get("coverageRadiusM")).isEqualTo(5000);
    }

    @Test
    @DisplayName("GET /celltowers/{sysid} 不存在时返回 404")
    void getTowerReturns404ForUnknown() {
        ResponseEntity<Map<String, Object>> resp = controller.getTower(99);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().get("error").toString()).contains("99");
    }

    @Test
    @DisplayName("GET /celltowers/{sysid}/terminals 返回终端列表")
    void getTerminalsReturnsList() {
        // 先上报基站状态
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 0, 0, 2000, 0, 0));
        // 查询终端（初始无终端接入）
        ResponseEntity<Map<String, Object>> resp = controller.getTerminals(1);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("sysid")).isEqualTo(1);
        assertThat(resp.getBody().get("terminalCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /celltowers/handovers 返回漫游历史")
    void getHandoverHistory() {
        ResponseEntity<Map<String, Object>> resp = controller.getHandoverHistory();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("count")).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /celltowers 返回 cellTypeName 正确映射")
    void cellTypeNameMapping() {
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 0, 0, 1000, 0, 0));
        service.onCellTowerStatus(2, new CellTowerStatusMsg(2, 1, 0, 0, 500, 0, 0));
        service.onCellTowerStatus(3, new CellTowerStatusMsg(3, 2, 0, 0, 5000, 0, 0));

        ResponseEntity<Map<String, Object>> resp = controller.getAllTowers();
        List<?> towers = (List<?>) resp.getBody().get("towers");
        assertThat(towers).hasSize(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> tower0 = (Map<String, Object>) towers.get(0);
        assertThat(tower0.get("cellTypeName")).isEqualTo("LTE_MICRO_CELL");
    }
}
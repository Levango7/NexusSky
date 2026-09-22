package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.TerrainMapService;
import io.aerofleet.mavlink.messages.FlightRestrictionMsg;
import io.aerofleet.mavlink.messages.TerrainTypeMapMsg;
import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerrainController REST 端点单测（M8 复杂地形适配，FR-31）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），验证端点逻辑与状态码。
 */
@DisplayName("TerrainController REST 端点 (FR-31)")
class TerrainControllerTest {

    private TerrainMapService service;
    private TerrainController controller;

    @BeforeEach
    void setUp() {
        service = new TerrainMapService();
        controller = new TerrainController(service);
    }

    @Test
    @DisplayName("GET /map 无数据时返回 available=false")
    void getTerrainMapEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getTerrainMap();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("available")).isEqualTo(false);
    }

    @Test
    @DisplayName("GET /map 有数据时返回 available=true 与网格信息")
    void getTerrainMapWithData() {
        List<Integer> cells = List.of(0, 1, 2, 3);
        TerrainTypeMapMsg msg = new TerrainTypeMapMsg(
                300000000, 1200000000, 50, 2, 2, cells);
        service.onTerrainTypeMap(1, msg);

        ResponseEntity<Map<String, Object>> resp = controller.getTerrainMap();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("available")).isEqualTo(true);
        assertThat(resp.getBody().get("mapWidth")).isEqualTo(2);
        assertThat(resp.getBody().get("mapHeight")).isEqualTo(2);
        assertThat((List<?>) resp.getBody().get("gridCells")).hasSize(4);
    }

    @Test
    @DisplayName("GET /restrictions 返回限制区列表")
    void getRestrictionsReturnsList() {
        List<FlightRestrictionMsg.GeoPoint> area = List.of(
                new FlightRestrictionMsg.GeoPoint(300000000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300010000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300005000, 120010000));
        service.onFlightRestriction(1, new FlightRestrictionMsg(0, 0f, area));

        ResponseEntity<Map<String, Object>> resp = controller.getRestrictions();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("count")).isEqualTo(1);
        assertThat((List<?>) resp.getBody().get("restrictions")).hasSize(1);
    }

    @Test
    @DisplayName("GET /changes 返回变更历史分页")
    void getChangesReturnsHistory() {
        List<TerrainUpdateMsg.AffectedCell> cells = List.of(
                new TerrainUpdateMsg.AffectedCell(0, 3));
        service.onTerrainUpdate(1, new TerrainUpdateMsg(1L, 0, cells));

        ResponseEntity<Map<String, Object>> resp = controller.getChanges(0, 50);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("count")).isEqualTo(1);
        assertThat((List<?>) resp.getBody().get("changes")).hasSize(1);
    }

    @Test
    @DisplayName("POST /build 返回 202 accepted")
    void buildTerrainMapAccepted() {
        TerrainController.BuildRequest req = new TerrainController.BuildRequest();
        req.originLat = 30.0;
        req.originLon = 120.0;
        req.widthM = 1000;
        req.heightM = 1000;
        req.gridResolution = 50;

        ResponseEntity<Map<String, Object>> resp = controller.buildTerrainMap(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody().get("status")).isEqualTo("accepted");
    }
}
package io.aerofleet.cloud.api;

import io.aerofleet.mavlink.messages.FlightRestrictionMsg;
import io.aerofleet.mavlink.messages.TerrainTypeMapMsg;
import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerrainMapService 单测（M8 复杂地形适配，FR-31）。
 */
@DisplayName("TerrainMapService 地形图存储 (FR-31)")
class TerrainMapServiceTest {

    private TerrainMapService service;

    @BeforeEach
    void setUp() {
        service = new TerrainMapService();
    }

    @Test
    @DisplayName("onTerrainTypeMap 存储快照，getCurrentMap 返回最新版本")
    void terrainTypeMapStoredAndRetrieved() {
        List<Integer> cells = List.of(0, 1, 2, 3);
        TerrainTypeMapMsg msg = new TerrainTypeMapMsg(
                300000000, 1200000000, 50, 2, 2, cells);
        service.onTerrainTypeMap(1, msg);

        TerrainMapService.TerrainMapSnapshot snapshot = service.getCurrentMap();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.mapWidth()).isEqualTo(2);
        assertThat(snapshot.mapHeight()).isEqualTo(2);
        assertThat(snapshot.originLat()).isEqualTo(30.0);
        assertThat(snapshot.originLon()).isEqualTo(120.0);
        assertThat(snapshot.gridCells()).containsExactly(0, 1, 2, 3);
        assertThat(service.currentVersion()).isGreaterThan(0);
    }

    @Test
    @DisplayName("onTerrainUpdate 追加变更历史，getChangeHistory 分页返回")
    void terrainUpdateAppendedToHistory() {
        List<TerrainUpdateMsg.AffectedCell> cells = List.of(
                new TerrainUpdateMsg.AffectedCell(0, 3),
                new TerrainUpdateMsg.AffectedCell(1, 4));
        TerrainUpdateMsg msg = new TerrainUpdateMsg(1L, 0, cells);
        service.onTerrainUpdate(1, msg);

        List<TerrainMapService.TerrainChangeRecord> history = service.getChangeHistory(0, 50);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).terrainVersion()).isEqualTo(1L);
        assertThat(history.get(0).affectedCells()).hasSize(2);
    }

    @Test
    @DisplayName("onFlightRestriction 累积限制区，getRestrictions 返回全部")
    void flightRestrictionsAccumulated() {
        List<FlightRestrictionMsg.GeoPoint> area = List.of(
                new FlightRestrictionMsg.GeoPoint(300000000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300010000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300005000, 120010000));
        FlightRestrictionMsg msg = new FlightRestrictionMsg(0, 0f, area);
        service.onFlightRestriction(1, msg);

        List<TerrainMapService.FlightRestrictionSnapshot> restrictions = service.getRestrictions();
        assertThat(restrictions).hasSize(1);
        assertThat(restrictions.get(0).restrictionType()).isEqualTo(0);
        assertThat(restrictions.get(0).areaPolygon()).hasSize(3);
    }
}
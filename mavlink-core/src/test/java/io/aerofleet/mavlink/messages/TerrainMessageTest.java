package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 复杂地形适配消息（msgId 462-464）编解码单测（FR-28/29/30）。
 */
@DisplayName("Terrain 消息编解码 (msgId 462-464)")
class TerrainMessageTest {

    // ===== TerrainTypeMapMsg (462) =====

    private static TerrainTypeMapMsg roundtripMap(TerrainTypeMapMsg msg) {
        return TerrainTypeMapMsg.decode(msg.toFrame(1, 1, 0));
    }

    @Test
    @DisplayName("TerrainTypeMap 全字段往返一致")
    void terrainTypeMapRoundtrip() {
        List<Integer> cells = List.of(0, 1, 2, 3, 4, 5);
        TerrainTypeMapMsg orig = new TerrainTypeMapMsg(300000000, 1200000000, 50, 3, 2, cells);
        TerrainTypeMapMsg back = roundtripMap(orig);

        assertThat(back.mapOriginLat).isEqualTo(300000000);
        assertThat(back.mapOriginLon).isEqualTo(1200000000);
        assertThat(back.gridResolution).isEqualTo(50);
        assertThat(back.mapWidth).isEqualTo(3);
        assertThat(back.mapHeight).isEqualTo(2);
        assertThat(back.gridCells).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(back.cellCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("TerrainTypeMap ID=462 / LEN=-1 / CRC_EXTRA=242")
    void terrainTypeMapConstants() {
        assertThat(TerrainTypeMapMsg.ID).isEqualTo(462);
        assertThat(TerrainTypeMapMsg.LEN).isEqualTo(-1);
        assertThat(TerrainTypeMapMsg.CRC_EXTRA).isEqualTo(242);
    }

    @Test
    @DisplayName("TerrainTypeMap encode 长度 = 14 + n")
    void terrainTypeMapEncodeLength() {
        TerrainTypeMapMsg msg = new TerrainTypeMapMsg(0, 0, 10, 2, 2, List.of(0, 1, 2, 3));
        assertThat(msg.encode()).hasSize(14 + 4);
        assertThat(msg.messageId()).isEqualTo(462);
    }

    @Test
    @DisplayName("TerrainTypeMap 空 gridCells 容忍")
    void terrainTypeMapEmptyCells() {
        TerrainTypeMapMsg orig = new TerrainTypeMapMsg(0, 0, 0, 0, 0, List.of());
        TerrainTypeMapMsg back = roundtripMap(orig);
        assertThat(back.gridCells).isEmpty();
        assertThat(back.cellCount()).isZero();
    }

    // ===== TerrainUpdateMsg (463) =====

    private static TerrainUpdateMsg roundtripUpdate(TerrainUpdateMsg msg) {
        return TerrainUpdateMsg.decode(msg.toFrame(1, 1, 0));
    }

    @Test
    @DisplayName("TerrainUpdate 全字段往返一致")
    void terrainUpdateRoundtrip() {
        List<TerrainUpdateMsg.AffectedCell> cells = List.of(
                new TerrainUpdateMsg.AffectedCell(10, 4),
                new TerrainUpdateMsg.AffectedCell(20, 5));
        TerrainUpdateMsg orig = new TerrainUpdateMsg(42L, 1, cells);
        TerrainUpdateMsg back = roundtripUpdate(orig);

        assertThat(back.terrainVersion).isEqualTo(42L);
        assertThat(back.changeReason).isEqualTo(1);
        assertThat(back.affectedCells).hasSize(2);
        assertThat(back.affectedCells.get(0).gridIndex()).isEqualTo(10);
        assertThat(back.affectedCells.get(0).newTerrainType()).isEqualTo(4);
        assertThat(back.affectedCells.get(1).gridIndex()).isEqualTo(20);
        assertThat(back.affectedCells.get(1).newTerrainType()).isEqualTo(5);
        assertThat(back.affectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("TerrainUpdate ID=463 / CRC_EXTRA=243")
    void terrainUpdateConstants() {
        assertThat(TerrainUpdateMsg.ID).isEqualTo(463);
        assertThat(TerrainUpdateMsg.CRC_EXTRA).isEqualTo(243);
    }

    @Test
    @DisplayName("TerrainUpdate encode 长度 = 9 + n×4")
    void terrainUpdateEncodeLength() {
        TerrainUpdateMsg msg = new TerrainUpdateMsg(1L, 0,
                List.of(new TerrainUpdateMsg.AffectedCell(0, 0)));
        assertThat(msg.encode()).hasSize(9 + 4);
    }

    @Test
    @DisplayName("TerrainUpdate 空受影响列表容忍")
    void terrainUpdateEmptyCells() {
        TerrainUpdateMsg orig = new TerrainUpdateMsg(0L, 0, List.of());
        TerrainUpdateMsg back = roundtripUpdate(orig);
        assertThat(back.affectedCells).isEmpty();
        assertThat(back.affectedCount()).isZero();
    }

    // ===== FlightRestrictionMsg (464) =====

    private static FlightRestrictionMsg roundtripRestriction(FlightRestrictionMsg msg) {
        return FlightRestrictionMsg.decode(msg.toFrame(1, 1, 0));
    }

    @Test
    @DisplayName("FlightRestriction 全字段往返一致")
    void flightRestrictionRoundtrip() {
        List<FlightRestrictionMsg.GeoPoint> area = List.of(
                new FlightRestrictionMsg.GeoPoint(300000000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300010000, 1200000000),
                new FlightRestrictionMsg.GeoPoint(300005000, 120010000));
        FlightRestrictionMsg orig = new FlightRestrictionMsg(
                FlightRestrictionMsg.TYPE_ALTITUDE_LIMIT, 120.0f, area);
        FlightRestrictionMsg back = roundtripRestriction(orig);

        assertThat(back.restrictionType).isEqualTo(FlightRestrictionMsg.TYPE_ALTITUDE_LIMIT);
        assertThat(back.limitValue).isEqualTo(120.0f);
        assertThat(back.area).hasSize(3);
        assertThat(back.area.get(0).latE7()).isEqualTo(300000000);
        assertThat(back.area.get(0).lonE7()).isEqualTo(1200000000);
        assertThat(back.vertexCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("FlightRestriction ID=464 / CRC_EXTRA=244")
    void flightRestrictionConstants() {
        assertThat(FlightRestrictionMsg.ID).isEqualTo(464);
        assertThat(FlightRestrictionMsg.CRC_EXTRA).isEqualTo(244);
    }

    @Test
    @DisplayName("FlightRestriction encode 长度 = 8 + n×8")
    void flightRestrictionEncodeLength() {
        FlightRestrictionMsg msg = new FlightRestrictionMsg(
                FlightRestrictionMsg.TYPE_NO_FLY, 0f,
                List.of(new FlightRestrictionMsg.GeoPoint(0, 0)));
        assertThat(msg.encode()).hasSize(8 + 8);
    }

    @Test
    @DisplayName("msgId 462-464 全局唯一：不与 450-461 冲突")
    void msgIdsGloballyUnique() {
        assertThat(TerrainTypeMapMsg.ID).isNotIn(450, 451, 452, 453, 454, 459, 460, 461);
        assertThat(TerrainUpdateMsg.ID).isNotIn(450, 451, 452, 453, 454, 459, 460, 461, 462);
        assertThat(FlightRestrictionMsg.ID).isNotIn(450, 451, 452, 453, 454, 459, 460, 461, 462, 463);
        // 三者互不相同
        assertThat(TerrainTypeMapMsg.ID).isNotEqualTo(TerrainUpdateMsg.ID);
        assertThat(TerrainUpdateMsg.ID).isNotEqualTo(FlightRestrictionMsg.ID);
    }
}
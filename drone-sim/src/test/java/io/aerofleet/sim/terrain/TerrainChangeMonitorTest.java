package io.aerofleet.sim.terrain;

import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import io.aerofleet.sim.TerrainModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerrainChangeMonitor 单测（FR-22~FR-27）。
 */
@DisplayName("TerrainChangeMonitor 灾害变更监视 (FR-22~27)")
class TerrainChangeMonitorTest {

    private TerrainGrid makeGrid() {
        TerrainType[] cells = {
                TerrainType.SUPER_HIGH_RISE, TerrainType.MOUNTAIN, TerrainType.FOREST,
                TerrainType.FLAT, TerrainType.FLAT, TerrainType.FLAT,
                TerrainType.FLAT, TerrainType.FLAT, TerrainType.FLAT,
        };
        return new TerrainGrid(cells, 3, 3, 100, 30.0, 120.0, TerrainModel.flat());
    }

    @Test
    @DisplayName("FR-22 地震：SUPER_HIGH_RISE → OLD_CITY_DENSE（建筑倒塌）")
    void earthquakeCollapsesHighRise() {
        TerrainGrid grid = makeGrid();
        TerrainChangeMonitor monitor = new TerrainChangeMonitor(grid);

        monitor.onChange(ChangeReason.EARTHQUAKE, List.of(0), List.of(TerrainType.SUPER_HIGH_RISE));
        // 索引 0 → gridX=0, gridY=0
        assertThat(grid.typeAtCell(0, 0)).isEqualTo(TerrainType.OLD_CITY_DENSE);
        assertThat(grid.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-23 泥石流：MOUNTAIN → HILL（高程变更）")
    void landslideDegradesMountain() {
        TerrainGrid grid = makeGrid();
        TerrainChangeMonitor monitor = new TerrainChangeMonitor(grid);

        monitor.onChange(ChangeReason.LANDSLIDE, List.of(1), List.of(TerrainType.MOUNTAIN));
        // 索引 1 → gridX=1, gridY=0
        assertThat(grid.typeAtCell(1, 0)).isEqualTo(TerrainType.HILL);
    }

    @Test
    @DisplayName("FR-25 变更通知广播至订阅者")
    void changeBroadcastToSubscribers() {
        TerrainGrid grid = makeGrid();
        TerrainChangeMonitor monitor = new TerrainChangeMonitor(grid);

        List<Long> receivedVersions = new ArrayList<>();
        monitor.subscribe((reason, indices, types, newVersion) -> receivedVersions.add(newVersion));

        monitor.onChange(ChangeReason.FIRE, List.of(2), List.of(TerrainType.FOREST));
        assertThat(receivedVersions).hasSize(1);
        assertThat(receivedVersions.get(0)).isEqualTo(grid.version());
    }

    @Test
    @DisplayName("FR-27 变更消息携带正确原因码")
    void changeMessageHasCorrectReason() {
        TerrainGrid grid = makeGrid();
        TerrainChangeMonitor monitor = new TerrainChangeMonitor(grid);

        TerrainUpdateMsg msg = monitor.onChange(
                ChangeReason.LANDSLIDE, List.of(0), List.of(TerrainType.FLAT));
        assertThat(msg).isNotNull();
        assertThat(msg.changeReason).isEqualTo(ChangeReason.LANDSLIDE.code);
        assertThat(msg.terrainVersion).isEqualTo(grid.version());
    }
}
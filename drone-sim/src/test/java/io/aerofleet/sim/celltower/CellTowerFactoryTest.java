package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CellTowerFactory 单测（M6 移动基站载荷抽象，FR-CT-01/06）。
 */
@DisplayName("CellTowerFactory (FR-CT-01/06)")
class CellTowerFactoryTest {

    @Test
    @DisplayName("create LTE 返回 LteCellTower 实例")
    void createLte() {
        CellTowerPayload tower = CellTowerFactory.create(
                CellType.LTE_MICRO_CELL, 1, 20, 200, 1,
                null, null, -80, 0.8, 30000L);
        assertThat(tower).isInstanceOf(LteCellTower.class);
        assertThat(tower.cellType()).isEqualTo(CellType.LTE_MICRO_CELL);
        assertThat(tower.sysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("create WiFi 返回 WifiCellTower 实例")
    void createWifi() {
        CellTowerPayload tower = CellTowerFactory.create(
                CellType.WIFI_MESH, 2, 15, 100, 6,
                null, null, -75, 0.8, 30000L);
        assertThat(tower).isInstanceOf(WifiCellTower.class);
        assertThat(tower.cellType()).isEqualTo(CellType.WIFI_MESH);
    }

    @Test
    @DisplayName("create LoRa 返回 LoRaCellTower 实例")
    void createLoRa() {
        CellTowerPayload tower = CellTowerFactory.create(
                CellType.LORA, 3, 10, 1000, 0,
                null, null, -90, 0.8, 30000L);
        assertThat(tower).isInstanceOf(LoRaCellTower.class);
        assertThat(tower.cellType()).isEqualTo(CellType.LORA);
    }

    @Test
    @DisplayName("createDefault LTE 返回默认参数实例")
    void createDefaultLte() {
        CellTowerPayload tower = CellTowerFactory.createDefault(
                CellType.LTE_MICRO_CELL, 1, null, null);
        assertThat(tower).isInstanceOf(LteCellTower.class);
        assertThat(tower.sysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("createDefault WiFi 返回默认参数实例")
    void createDefaultWifi() {
        CellTowerPayload tower = CellTowerFactory.createDefault(
                CellType.WIFI_MESH, 2, null, null);
        assertThat(tower).isInstanceOf(WifiCellTower.class);
    }

    @Test
    @DisplayName("createDefault LoRa 返回默认参数实例")
    void createDefaultLoRa() {
        CellTowerPayload tower = CellTowerFactory.createDefault(
                CellType.LORA, 3, null, null);
        assertThat(tower).isInstanceOf(LoRaCellTower.class);
    }
}
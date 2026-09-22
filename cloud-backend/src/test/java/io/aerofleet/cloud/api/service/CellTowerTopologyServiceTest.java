package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.api.dto.CellTowerSnapshot;
import io.aerofleet.mavlink.messages.CellHandoverMsg;
import io.aerofleet.mavlink.messages.CellTowerStatusMsg;
import io.aerofleet.mavlink.messages.GroundTerminalRegisterMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CellTowerTopologyService 单测（M6 移动基站载荷抽象，FR-NFER-OBS-02）。
 */
@DisplayName("CellTowerTopologyService (FR-NFER-OBS-02)")
class CellTowerTopologyServiceTest {

    private CellTowerTopologyService service;

    @BeforeEach
    void setUp() {
        service = new CellTowerTopologyService();
    }

    @Test
    @DisplayName("onCellTowerStatus 更新快照并递增版本")
    void onCellTowerStatusUpdatesSnapshot() {
        CellTowerStatusMsg msg = new CellTowerStatusMsg(1, 0, 399000000, 1163000000, 2000, 50, 25);
        long v0 = service.currentVersion();

        service.onCellTowerStatus(1, msg);

        assertThat(service.currentVersion()).isGreaterThan(v0);
        CellTowerSnapshot snap = service.getSnapshot(1);
        assertThat(snap).isNotNull();
        assertThat(snap.sysid).isEqualTo(1);
        assertThat(snap.cellType).isEqualTo(0);
        assertThat(snap.coverageRadiusM).isEqualTo(2000);
        assertThat(snap.connectedTerminals).isEqualTo(50);
    }

    @Test
    @DisplayName("onTerminalRegister 更新终端注册表")
    void onTerminalRegisterUpdatesRegistry() {
        GroundTerminalRegisterMsg msg = new GroundTerminalRegisterMsg(1001, 0, 399000000, 1163000000, 1);
        service.onTerminalRegister(1, msg);

        Map<Integer, CellTowerSnapshot.TerminalInfo> terminals = service.getAllTerminals();
        assertThat(terminals).containsKey(1001);
        assertThat(terminals.get(1001).terminalType()).isEqualTo(0);
        assertThat(service.registeredTerminalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("onHandover 记录漫游历史并更新终端接入")
    void onHandoverRecordsHistory() {
        // 先注册终端
        GroundTerminalRegisterMsg reg = new GroundTerminalRegisterMsg(1001, 0, 399000000, 1163000000, 1);
        service.onTerminalRegister(1, reg);

        // 执行漫游
        CellHandoverMsg msg = new CellHandoverMsg(1001, 1, 2, 0);
        service.onHandover(1, msg);

        List<CellTowerTopologyService.HandoverEvent> history = service.getHandoverHistory();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).terminalId()).isEqualTo(1001);
        assertThat(history.get(0).fromSysid()).isEqualTo(1);
        assertThat(history.get(0).toSysid()).isEqualTo(2);

        // 终端接入应更新到目标机
        assertThat(service.getAllTerminals().get(1001).connectedSysid()).isEqualTo(2);
    }

    @Test
    @DisplayName("getAllSnapshots 返回按 sysid 排序的快照")
    void getAllSnapshotsSorted() {
        service.onCellTowerStatus(3, new CellTowerStatusMsg(3, 0, 0, 0, 1000, 0, 0));
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 0, 0, 1000, 0, 0));
        service.onCellTowerStatus(2, new CellTowerStatusMsg(2, 0, 0, 0, 1000, 0, 0));

        Map<Integer, CellTowerSnapshot> all = service.getAllSnapshots();
        assertThat(all.keySet()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("detectChanges 首次调用生成 TOWER_ONLINE 事件")
    void detectChangesFirstTime() {
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 0, 0, 1000, 0, 0));
        service.onCellTowerStatus(2, new CellTowerStatusMsg(2, 1, 0, 0, 500, 0, 0));

        List<CellTowerTopologyService.CellTowerEvent> events = service.detectChanges();
        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(e -> e.type() == CellTowerTopologyService.EventType.TOWER_ONLINE && e.fromSysid() == 1);
        assertThat(events).anyMatch(e -> e.type() == CellTowerTopologyService.EventType.TOWER_ONLINE && e.fromSysid() == 2);
    }

    @Test
    @DisplayName("detectChanges 第二次调用无变化时返回空")
    void detectChangesNoChange() {
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 0, 0, 1000, 0, 0));
        service.detectChanges(); // 首次

        List<CellTowerTopologyService.CellTowerEvent> events = service.detectChanges();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("onlineTowerCount 返回在线基站数")
    void onlineTowerCount() {
        service.onCellTowerStatus(1, new CellTowerStatusMsg(1, 0, 0, 0, 1000, 0, 0));
        service.onCellTowerStatus(2, new CellTowerStatusMsg(2, 1, 0, 0, 500, 0, 0));
        assertThat(service.onlineTowerCount()).isEqualTo(2);
    }
}
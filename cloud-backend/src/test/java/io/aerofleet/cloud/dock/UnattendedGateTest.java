package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UnattendedScheduler 门控（spec R4）：前置不满足必须 SKIP，不留下半执行现场。
 * <p>
 * 「托管机离线 → SKIP」是 e2e 实测撞出来的真缺陷：修前会白开门、白等到
 * 飞行超时再 FAILED（门开着、飞机没动）——比"没执行"更糟，现场处于中间态。
 */
@DisplayName("UnattendedScheduler — 前置门控")
class UnattendedGateTest {

    private DockRunLogRepository runLogs;
    private DeviceRegistry registry;
    private UnattendedScheduler scheduler;
    private DockEntity dock;

    @BeforeEach
    void setUp() {
        runLogs = mock(DockRunLogRepository.class);
        registry = mock(DeviceRegistry.class);
        when(runLogs.findByDockIdOrderByStartedAtDesc(anyLong())).thenReturn(List.of());

        scheduler = new UnattendedScheduler(
                mock(DockScheduleRepository.class), runLogs,
                mock(DockRepository.class), mock(DockService.class),
                mock(DroneCommandService.class), registry,
                new DockProperties(), new ObjectMapper());

        dock = new DockEntity();
        dock.id = 1L;
        dock.sn = "DOCK-1";
        dock.state = DockState.IDLE;
        dock.droneSysid = 9;
    }

    private DockScheduleEntity schedule() {
        DockScheduleEntity s = new DockScheduleEntity();
        s.id = 1L;
        s.dockId = 1L;
        s.enabled = true;
        return s;
    }

    @Test
    @DisplayName("机巢不在库 → dock-absent")
    void dockAbsent() {
        assertThat(scheduler.gate(null, schedule())).isEqualTo("dock-absent");
    }

    @Test
    @DisplayName("机巢离线 → dock-offline")
    void dockOffline() {
        dock.state = DockState.OFFLINE;
        assertThat(scheduler.gate(dock, schedule())).isEqualTo("dock-offline");
    }

    @Test
    @DisplayName("机巢过渡态 → state:OPENING")
    void dockTransitional() {
        dock.state = DockState.OPENING;
        assertThat(scheduler.gate(dock, schedule())).isEqualTo("state:OPENING");
    }

    @Test
    @DisplayName("无托管机 → no-docked-drone")
    void noDrone() {
        dock.droneSysid = null;
        assertThat(scheduler.gate(dock, schedule())).isEqualTo("no-docked-drone");
    }

    @Test
    @DisplayName("托管机未注册 → drone-offline（不白开门）")
    void droneUnknown() {
        when(registry.get(9)).thenReturn(null);
        assertThat(scheduler.gate(dock, schedule())).isEqualTo("drone-offline");
    }

    @Test
    @DisplayName("托管机注册但离线 → drone-offline")
    void droneOffline() {
        DroneSnapshot snap = new DroneSnapshot(9);
        snap.online = false;
        when(registry.get(9)).thenReturn(snap);
        assertThat(scheduler.gate(dock, schedule())).isEqualTo("drone-offline");
    }

    @Test
    @DisplayName("有 RUNNING 记录 → busy（同巢并发保护）")
    void busy() {
        DroneSnapshot snap = new DroneSnapshot(9);
        snap.online = true;
        when(registry.get(9)).thenReturn(snap);
        DockRunLogEntity running = new DockRunLogEntity();
        running.result = "RUNNING";
        when(runLogs.findByDockIdOrderByStartedAtDesc(1L)).thenReturn(List.of(running));

        assertThat(scheduler.gate(dock, schedule())).isEqualTo("busy");
    }

    @Test
    @DisplayName("全部满足 → null（可执行）")
    void allGood() {
        DroneSnapshot snap = new DroneSnapshot(9);
        snap.online = true;
        when(registry.get(9)).thenReturn(snap);
        assertThat(scheduler.gate(dock, schedule())).isNull();
    }
}
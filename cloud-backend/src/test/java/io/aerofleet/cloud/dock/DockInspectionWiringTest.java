package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.inspection.InspectionTask;
import io.aerofleet.cloud.inspection.InspectionTaskService;
import io.aerofleet.cloud.inspection.Waypoint;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.common.MissionItemRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 机巢定时任务 ↔ 巡检任务接线（黄金路径硬化第一批）。
 * <p>
 * 旧行为：机巢按 cron 飞完只写 run log；巡检模块独立"模拟完成"，两者不相干。
 * 本测试钉住新接线：带巡检模板的 schedule 必须创建任务、写 run log、启动任务。
 */
@DisplayName("UnattendedScheduler — 巡检任务接线")
class DockInspectionWiringTest {

    private DockRunLogRepository runLogs;
    private InspectionTaskService inspectionTasks;
    private UnattendedScheduler scheduler;
    private DockEntity dock;

    @BeforeEach
    void setUp() {
        runLogs = mock(DockRunLogRepository.class);
        inspectionTasks = mock(InspectionTaskService.class);
        scheduler = new UnattendedScheduler(
                mock(DockScheduleRepository.class), runLogs,
                mock(DockRepository.class), mock(DockService.class),
                mock(DroneCommandService.class), mock(DeviceRegistry.class),
                new DockProperties(), new ObjectMapper(), inspectionTasks);

        dock = new DockEntity();
        dock.id = 1L;
        dock.sn = "DOCK-1";
        dock.droneSysid = 9;
        dock.lat = 39.90;
        dock.lon = 116.40;
    }

    private DockScheduleEntity schedule(String templateId) {
        DockScheduleEntity s = new DockScheduleEntity();
        s.id = 7L;
        s.dockId = 1L;
        s.inspectionTemplateId = templateId;
        s.enabled = true;
        return s;
    }

    private DockRunLogEntity run() {
        DockRunLogEntity r = new DockRunLogEntity();
        r.scheduleId = 7L;
        r.dockId = 1L;
        r.result = "RUNNING";
        return r;
    }

    @Test
    @DisplayName("带巡检模板：创建任务 → 写 run log → 启动任务")
    void createsAndLinksInspectionTask() {
        InspectionTask task = new InspectionTask("task-1", "preset-power-line", 9, List.of());
        when(inspectionTasks.createTask(anyString(), anyInt(), anyDouble(), anyDouble(), isNull()))
                .thenReturn(task);

        DockRunLogEntity r = run();
        String id = scheduler.createInspectionTask(schedule("preset-power-line"), dock, r);

        assertThat(id).isEqualTo("task-1");
        assertThat(r.inspectionTaskId).isEqualTo("task-1");
        verify(runLogs).save(r);
        verify(inspectionTasks).startTask("task-1");
    }

    @Test
    @DisplayName("无巡检模板：走旧 waypoints 路径，不碰巡检服务")
    void noTemplateKeepsLegacyBehavior() throws Exception {
        DockScheduleEntity s = schedule(null);
        s.waypoints = "[[39.900,116.400,80],[39.901,116.401,80]]";

        List<MissionItemRequest> items = scheduler.missionFrom(s, dock);

        assertThat(items).hasSize(4);
        assertThat(items.get(0).cmd()).isEqualTo("takeoff");
        assertThat(items.get(3).cmd()).isEqualTo("rtl");
        verifyNoInteractions(inspectionTasks);
    }

    @Test
    @DisplayName("巡检航线 → takeoff + n 个 waypoint + rtl")
    void missionFromWaypoints() {
        List<Waypoint> wps = List.of(
                Waypoint.fly(0, 39.900, 116.400, 80, 0, 8),
                Waypoint.photo(1, 39.901, 116.401, 80, 0, 8, 2));

        List<MissionItemRequest> items = scheduler.missionFrom(wps);

        assertThat(items).hasSize(4);
        assertThat(items.get(0).cmd()).isEqualTo("takeoff");
        assertThat(items.get(1).cmd()).isEqualTo("waypoint");
        assertThat(items.get(2).cmd()).isEqualTo("waypoint");
        assertThat(items.get(3).cmd()).isEqualTo("rtl");
    }
}

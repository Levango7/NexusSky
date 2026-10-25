package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.ThermalDataMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThermalIngestListener} 契约（P2 #19）。
 *
 * <p>钉住三件事：每帧 THERMAL_DATA 写快照（机队最新热读数）；活跃任务收账为
 * COMPLETED（每帧都是完整分析，PENDING 直接收账）；第二帧只刷新快照、
 * <b>不重开已完成任务</b>（任务结果是收账时刻的快照，不是"最新值"）。
 */
@DisplayName("ThermalIngestListener：THERMAL_DATA(30012) ingest 契约")
class ThermalIngestListenerTest {

    private final DeviceRegistry registry = new DeviceRegistry();
    private final ThermalTaskService tasks = new ThermalTaskService();
    private final ThermalIngestListener listener = new ThermalIngestListener(registry, tasks);

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    /** 发布一帧 sysid=7 的 THERMAL_DATA 事件（tempMax/hotspotCount 可调，其余定值）。 */
    private void publish(float tempMax, int hotspots) {
        ThermalDataMsg msg = new ThermalDataMsg(30.0f, 20.0f, tempMax, 2.5f, 1000L, hotspots, 7);
        listener.onThermalData(new MavlinkMessageEvent(this, 7, ThermalDataMsg.ID, msg, 42L));
    }

    @Test
    @DisplayName("每帧写快照：热像五元组 + 到达时刻落进 DroneSnapshot")
    void snapshotFieldsUpdated() {
        publish(45.0f, 3);

        DroneSnapshot s = registry.all().stream()
                .filter(d -> d.sysid == 7).findFirst().orElseThrow();
        assertThat(s.thermalTempMean).isEqualTo(30.0);
        assertThat(s.thermalTempMin).isEqualTo(20.0);
        assertThat(s.thermalTempMax).isEqualTo(45.0);
        assertThat(s.thermalTempStdDev).isEqualTo(2.5);
        assertThat(s.thermalHotspotCount).isEqualTo(3);
        assertThat(s.thermalUpdatedAtMs).as("到达时刻必须落盘（读端点按它过滤未上报机）").isPositive();
    }

    @Test
    @DisplayName("未知 sysid 的帧：registerIfAbsent 建快照（与 Heartbeat 同口径），不抛错")
    void unknownSysidSnapshotCreated() {
        ThermalDataMsg msg = new ThermalDataMsg(30.0f, 20.0f, 40.0f, 2.0f, 1000L, 1, 99);
        listener.onThermalData(new MavlinkMessageEvent(this, 99, ThermalDataMsg.ID, msg, 42L));

        assertThat(registry.all()).anyMatch(d -> d.sysid == 99 && d.thermalUpdatedAtMs > 0);
    }

    @Test
    @DisplayName("活跃任务收账：PENDING → COMPLETED，结果=帧值（最老的先收）")
    void oldestActiveTaskCompleted() {
        ThermalTaskService.ThermalTask first = tasks.create(7, Map.of(), 50.0);
        ThermalTaskService.ThermalTask second = tasks.create(7, Map.of(), 60.0);

        publish(45.0f, 3);

        assertThat(first.state).as("最老任务先收账").isEqualTo("COMPLETED");
        assertThat(first.tempMax).isEqualTo(45.0);
        assertThat(first.tempMean).isEqualTo(30.0);
        assertThat(first.hotspotCount).isEqualTo(3);
        assertThat(first.completedAt).isPositive();
        assertThat(second.state).as("第二个任务不受这帧影响").isEqualTo("PENDING");

        publish(52.0f, 5);
        assertThat(second.state).isEqualTo("COMPLETED");
        assertThat(second.tempMax).isEqualTo(52.0);
        assertThat(second.hotspotCount).isEqualTo(5);
    }

    @Test
    @DisplayName("无活跃任务：只刷快照不异常（任务表保持为空）")
    void noActiveTaskJustSnapshot() {
        publish(41.0f, 2);

        assertThat(tasks.all()).isEmpty();
        assertThat(registry.all().stream().filter(d -> d.sysid == 7).findFirst().orElseThrow()
                .thermalUpdatedAtMs).isPositive();
    }

    @Test
    @DisplayName("第二帧不重开已完成任务：任务结果停在收账帧，快照跟最新帧走")
    void secondFrameDoesNotReopenCompletedTask() {
        ThermalTaskService.ThermalTask task = tasks.create(7, Map.of(), 50.0);
        publish(45.0f, 3);
        publish(80.0f, 9);

        assertThat(task.state).isEqualTo("COMPLETED");
        assertThat(task.tempMax).as("任务结果是收账时刻的快照，不许被后续帧改写").isEqualTo(45.0);
        assertThat(task.hotspotCount).isEqualTo(3);
        DroneSnapshot s = registry.all().stream().filter(d -> d.sysid == 7).findFirst().orElseThrow();
        assertThat(s.thermalTempMax).as("机队最新读数跟最新帧走").isEqualTo(80.0);
    }
}

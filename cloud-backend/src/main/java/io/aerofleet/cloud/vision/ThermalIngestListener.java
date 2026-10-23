package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.ThermalDataMsg;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * M3 热成像遥测 ingest（P2 #19，2026-10-24 接线）。
 *
 * <p>此前 THERMAL_DATA(30012) 在 cloud-backend 零引用：drone-sim 每秒下传机下视
 * 温度场分析，网关解得出来、事件也发布了，就是没有任何监听器接——热成像任务
 * 永远停在 PENDING，前端面板只能吃 404。本监听器补上最后一环：
 *
 * <ol>
 *   <li>把每帧分析写进 {@link DroneSnapshot}（机队最新热读数，GET /thermal/status 读它）；</li>
 *   <li>收账该 sysid 最老的活跃热成像任务（PENDING → COMPLETED，语义见
 *       {@link ThermalTaskService#completeOldestActive}）。</li>
 * </ol>
 *
 * <p>姿势对齐 {@code TelemetrySnapshotListener}（@EventListener + msgId 条件）；
 * 事件已按 sysid 广播，这里不做租户过滤（快照写入是全量事实，读侧按租户口径过滤）。
 */
@Component
public class ThermalIngestListener {

    private final DeviceRegistry registry;
    private final ThermalTaskService tasks;

    public ThermalIngestListener(DeviceRegistry registry, ThermalTaskService tasks) {
        this.registry = registry;
        this.tasks = tasks;
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ThermalDataMsg).ID")
    public void onThermalData(MavlinkMessageEvent event) {
        ThermalDataMsg msg = (ThermalDataMsg) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(event.getSysid());
        s.thermalTempMean = msg.tempMean;
        s.thermalTempMin = msg.tempMin;
        s.thermalTempMax = msg.tempMax;
        s.thermalTempStdDev = msg.tempStdDev;
        s.thermalHotspotCount = msg.hotspotCount;
        s.thermalUpdatedAtMs = System.currentTimeMillis();
        tasks.completeOldestActive(event.getSysid(), msg.tempMean, msg.tempMin,
                msg.tempMax, msg.tempStdDev, msg.hotspotCount);
    }
}

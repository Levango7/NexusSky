package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.enums.ConflictType;
import io.aerofleet.mavlink.messages.ConflictAlertMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * M10 机队冲突扫描服务（WS_TYPE_MAP 收口 2026-10-05 边界清零）。
 * <p>
 * {@code checkAllConflicts} 的生产调用方：对在线无人机按当前位姿/速度构建 4D 预测航迹，
 * 两两检测冲突，并为每个冲突对发布 ConflictAlertMsg(30049)。
 * <p>
 * 触发路径：
 * <ul>
 *   <li>{@code @Scheduled} 5s 周期扫描（无 WS 客户端时跳过，仿 pusher 范式）</li>
 *   <li>{@code POST /api/v1/scheduling/conflicts/scan} 按需扫描（REST 返回冲突对）</li>
 * </ul>
 * <p>
 * 字段映射口径（协议枚举 vs 4D 检测几何类型）：
 * <ul>
 *   <li>conflictType：HEAD-ON→COLLISION(2)（对头最危险），CROSSING/OVERTAKE→PATH(1)；
 *       AIRSPACE(0) 不可达——空域预约冲突走 {@code checkReservationConflict}，不属机对扫描</li>
 *   <li>severity：按冲突倒计时分档 timeToConflict &lt;10s→4，&lt;30s→3，&lt;60s→2，其余→1</li>
 *   <li>minDistance：冲突点水平距离（m）；timeToConflict：冲突倒计时（s）</li>
 * </ul>
 */
@Service
public class ConflictScanService {
    private static final Logger log = LoggerFactory.getLogger(ConflictScanService.class);

    /** 4D 航迹预测时长（s）。 */
    static final int HORIZON_SEC = 60;
    /** severity 分档阈值（s）。 */
    private static final double SEVERITY_CRITICAL_SEC = 10.0;
    private static final double SEVERITY_MAJOR_SEC = 30.0;
    private static final double SEVERITY_MINOR_SEC = 60.0;

    private final DeviceRegistry registry;
    private final ConflictAvoidanceService conflictService;
    private final ApplicationEventPublisher eventPublisher;
    private final TelemetryWebSocketHandler wsHandler;

    public ConflictScanService(DeviceRegistry registry,
                               ConflictAvoidanceService conflictService,
                               ApplicationEventPublisher eventPublisher,
                               TelemetryWebSocketHandler wsHandler) {
        this.registry = registry;
        this.conflictService = conflictService;
        this.eventPublisher = eventPublisher;
        this.wsHandler = wsHandler;
    }

    /**
     * 周期扫描（5s）：无 WS 客户端时跳过（冲突帧仅经 WS 转发，无人订阅时不做无用功）。
     */
    @Scheduled(fixedDelay = 5000)
    public void scanPeriodic() {
        if (wsHandler.connectionCount() == 0) {
            return;
        }
        try {
            scanOnce();
        } catch (Exception e) {
            log.warn("periodic conflict scan failed: {}", e.getMessage());
        }
    }

    /**
     * 扫描在线机队：构建 4D 预测航迹 → 两两检测 → 逐冲突对发布 30049 帧。
     *
     * @return 冲突对列表（无冲突时为空）
     */
    public List<ConflictAvoidanceService.ConflictPair> scanOnce() {
        List<ConflictAvoidanceService.DroneTrajectory> trajectories = new ArrayList<>();
        for (DroneSnapshot d : registry.all()) {
            if (!d.online) {
                continue;
            }
            if (Double.isNaN(d.lat) || Double.isNaN(d.lon) || Double.isNaN(d.yaw)) {
                continue; // 无导航数据，无法预测
            }
            double alt = !Double.isNaN(d.amslAlt) ? d.amslAlt
                    : (!Double.isNaN(d.relativeAlt) ? d.relativeAlt : 0.0);
            double speed = !Double.isNaN(d.groundspeed) ? d.groundspeed
                    : (!Double.isNaN(d.vx) && !Double.isNaN(d.vy) ? Math.hypot(d.vx, d.vy) : Double.NaN);
            if (Double.isNaN(speed)) {
                continue;
            }
            trajectories.add(new ConflictAvoidanceService.DroneTrajectory(
                    d.sysid,
                    conflictService.predictTrajectory4D(d.lat, d.lon, alt, speed, d.yaw, HORIZON_SEC)));
        }
        List<ConflictAvoidanceService.ConflictPair> pairs =
                conflictService.scanAllConflicts(trajectories);
        for (ConflictAvoidanceService.ConflictPair pair : pairs) {
            publishConflictAlert(pair);
        }
        if (!pairs.isEmpty()) {
            log.info("fleet conflict scan: {} conflicts among {} tracked drones",
                    pairs.size(), trajectories.size());
        }
        return pairs;
    }

    /** 发布单个冲突对的 30049 帧；发布失败仅记日志（扫描主流程不受影响）。 */
    private void publishConflictAlert(ConflictAvoidanceService.ConflictPair pair) {
        try {
            ConflictAlertMsg msg = new ConflictAlertMsg(
                    (float) pair.result.horizontalDistance,
                    (float) pair.result.timeToConflict,
                    pair.sysid1,
                    conflictTypeOf(pair.result.type).ordinal(),
                    pair.sysid2,
                    severityOf(pair.result.timeToConflict));
            // 事件 sysid 取冲突对第一机：WS 租户路由按其归属推导
            // （同租户机队内冲突对两机归属一致；跨租户时空域本就不该重叠）
            eventPublisher.publishEvent(
                    new MavlinkMessageEvent(this, pair.sysid1, ConflictAlertMsg.ID, msg,
                            System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("conflict alert frame publish failed: {} vs {}: {}",
                    pair.sysid1, pair.sysid2, e.getMessage());
        }
    }

    /** 4D 几何类型 → 协议枚举（HEAD-ON→COLLISION，CROSSING/OVERTAKE→PATH）。 */
    static ConflictType conflictTypeOf(String type4d) {
        if ("HEAD-ON".equals(type4d)) {
            return ConflictType.COLLISION;
        }
        if ("CROSSING".equals(type4d) || "OVERTAKE".equals(type4d)) {
            return ConflictType.PATH;
        }
        return ConflictType.PATH;
    }

    /** 冲突倒计时 → 协议 severity 1-4 分档。 */
    static int severityOf(double timeToConflictSec) {
        if (timeToConflictSec < SEVERITY_CRITICAL_SEC) {
            return 4;
        }
        if (timeToConflictSec < SEVERITY_MAJOR_SEC) {
            return 3;
        }
        if (timeToConflictSec < SEVERITY_MINOR_SEC) {
            return 2;
        }
        return 1;
    }
}

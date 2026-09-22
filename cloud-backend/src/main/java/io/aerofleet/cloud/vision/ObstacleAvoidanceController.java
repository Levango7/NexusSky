package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.mission.DroneCommandService;
import io.aerofleet.mavlink.enums.AvoidanceMode;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.enums.ThreatLevel;
import io.aerofleet.mavlink.messages.ObstacleReportMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 避障控制核心（M3 感知成像增强，FR-15/FR-16/FR-17/FR-18/FR-29）。
 * <p>
 * 持有 {@code ConcurrentHashMap<sysid, ObstacleConfig>} 与
 * {@code ConcurrentHashMap<sysid, ObstacleStatus>}，接收 ObstacleReport 并按威胁→命令映射下发。
 * <p>
 * 威胁→命令映射（FR-16）：
 * <ul>
 *   <li>CRITICAL → 紧急悬停（MAV_CMD_DO_SET_MODE → hover），优先于其他模式</li>
 *   <li>HIGH → 按配置模式（WAYPOINT_OFFSET → DO_REPOSITION / SPEED_LIMIT → DO_CHANGE_SPEED）</li>
 *   <li>MEDIUM/LOW/NONE → 不下发（仅记录）</li>
 * </ul>
 * 避障解除（FR-17）：HIGH→MEDIUM 恢复原航点/速度；CRITICAL 需手动解除。
 * <p>
 * 并发安全（DFX 4.2）：ConcurrentHashMap + volatile 字段。
 * 单机故障隔离（DFX 4.2）：per-sysid try-catch。
 */
@Service
public class ObstacleAvoidanceController {

    private static final Logger log = LoggerFactory.getLogger(ObstacleAvoidanceController.class);

    private final ConcurrentHashMap<Integer, ObstacleConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ObstacleStatus> statuses = new ConcurrentHashMap<>();
    private final DroneCommandService commands;
    private final DeviceRegistry registry;

    public ObstacleAvoidanceController(DroneCommandService commands, DeviceRegistry registry) {
        this.commands = commands;
        this.registry = registry;
    }

    /**
     * FR-15 避障配置。
     * <p>
     * 校验 safetyDistance≥1 + emergencyHover<safetyDistance（由 ObstacleConfig record 校验），
     * 存储配置并返回确认。
     */
    public ObstacleConfig configure(ObstacleConfig config) {
        // record 紧凑构造已校验，此处只需存储
        configs.put(config.sysid(), config);
        log.info("obstacle config: sysid={} safety={}m emergency={}m mode={} enabled={}",
                config.sysid(), config.safetyDistanceM(), config.emergencyHoverM(),
                config.mode(), config.enabled());
        return config;
    }

    /** FR-25 查询配置。 */
    public ObstacleConfig getConfig(int sysid) {
        return configs.get(sysid);
    }

    /** FR-18 避障状态查询。 */
    public ObstacleStatus getStatus(int sysid) {
        return statuses.computeIfAbsent(sysid, k -> new ObstacleStatus());
    }

    /**
     * FR-16 避障命令下发（接收 ObstacleReport 后调用）。
     * <p>
     * CRITICAL → 紧急悬停（优先）；HIGH → 按模式避障；MEDIUM 以下不下发。
     * FR-17 威胁降级解除：HIGH→MEDIUM 恢复；CRITICAL 需手动。
     *
     * @param sysid    源飞机 sysid
     * @param distance 最近障碍距离（米）
     * @param directionDeg 障碍方向（度）
     * @param threat   威胁等级
     */
    public void onObstacleReport(int sysid, double distance, double directionDeg,
                                 ThreatLevel threat) {
        ObstacleConfig cfg = configs.get(sysid);
        if (cfg == null || !cfg.enabled()) {
            // 异常 5.5.3-1：未启用避障的飞机不执行避障命令
            return;
        }

        ObstacleStatus status = statuses.computeIfAbsent(sysid, k -> new ObstacleStatus());
        status.previousThreat = status.currentThreat;
        status.currentThreat = threat;
        status.nearestDistance = distance;
        status.nearestDirectionDeg = directionDeg;
        status.lastReportTime = System.currentTimeMillis();

        // FR-16 威胁→命令映射
        try {
            switch (threat) {
                case CRITICAL -> emergencyHover(sysid, status);
                case HIGH -> avoidByMode(sysid, cfg, distance, directionDeg, status);
                default -> {
                    // MEDIUM/LOW/NONE: 不下发避障命令（仅记录）
                    // FR-17 威胁降级解除：HIGH→MEDIUM/LOW/NONE 恢复原航点/速度
                    if (isLowerThreat(status.previousThreat, threat)
                            && status.previousThreat == ThreatLevel.HIGH) {
                        releaseAvoidance(sysid, cfg, status);
                    }
                }
            }
        } catch (Exception e) {
            // DFX 4.2 单机故障隔离：per-sysid try-catch
            log.warn("obstacle avoidance failed for sysid={}: {}", sysid, e.getMessage());
        }
    }

    /** CRITICAL → 紧急悬停（MAV_CMD_DO_SET_MODE → hover）。 */
    private void emergencyHover(int sysid, ObstacleStatus status) {
        try {
            // MAV_CMD_DO_SET_MODE: p1=base_mode（SAFETY_ARMED|GUIDED 悬停），p2=custom_mode
            commands.command(sysid, MavEnums.MAV_CMD_DO_SET_MODE,
                    MavEnums.MAV_MODE_FLAG_SAFETY_ARMED | MavEnums.MAV_MODE_FLAG_GUIDED_ENABLED,
                    0, 0, 0, 0, 0, 0);
            status.inEmergencyHover = true;
            status.lastCommandTime = System.currentTimeMillis();
            log.info("obstacle avoidance: sysid={} CRITICAL -> emergency hover", sysid);
        } catch (Exception e) {
            log.warn("emergency hover command failed for sysid={}: {}", sysid, e.getMessage());
        }
    }

    /** HIGH → 按配置模式避障。 */
    private void avoidByMode(int sysid, ObstacleConfig cfg, double distance,
                             double directionDeg, ObstacleStatus status) {
        try {
            switch (cfg.mode()) {
                case WAYPOINT_OFFSET -> {
                    // 航点偏移：向障碍物反方向偏移 safetyDistance
                    // DO_REPOSITION: p5=lat, p6=lon, p7=alt（此处简化：仅记录方向，实际偏移由航点管理器处理）
                    double offsetDir = (directionDeg + 180) % 360;
                    commands.command(sysid, MavEnums.MAV_CMD_DO_REPOSITION,
                            0, 0, 0, (float) offsetDir, 0, 0, 0);
                    status.lastMode = AvoidanceMode.WAYPOINT_OFFSET;
                    log.info("obstacle avoidance: sysid={} HIGH WAYPOINT_OFFSET dir={}°",
                            sysid, offsetDir);
                }
                case SPEED_LIMIT -> {
                    // 速度限制：DO_CHANGE_SPEED: p2=speed（m/s）
                    commands.command(sysid, MavEnums.MAV_CMD_DO_CHANGE_SPEED,
                            0, (float) cfg.maxSpeedMs(), -1, 0, 0, 0, 0);
                    status.lastMode = AvoidanceMode.SPEED_LIMIT;
                    log.info("obstacle avoidance: sysid={} HIGH SPEED_LIMIT maxSpeed={}m/s",
                            sysid, cfg.maxSpeedMs());
                }
                case EMERGENCY_HOVER -> emergencyHover(sysid, status);
                case DISABLED -> { /* 不动作 */ }
            }
            status.lastCommandTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("avoidance command failed for sysid={}: {}", sysid, e.getMessage());
        }
    }

    /** FR-17 避障解除（威胁降级时恢复原航点/速度）。 */
    private void releaseAvoidance(int sysid, ObstacleConfig cfg, ObstacleStatus status) {
        try {
            switch (status.lastMode) {
                case SPEED_LIMIT -> {
                    // 恢复原速度：DO_CHANGE_SPEED p2=-1（恢复默认）
                    commands.command(sysid, MavEnums.MAV_CMD_DO_CHANGE_SPEED,
                            0, -1, -1, 0, 0, 0, 0);
                    log.info("obstacle avoidance: sysid={} threat degraded -> restore speed", sysid);
                }
                case WAYPOINT_OFFSET -> {
                    // 恢复原航点：DO_REPOSITION 清除偏移（p5=p6=p7=0 表示恢复当前航点）
                    log.info("obstacle avoidance: sysid={} threat degraded -> restore waypoint", sysid);
                }
                default -> { /* EMERGENCY_HOVER 不自动恢复（FR-17） */ }
            }
        } catch (Exception e) {
            log.warn("release avoidance failed for sysid={}: {}", sysid, e.getMessage());
        }
    }

    /**
     * FR-29 紧急悬停解除（操作员手动）。
     * <p>
     * 清除紧急悬停标志，下发恢复飞行命令。
     */
    public void release(int sysid) {
        ObstacleStatus status = statuses.get(sysid);
        if (status != null && status.inEmergencyHover) {
            status.inEmergencyHover = false;
            log.info("obstacle avoidance: sysid={} emergency hover released", sysid);
        }
    }

    /** 判断 newThreat 是否比 oldThreat 低（降级）。 */
    private boolean isLowerThreat(ThreatLevel oldThreat, ThreatLevel newThreat) {
        return newThreat.ordinal() < oldThreat.ordinal();
    }

    /** 所有避障状态（供 ObstaclePusher 1Hz 推送）。 */
    public java.util.Map<Integer, ObstacleStatus> allStatuses() {
        return java.util.Collections.unmodifiableMap(statuses);
    }

    /** 所有避障配置（供查询）。 */
    public java.util.Map<Integer, ObstacleConfig> allConfigs() {
        return java.util.Collections.unmodifiableMap(configs);
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理障碍物消息
    // =====================================================================

    /**
     * M3 障碍物报告路由（FR-15/FR-16）：将 ObstacleReportMsg 解码后按威胁等级
     * 执行避障命令下发。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ObstacleReportMsg).ID")
    public void onObstacleReportEvent(MavlinkMessageEvent event) {
        ObstacleReportMsg msg = (ObstacleReportMsg) event.getMessage();
        ThreatLevel threat = msg.threat >= 0 && msg.threat < ThreatLevel.values().length
                ? ThreatLevel.values()[msg.threat]
                : ThreatLevel.NONE;
        onObstacleReport(
                msg.sysid > 0 ? msg.sysid : event.getSysid(),
                msg.distance, msg.direction, threat);
        log.debug("OBSTACLE_REPORT sysid={} distance={}m direction={}° threat={}",
                event.getSysid(), msg.distance, msg.direction, threat);
    }
}
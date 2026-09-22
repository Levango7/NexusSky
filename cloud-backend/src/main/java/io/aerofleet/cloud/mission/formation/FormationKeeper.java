package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.mavlink.enums.MavEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 队形保持与修复（FR-07/FR-08/FR-16，DFX 4.1 ≤1Hz，DFX 4.2 并发安全）。
 *
 * 1Hz 周期检查各机实际位置与目标位置偏差：
 *   - STABLE 状态：偏差超容差（{@link #KEEP_TOLERANCE_M}）下发 DO_REPOSITION 修正（FR-08），
 *     容差内不下发（FR-07，避免抖动）
 *   - TRANSITIONING 状态：所有在线成员到位 → 转 STABLE（FR-16）
 *
 * 复用（只读/逐机，不修改既有服务）：
 *   - FormationService.activeFormations()：读取编队状态 + 目标位置
 *   - DeviceRegistry.get(sysid)：读取各机实际位置（volatile 字段只读）
 *   - DroneCommandService.command(sysid, MAV_CMD_DO_REPOSITION, ...)：逐机下发修正
 */
@Component
public class FormationKeeper {

    private static final Logger log = LoggerFactory.getLogger(FormationKeeper.class);

    /** 队形保持容差（m），偏差超过此值下发 DO_REPOSITION 修正（FR-07/FR-08）。 */
    static final double KEEP_TOLERANCE_M = 1.0;

    /** 地球半径（m），本地米距离近似。 */
    private static final double EARTH_R = 6371000.0;

    private final FormationService formationService;
    private final DeviceRegistry registry;
    private final DroneCommandService commands;

    /** 修正命令计数（供监控/单测观测）。 */
    private final AtomicInteger correctionCount = new AtomicInteger(0);

    public FormationKeeper(FormationService formationService,
                           DeviceRegistry registry,
                           DroneCommandService commands) {
        this.formationService = formationService;
        this.registry = registry;
        this.commands = commands;
    }

    /**
     * 1Hz 队形保持检查（FR-07/FR-08/FR-16）。
     * 遍历活跃编队，STABLE 检查偏差，TRANSITIONING 检测到位转 STABLE。
     */
    @Scheduled(fixedDelay = 1000)
    public void check() {
        for (Formation f : formationService.activeFormations()) {
            if (f.state == Formation.FormationState.STABLE) {
                checkStableDeviations(f);
            } else if (f.state == Formation.FormationState.TRANSITIONING) {
                checkTransitionComplete(f);
            } else if (f.state == Formation.FormationState.FORMING) {
                checkFormingComplete(f);
            }
        }
    }

    /**
     * STABLE 状态偏差检查：超容差下发 DO_REPOSITION（FR-08），容差内不下发（FR-07）。
     */
    private void checkStableDeviations(Formation f) {
        for (int sysid : f.sortedMembers()) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                continue;  // 离线成员跳过，不阻塞其余
            }
            Formation.GeoPos target = f.targetPositions.get(sysid);
            if (target == null) {
                continue;
            }
            if (Double.isNaN(snap.lat) || Double.isNaN(snap.lon)) {
                continue;  // 位置未上报
            }
            double dist = distanceM(snap.lat, snap.lon, target.lat(), target.lon());
            if (dist > KEEP_TOLERANCE_M) {
                // FR-08 偏差超容差：下发 DO_REPOSITION 修正
                try {
                    commands.command(sysid, MavEnums.MAV_CMD_DO_REPOSITION,
                            0, 0, 0, Float.NaN,  // param4=yaw=NaN=不变
                            (float) target.lat(), (float) target.lon(), (float) target.alt());
                    correctionCount.incrementAndGet();
                    log.info("formation {} repair: sysid={} deviation={}m -> DO_REPOSITION",
                            f.formationId, sysid, String.format("%.1f", dist));
                } catch (Exception e) {
                    log.warn("formation {} repair failed for sysid={}: {}",
                            f.formationId, sysid, e.getMessage());
                }
            } else {
                // FR-07 偏差在容差内：不下发修正（避免抖动），仅 debug 日志
                log.debug("formation {} keep: sysid={} deviation={}m (tolerance={}m)",
                        f.formationId, sysid, String.format("%.2f", dist), KEEP_TOLERANCE_M);
            }
        }
    }

    /**
     * TRANSITIONING 状态到位检测：所有在线成员到位 → STABLE（FR-16）。
     */
    private void checkTransitionComplete(Formation f) {
        if (allOnlineMembersInTolerance(f)) {
            f.state = Formation.FormationState.STABLE;
            f.version.incrementAndGet();
            formationService.persistState(f.formationId);
            log.info("formation {} transition complete -> STABLE", f.formationId);
        }
    }

    /**
     * FORMING 状态到位检测：所有在线成员到位 → STABLE（FR-14/FR-16）。
     * 编队创建后 state=FORMING，Keeper 检测所有在线成员到位后转 STABLE，
     * 之后 STABLE 的偏差检查才生效。逻辑与 checkTransitionComplete 相同。
     */
    private void checkFormingComplete(Formation f) {
        if (allOnlineMembersInTolerance(f)) {
            f.state = Formation.FormationState.STABLE;
            f.version.incrementAndGet();
            formationService.persistState(f.formationId);
            log.info("formation {} forming complete -> STABLE", f.formationId);
        }
    }

    /**
     * 检查所有在线成员是否都在目标位置容差内（FR-16 到位判定）。
     * 离线成员不参与判定；位置未上报或超容差则返回 false。
     */
    private boolean allOnlineMembersInTolerance(Formation f) {
        for (int sysid : f.sortedMembers()) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                continue;  // 离线成员不参与到位判定
            }
            Formation.GeoPos target = f.targetPositions.get(sysid);
            if (target == null) {
                continue;
            }
            if (Double.isNaN(snap.lat) || Double.isNaN(snap.lon)) {
                return false;
            }
            double dist = distanceM(snap.lat, snap.lon, target.lat(), target.lon());
            if (dist > KEEP_TOLERANCE_M) {
                return false;
            }
        }
        return true;
    }

    /** 修正命令累计计数（供单测观测）。 */
    public int correctionCount() {
        return correctionCount.get();
    }

    /**
     * 本地米距离（等距矩形近似，与 drone-sim GeoUtil 一致）。
     * 模拟器范围内精度充足，避免 haversine 的三角运算开销。
     */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double north = (lat1 - lat2) * metersPerDegLat();
        double east = (lon1 - lon2) * metersPerDegLon((lat1 + lat2) / 2.0);
        return Math.sqrt(north * north + east * east);
    }

    private static double metersPerDegLat() {
        return Math.PI / 180.0 * EARTH_R;
    }

    private static double metersPerDegLon(double refLat) {
        return Math.PI / 180.0 * EARTH_R * Math.cos(Math.toRadians(refLat));
    }
}
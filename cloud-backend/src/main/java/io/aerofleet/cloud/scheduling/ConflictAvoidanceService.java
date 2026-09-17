package io.aerofleet.cloud.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M10 空域冲突避免服务。
 * <p>
 * 检测两机航迹冲突（3D 空间 + 时间窗口），提供冲突解决策略。
 * <p>
 * 4D 增强能力（3D 空间 + 时间窗口）：
 * <ul>
 *   <li>{@link #predictTrajectory4D} 匀速直线运动模型预测未来 N 秒航迹</li>
 *   <li>{@link #checkConflict4D} 逐秒对比两架预测航迹，识别 HEAD-ON/CROSSING/OVERTAKE</li>
 *   <li>{@link #checkAllConflicts} 批量检测多架无人机两两冲突</li>
 *   <li>{@link #resolveConflict} 三种解决机动：高度分层 / 速度调整 / 等待盘旋</li>
 *   <li>{@link #reserveAirspace} / {@link #checkReservationConflict} 时间-空间预约表</li>
 * </ul>
 */
@Service
public class ConflictAvoidanceService {
    private static final Logger log = LoggerFactory.getLogger(ConflictAvoidanceService.class);

    private static final double MIN_HORIZONTAL_SEP = 50.0;  // 最小水平间隔 50m
    private static final double MIN_VERTICAL_SEP = 10.0;    // 最小垂直间隔 10m
    private static final double TIME_WINDOW_SEC = 30.0;     // 时间窗口 30s

    /** 地球半径(m)，用于经纬度↔米换算 */
    private static final double EARTH_RADIUS_M = 6371000.0;

    /** 高度分层机动：爬升/下降幅度(m) */
    private static final double ALTITUDE_LAYER_DELTA = 15.0;
    /** 速度调整机动：默认速度变化量(m/s) */
    private static final double SPEED_ADJUST_DELTA = 2.0;
    /** 等待盘旋机动：默认等待时间(s) */
    private static final double HOLDING_TIME_SEC = 20.0;

    /**
     * 4D 空域预约表：sysid -> 该无人机已预约的 4D 区块列表。
     * <p>
     * 线程安全由 Collections.synchronizedMap 包装保证（粗粒度锁，
     * 预约操作频率低，足够使用）。
     */
    private final Map<Integer, List<Reservation4D>> reservationTable =
            Collections.synchronizedMap(new HashMap<>());

    // ==================== 原有 API（向后兼容） ====================

    /** 检测两机航迹冲突 */
    public ConflictResult checkConflict(double lat1, double lon1, double alt1, double v1, double heading1,
                                        double lat2, double lon2, double alt2, double v2, double heading2) {
        double hDist = haversine(lat1, lon1, lat2, lon2);
        double vDist = Math.abs(alt1 - alt2);

        boolean hConflict = hDist < MIN_HORIZONTAL_SEP;
        boolean vConflict = vDist < MIN_VERTICAL_SEP;

        if (hConflict && vConflict) {
            double timeToConflict = estimateTimeToConflict(hDist, v1, v2);
            log.warn("Conflict detected: hDist={}m vDist={}m timeToConflict={}s", hDist, vDist, timeToConflict);
            return new ConflictResult(true, hDist, vDist, timeToConflict, "COLLISION");
        }
        return new ConflictResult(false, hDist, vDist, -1, "CLEAR");
    }

    /** 冲突解决：高度分层 */
    public String resolveByAltitude(int sysid1, int sysid2) {
        log.info("Resolving conflict by altitude separation: {} vs {}", sysid1, sysid2);
        return String.format("sysid %d climb +10m, sysid %d descend -10m", sysid1, sysid2);
    }

    /** 冲突解决：时间错开 */
    public String resolveByTime(int sysid1, int sysid2) {
        log.info("Resolving conflict by time offset: {} vs {}", sysid1, sysid2);
        return String.format("sysid %d delay 5s", sysid2);
    }

    // ==================== 4D 航迹预测 ====================

    /**
     * 4D 航迹预测：基于匀速直线运动模型，预测未来 horizonSec 秒的航迹。
     * <p>
     * 每秒一个航迹点，格式为 [lat, lon, alt, timeSec]：
     * <ul>
     *   <li>timeSec 从 0 开始，到 horizonSec（含），共 horizonSec+1 个点</li>
     *   <li>heading 单位为度，0=正北，90=正东，顺时针</li>
     *   <li>velocity 单位为 m/s</li>
     * </ul>
     *
     * @param lat       当前纬度(°)
     * @param lon       当前经度(°)
     * @param alt       当前高度(m)
     * @param velocity  速度(m/s)
     * @param heading   航向(°, 0=正北, 顺时针)
     * @param horizonSec 预测时长(s)
     * @return 4D 航迹点列表，每元素为 [lat, lon, alt, timeSec]
     */
    public List<double[]> predictTrajectory4D(double lat, double lon, double alt,
                                              double velocity, double heading, int horizonSec) {
        if (horizonSec < 0) {
            throw new IllegalArgumentException("horizonSec must be non-negative, got " + horizonSec);
        }
        List<double[]> trajectory = new ArrayList<>(horizonSec + 1);
        double headingRad = Math.toRadians(heading);
        // 单位时间(1s)内在地球表面移动的距离(m)
        double stepMeters = velocity; // 1s * velocity(m/s)
        // 经纬度每米对应的度数（近似，使用当前纬度修正经度收敛）
        double latPerMeter = 1.0 / EARTH_RADIUS_M * 180.0 / Math.PI;
        double lonPerMeter = 1.0 / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))) * 180.0 / Math.PI;
        // 北向/东向分量
        double northComponent = stepMeters * Math.cos(headingRad);
        double eastComponent = stepMeters * Math.sin(headingRad);

        double curLat = lat;
        double curLon = lon;
        for (int t = 0; t <= horizonSec; t++) {
            // t=0 时为当前位置；t>0 时累加位移
            trajectory.add(new double[]{curLat, curLon, alt, t});
            curLat += northComponent * latPerMeter;
            curLon += eastComponent * lonPerMeter;
        }
        return trajectory;
    }

    // ==================== 4D 冲突检测 ====================

    /**
     * 4D 冲突检测：逐秒对比两架预测航迹，识别冲突类型。
     * <p>
     * 冲突判据：同一时间点水平距离 &lt; {@link #MIN_HORIZONTAL_SEP} 且
     * 垂直距离 &lt; {@link #MIN_VERTICAL_SEP}。
     * <p>
     * 冲突类型由两机航向差判定：
     * <ul>
     *   <li>HEAD-ON（对头）：航向差 ∈ [135°, 225°]</li>
     *   <li>OVERTAKE（追击）：航向差 &lt; 45° 且速度差显著</li>
     *   <li>CROSSING（交叉）：其他情况</li>
     * </ul>
     *
     * @param traj1 第一架无人机 4D 航迹
     * @param traj2 第二架无人机 4D 航迹
     * @return 冲突结果；无冲突时 conflict=false
     */
    public ConflictResult checkConflict4D(List<double[]> traj1, List<double[]> traj2) {
        if (traj1 == null || traj2 == null || traj1.isEmpty() || traj2.isEmpty()) {
            return new ConflictResult(false, Double.MAX_VALUE, Double.MAX_VALUE, -1, "CLEAR");
        }
        int n = Math.min(traj1.size(), traj2.size());
        for (int i = 0; i < n; i++) {
            double[] p1 = traj1.get(i);
            double[] p2 = traj2.get(i);
            // 两航迹点时间应一致（按索引对齐，predictTrajectory4D 保证 t 从 0 起）
            double t1 = p1[3];
            double t2 = p2[3];
            if (Math.abs(t1 - t2) > 0.5) {
                // 时间未对齐，跳过（防御性）
                continue;
            }
            double hDist = haversine(p1[0], p1[1], p2[0], p2[1]);
            double vDist = Math.abs(p1[2] - p2[2]);
            if (hDist < MIN_HORIZONTAL_SEP && vDist < MIN_VERTICAL_SEP) {
                String conflictType = classifyConflictType(traj1, traj2, i);
                log.warn("4D conflict at t={}s: hDist={}m vDist={}m type={}", t1, hDist, vDist, conflictType);
                return new ConflictResult(true, hDist, vDist, t1, conflictType);
            }
        }
        return new ConflictResult(false, Double.MAX_VALUE, Double.MAX_VALUE, -1, "CLEAR");
    }

    /**
     * 批量 4D 冲突检测：对一组无人机航迹两两检查，返回所有冲突结果。
     *
     * @param trajectories 无人机航迹列表
     * @return 所有冲突结果列表（无冲突时为空）
     */
    public List<ConflictResult> checkAllConflicts(List<DroneTrajectory> trajectories) {
        List<ConflictResult> conflicts = new ArrayList<>();
        if (trajectories == null || trajectories.size() < 2) {
            return conflicts;
        }
        for (int i = 0; i < trajectories.size(); i++) {
            for (int j = i + 1; j < trajectories.size(); j++) {
                DroneTrajectory a = trajectories.get(i);
                DroneTrajectory b = trajectories.get(j);
                ConflictResult r = checkConflict4D(a.points, b.points);
                if (r.conflict) {
                    conflicts.add(r);
                }
            }
        }
        return conflicts;
    }

    /**
     * 根据两机航迹的运动方向判定冲突类型。
     * <p>
     * 用冲突点附近的位移向量估算航向，再计算航向差。
     */
    private String classifyConflictType(List<double[]> traj1, List<double[]> traj2, int conflictIdx) {
        double heading1 = estimateHeadingAt(traj1, conflictIdx);
        double heading2 = estimateHeadingAt(traj2, conflictIdx);
        double headingDiff = normalizeAngleDiff(heading1 - heading2);
        // headingDiff ∈ [0, 180]
        if (headingDiff >= 135.0) {
            return "HEAD-ON";
        } else if (headingDiff < 45.0) {
            // 同向：进一步看速度差判定追击
            double v1 = estimateSpeedAt(traj1, conflictIdx);
            double v2 = estimateSpeedAt(traj2, conflictIdx);
            if (Math.abs(v1 - v2) > 1.0) {
                return "OVERTAKE";
            }
            // 速度相近的同向接近，归为交叉（边界情形）
            return "CROSSING";
        } else {
            return "CROSSING";
        }
    }

    /** 估算航迹在 idx 处的航向(°, 0=正北, 顺时针) */
    private double estimateHeadingAt(List<double[]> traj, int idx) {
        int next = Math.min(idx + 1, traj.size() - 1);
        if (next == idx) {
            return 0.0;
        }
        double[] p0 = traj.get(idx);
        double[] p1 = traj.get(next);
        double dLat = p1[0] - p0[0];
        double dLon = p1[1] - p0[1];
        // 北向 dLat，东向 dLon（已含纬度收敛修正的近似）
        double eastM = dLon * EARTH_RADIUS_M * Math.cos(Math.toRadians(p0[0])) * Math.PI / 180.0;
        double northM = dLat * EARTH_RADIUS_M * Math.PI / 180.0;
        return Math.toDegrees(Math.atan2(eastM, northM));
    }

    /** 估算航迹在 idx 处的速度(m/s) */
    private double estimateSpeedAt(List<double[]> traj, int idx) {
        int next = Math.min(idx + 1, traj.size() - 1);
        if (next == idx) {
            return 0.0;
        }
        double[] p0 = traj.get(idx);
        double[] p1 = traj.get(next);
        double dist = haversine(p0[0], p0[1], p1[0], p1[1]);
        double dt = p1[3] - p0[3];
        return dt > 0 ? dist / dt : 0.0;
    }

    /** 将角度差归一化到 [0, 180] */
    private double normalizeAngleDiff(double diff) {
        double d = ((diff % 360.0) + 360.0) % 360.0;
        if (d > 180.0) {
            d = 360.0 - d;
        }
        return d;
    }

    // ==================== 冲突解决机动 ====================

    /**
     * 冲突解决机动：根据策略生成具体机动建议。
     *
     * @param conflict  冲突结果（需含冲突双方信息）
     * @param strategy  解决策略
     * @return 机动建议
     */
    public ResolutionAdvice resolveConflict(ConflictResult conflict, ResolutionStrategy strategy) {
        // ConflictResult 不携带 sysid，这里用占位 0/1；调用方可通过 DroneTrajectory.sysid 关联
        return resolveConflict(0, 1, conflict, strategy);
    }

    /**
     * 冲突解决机动（带 sysid 重载）：根据策略生成具体机动建议。
     *
     * @param sysid1   第一架无人机 sysid
     * @param sysid2   第二架无人机 sysid
     * @param conflict 冲突结果
     * @param strategy 解决策略
     * @return 机动建议
     */
    public ResolutionAdvice resolveConflict(int sysid1, int sysid2,
                                            ConflictResult conflict, ResolutionStrategy strategy) {
        switch (strategy) {
            case ALTITUDE_LAYER: {
                String desc = String.format(
                        "sysid %d climb +%.0fm, sysid %d descend -%.0fm (vertical separation)",
                        sysid1, ALTITUDE_LAYER_DELTA, sysid2, ALTITUDE_LAYER_DELTA);
                log.info("Resolve by altitude layer: {}", desc);
                return new ResolutionAdvice(sysid1, sysid2, strategy, desc, ALTITUDE_LAYER_DELTA);
            }
            case SPEED_ADJUST: {
                // 根据冲突时间调整速度：冲突时间越短，调整越大
                double adjust = SPEED_ADJUST_DELTA;
                if (conflict != null && conflict.timeToConflict > 0 && conflict.timeToConflict < 10) {
                    adjust = SPEED_ADJUST_DELTA * 2.0; // 紧急情况加倍
                }
                String desc = String.format(
                        "sysid %d decelerate -%.1fm/s, sysid %d accelerate +%.1fm/s (time shift at t=%.1fs)",
                        sysid1, adjust, sysid2, adjust,
                        conflict != null ? conflict.timeToConflict : 0.0);
                log.info("Resolve by speed adjust: {}", desc);
                return new ResolutionAdvice(sysid1, sysid2, strategy, desc, adjust);
            }
            case HOLDING_PATTERN: {
                double holdSec = HOLDING_TIME_SEC;
                String desc = String.format(
                        "sysid %d enter holding pattern before conflict point, wait %.0fs then resume",
                        sysid1, holdSec);
                log.info("Resolve by holding pattern: {}", desc);
                return new ResolutionAdvice(sysid1, sysid2, strategy, desc, holdSec);
            }
            default:
                throw new IllegalArgumentException("Unknown resolution strategy: " + strategy);
        }
    }

    // ==================== 时间-空间预约 ====================

    /**
     * 预约 4D 空域区块。
     * <p>
     * 若与已有预约（含其他无人机）冲突，则拒绝预约。
     *
     * @param sysid     无人机 sysid
     * @param lat       区块中心纬度(°)
     * @param lon       区块中心经度(°)
     * @param alt       区块中心高度(m)
     * @param startTime 起始时间(s)
     * @param endTime   结束时间(s)
     * @param radius    区块水平半径(m)
     * @return true=预约成功；false=与已有预约冲突，拒绝
     */
    public boolean reserveAirspace(int sysid, double lat, double lon, double alt,
                                   double startTime, double endTime, double radius) {
        if (endTime < startTime) {
            throw new IllegalArgumentException("endTime must be >= startTime");
        }
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        synchronized (reservationTable) {
            if (checkReservationConflict(sysid, lat, lon, alt, startTime, endTime, radius)) {
                log.warn("Reservation rejected for sysid {}: conflict with existing reservation", sysid);
                return false;
            }
            Reservation4D reservation = new Reservation4D(lat, lon, alt, startTime, endTime, radius);
            reservationTable.computeIfAbsent(sysid, k -> new ArrayList<>()).add(reservation);
            log.info("Reservation accepted for sysid {}: ({},{},{}) t=[{},{}] r={}",
                    sysid, lat, lon, alt, startTime, endTime, radius);
            return true;
        }
    }

    /**
     * 检查 4D 预约冲突：是否与已有预约（其他无人机）在时间-空间上重叠。
     * <p>
     * 不检查 sysid 自己的已有预约（允许同一无人机连续预约相邻区块）。
     *
     * @param sysid     申请方 sysid
     * @param lat       区块中心纬度(°)
     * @param lon       区块中心经度(°)
     * @param alt       区块中心高度(m)
     * @param startTime 起始时间(s)
     * @param endTime   结束时间(s)
     * @param radius    区块水平半径(m)
     * @return true=存在冲突；false=无冲突
     */
    public boolean checkReservationConflict(int sysid, double lat, double lon, double alt,
                                            double startTime, double endTime, double radius) {
        synchronized (reservationTable) {
            for (Map.Entry<Integer, List<Reservation4D>> entry : reservationTable.entrySet()) {
                int ownerSysid = entry.getKey();
                if (ownerSysid == sysid) {
                    continue; // 跳过自己
                }
                for (Reservation4D existing : entry.getValue()) {
                    if (reservationsOverlap(lat, lon, alt, startTime, endTime, radius, existing)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * 判断两个 4D 预约区块是否重叠。
     * <p>
     * 重叠条件：时间区间相交 且 水平距离 &lt; (r1+r2) 且 垂直距离 &lt; {@link #MIN_VERTICAL_SEP}。
     */
    private boolean reservationsOverlap(double lat, double lon, double alt,
                                        double startTime, double endTime, double radius,
                                        Reservation4D existing) {
        // 时间重叠
        boolean timeOverlap = startTime < existing.endTime && existing.startTime < endTime;
        if (!timeOverlap) {
            return false;
        }
        // 水平重叠：中心距 < 半径和
        double hDist = haversine(lat, lon, existing.lat, existing.lon);
        if (hDist >= (radius + existing.radius)) {
            return false;
        }
        // 垂直重叠：高度差 < MIN_VERTICAL_SEP
        double vDist = Math.abs(alt - existing.alt);
        return vDist < MIN_VERTICAL_SEP;
    }

    /** 清空所有预约（测试辅助） */
    public void clearReservations() {
        reservationTable.clear();
    }

    /** 获取某无人机的所有预约（测试辅助，返回只读视图） */
    public List<Reservation4D> getReservations(int sysid) {
        return reservationTable.getOrDefault(sysid, Collections.emptyList());
    }

    // ==================== 工具方法 ====================

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double RE = EARTH_RADIUS_M;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return RE * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double estimateTimeToConflict(double dist, double v1, double v2) {
        double closingRate = (v1 + v2) / 2;
        return closingRate > 0 ? dist / closingRate : Double.MAX_VALUE;
    }

    // ==================== 内部类型 ====================

    /** 冲突解决策略枚举 */
    public enum ResolutionStrategy {
        /** 高度分层：一机爬升，一机下降 */
        ALTITUDE_LAYER,
        /** 速度调整：一机减速，一机加速，错开冲突时间点 */
        SPEED_ADJUST,
        /** 等待盘旋：在冲突点前盘旋等待 */
        HOLDING_PATTERN
    }

    /** 冲突检测结果 DTO */
    public static class ConflictResult {
        public final boolean conflict;
        public final double horizontalDistance;
        public final double verticalDistance;
        public final double timeToConflict;
        public final String type;

        public ConflictResult(boolean conflict, double hDist, double vDist, double time, String type) {
            this.conflict = conflict;
            this.horizontalDistance = hDist;
            this.verticalDistance = vDist;
            this.timeToConflict = time;
            this.type = type;
        }
    }

    /** 冲突解决机动建议 DTO */
    public static class ResolutionAdvice {
        /** 涉及的第一架无人机 sysid */
        public final int sysid1;
        /** 涉及的第二架无人机 sysid */
        public final int sysid2;
        /** 解决策略 */
        public final ResolutionStrategy strategy;
        /** 具体机动描述（人类可读） */
        public final String description;
        /** 机动参数值：爬升高度(m) / 速度变化(m/s) / 等待时间(s) */
        public final double paramValue;

        public ResolutionAdvice(int sysid1, int sysid2, ResolutionStrategy strategy,
                                String description, double paramValue) {
            this.sysid1 = sysid1;
            this.sysid2 = sysid2;
            this.strategy = strategy;
            this.description = description;
            this.paramValue = paramValue;
        }
    }

    /** 无人机 4D 航迹 DTO（用于批量冲突检测） */
    public static class DroneTrajectory {
        /** 无人机 sysid */
        public final int sysid;
        /** 4D 航迹点列表，每元素为 [lat, lon, alt, timeSec] */
        public final List<double[]> points;

        public DroneTrajectory(int sysid, List<double[]> points) {
            this.sysid = sysid;
            this.points = points;
        }
    }

    /** 4D 空域预约区块 DTO */
    public static class Reservation4D {
        /** 中心纬度(°) */
        public final double lat;
        /** 中心经度(°) */
        public final double lon;
        /** 中心高度(m) */
        public final double alt;
        /** 起始时间(s) */
        public final double startTime;
        /** 结束时间(s) */
        public final double endTime;
        /** 水平半径(m) */
        public final double radius;

        public Reservation4D(double lat, double lon, double alt,
                             double startTime, double endTime, double radius) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.startTime = startTime;
            this.endTime = endTime;
            this.radius = radius;
        }
    }
}

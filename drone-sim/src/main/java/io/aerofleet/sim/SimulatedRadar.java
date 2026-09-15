package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ScanMode;
import io.aerofleet.mavlink.enums.TrackState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟相控阵雷达数据源（M4 硬件抽象，FR-04/FR-05/FR-06）。
 * <p>
 * 基于合成目标世界产出雷达目标报告：对扫描波束覆盖范围内的合成目标，
 * 计算距离/方位/俯仰/径向速度/RCS（基于目标类型查表），并维护跟踪状态。
 * <p>
 * 波束控制（FR-04）：
 * <ul>
 *   <li>STARE：波束固定指向配置的方位角/俯仰角</li>
 *   <li>SECTOR_SCAN：波束在 [azimCenter - azimWidth/2, azimCenter + azimWidth/2] 范围内往返扫描</li>
 *   <li>TRACK_WHILE_SCAN：扇扫同时维持对已跟踪目标的波束确认</li>
 * </ul>
 * <p>
 * 线程安全：trackStates 使用 ConcurrentHashMap；beamAzim 由 tick 线程独占写。
 */
public class SimulatedRadar implements PhasedArrayRadar {

    private final Map<Integer, TrackState> trackStates = new ConcurrentHashMap<>();
    private double beamAzim = 0;
    private long lastScanMs = 0;

    @Override
    public List<RadarTargetReport> scan(RadarScanConfig config, List<SyntheticTarget> targets) {
        long now = System.currentTimeMillis();
        // FR-04 波束控制：更新波束方位
        beamAzim = updateBeamAzim(config, now);

        List<RadarTargetReport> out = new ArrayList<>();
        if (targets == null || targets.isEmpty()) {
            return out;  // FR-01 空目标 → 空列表（非 null）
        }

        for (SyntheticTarget t : targets) {
            // 计算目标相对雷达的极坐标
            double dist = Math.hypot(t.north(), t.east());
            if (dist > config.range()) {
                continue;  // 超出探测距离
            }
            if (dist < 0.001) {
                continue;  // 目标在雷达位置，跳过
            }
            double azim = Math.toDegrees(Math.atan2(t.east(), t.north()));
            azim = ((azim % 360) + 360) % 360;
            double elev = Math.toDegrees(Math.atan2(t.alt(), dist));

            // FR-04 波束覆盖范围过滤
            if (!inBeamCoverage(azim, elev, config)) {
                continue;
            }

            // FR-05 计算雷达参数
            double radialVel = (t.velN() * t.north() + t.velE() * t.east()) / dist;
            double heading = Math.toDegrees(Math.atan2(t.velE(), t.velN()));
            heading = ((heading % 360) + 360) % 360;
            double rcs = rcsByType(t.kind());

            // FR-05 跟踪状态维护（DETECTED → TRACKING）
            TrackState state = trackStates.compute(t.id(), (id, prev) -> {
                if (prev == null || prev == TrackState.LOST) {
                    return TrackState.DETECTED;
                }
                if (prev == TrackState.DETECTED) {
                    return TrackState.TRACKING;
                }
                return TrackState.TRACKING;
            });

            out.add(new RadarTargetReport(t.id(), dist, azim, elev, radialVel, heading,
                    rcs, state, now));
        }
        return out;
    }

    @Override
    public double currentBeamAzim() {
        return beamAzim;
    }

    /** FR-04 波束方位更新。 */
    private double updateBeamAzim(RadarScanConfig config, long now) {
        switch (config.mode()) {
            case STARE:
                return config.azimCenter();
            case SECTOR_SCAN:
            case TRACK_WHILE_SCAN:
                // 扇扫：在 [center - width/2, center + width/2] 范围内以 scanPeriodMs 周期往返
                // lastScanMs 仅在首次调用时初始化为 now，之后不再更新，
                // 让 (now - lastScanMs) 随时间增长驱动三角波往返扫描。
                // 若每次都更新 lastScanMs = now，则 (now - lastScanMs) 恒等于两次调用的间隔，
                // 在 VirtualDrone 按 scanPeriodMs 周期触发 scan() 时 phase 恒为 0，波束固定在起始端。
                if (lastScanMs == 0) {
                    lastScanMs = now;
                }
                double halfWidth = config.azimWidth() / 2.0;
                double period = config.scanPeriodMs() / 1000.0;
                double phase = ((now - lastScanMs) / 1000.0) % period / period;  // 0..1
                // 三角波：0→1→0 往返
                double triangle = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
                return config.azimCenter() - halfWidth + triangle * config.azimWidth();
            default:
                return config.azimCenter();
        }
    }

    /** FR-04 波束覆盖范围判断。 */
    private boolean inBeamCoverage(double azim, double elev, RadarScanConfig config) {
        // 方位覆盖
        double azimMin, azimMax;
        if (config.mode() == ScanMode.STARE) {
            azimMin = config.azimCenter() - config.beamWidth() / 2.0;
            azimMax = config.azimCenter() + config.beamWidth() / 2.0;
        } else {
            azimMin = config.azimCenter() - config.azimWidth() / 2.0;
            azimMax = config.azimCenter() + config.azimWidth() / 2.0;
        }
        if (!angleInRange(azim, azimMin, azimMax)) {
            return false;
        }
        // 俯仰覆盖（波束宽度范围内）
        double elevMin = config.elevCenter() - config.beamWidth() / 2.0;
        double elevMax = config.elevCenter() + config.beamWidth() / 2.0;
        return elev >= elevMin && elev <= elevMax;
    }

    /** 角度范围判断（处理 0-360 环绕）。 */
    private static boolean angleInRange(double angle, double min, double max) {
        // 归一化到 0-360
        angle = ((angle % 360) + 360) % 360;
        min = ((min % 360) + 360) % 360;
        max = ((max % 360) + 360) % 360;
        if (min <= max) {
            return angle >= min && angle <= max;
        }
        // 环绕情况：min > max 表示跨越 0°
        return angle >= min || angle <= max;
    }

    /** FR-05 RCS 查表（基于目标类型）。 */
    private double rcsByType(String kind) {
        if (kind == null) {
            return 0.0;
        }
        return switch (kind.toLowerCase()) {
            case "vehicle", "car" -> 10.0;
            case "person", "pedestrian" -> -20.0;
            case "building", "static" -> 30.0;
            default -> 0.0;
        };
    }

    /** 重置跟踪状态（供测试/配置重置用）。 */
    public void resetTracks() {
        trackStates.clear();
    }
}
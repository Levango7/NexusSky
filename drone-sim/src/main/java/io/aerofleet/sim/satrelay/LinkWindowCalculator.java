package io.aerofleet.sim.satrelay;

import java.util.ArrayList;
import java.util.List;

/**
 * 可见窗口计算与过境切换检测（M7 星-空-地多层级中继，FR-5.1）。
 * <p>
 * 可见窗口：卫星对地面点仰角超过阈值的连续时间段（FR-5.1.1.5）。
 * 过境切换：卫星进入/离开可见窗口的边界事件（FR-5.1.1.6）。
 * <p>
 * 性能：24h 窗口计算 <50ms（DFX 4.1.2），默认 60s 步进 = 1440 次仰角计算。
 */
public final class LinkWindowCalculator {

    private LinkWindowCalculator() {
    }

    /** 可见窗口。 */
    public record VisibilityWindow(int satId, int groundPointId, long startMs, long endMs,
                                   double maxElevationDeg, long durationMs) {
        /** 窗口是否包含指定时刻。 */
        public boolean contains(long timeMs) {
            return timeMs >= startMs && timeMs <= endMs;
        }
    }

    /** 过境切换事件类型。 */
    public enum TransitionType {
        ENTER_WINDOW,   // 进入可见窗口
        LEAVE_WINDOW,   // 离开可见窗口
        NO_CHANGE       // 无变化
    }

    /** 过境切换事件。 */
    public record TransitionEvent(TransitionType type, int satId, long timeMs, double elevationDeg) {}

    /**
     * 计算卫星对地面点在未来时间范围内的可见窗口（FR-5.1.1.5）。
     * <p>
     * 在 [fromMs, toMs] 区间按 stepMs 步进扫描仰角，找到仰角 > 阈值的连续段，
     * 对边界做线性插值精化。窗口连续性保证：合并相邻段。
     *
     * @param sat          卫星节点
     * @param groundPointId 地面点标识
     * @param latDeg       地面点纬度（度）
     * @param lonDeg       地面点经度（度）
     * @param thresholdDeg 仰角阈值（度）
     * @param fromMs       起始时间（ms）
     * @param toMs         结束时间（ms）
     * @param stepMs       扫描步进（ms）
     * @return 可见窗口列表（可能为空）
     */
    public static List<VisibilityWindow> computeWindows(SatelliteNode sat, int groundPointId,
                                                        double latDeg, double lonDeg,
                                                        double thresholdDeg,
                                                        long fromMs, long toMs, long stepMs) {
        List<VisibilityWindow> windows = new ArrayList<>();
        if (stepMs <= 0 || toMs <= fromMs) {
            return windows;
        }

        // 步进扫描仰角
        List<Long> times = new ArrayList<>();
        List<Double> elevations = new ArrayList<>();
        for (long t = fromMs; t <= toMs; t += stepMs) {
            double[] pos = OrbitModel.positionAt(sat.orbitAltitudeKm(), sat.inclinationDeg(),
                    sat.raanDeg(), sat.initialMeanAnomalyDeg(), t);
            double[] elAz = OrbitModel.elevationAzimuth(pos, latDeg, lonDeg);
            times.add(t);
            elevations.add(elAz[0]);
        }

        // 找到仰角 > 阈值的连续段
        boolean inWindow = false;
        long windowStart = 0;
        double maxEl = 0;
        long maxElTime = 0;

        for (int i = 0; i < elevations.size(); i++) {
            double el = elevations.get(i);
            long t = times.get(i);

            if (el > thresholdDeg) {
                if (!inWindow) {
                    inWindow = true;
                    windowStart = t;
                    maxEl = el;
                    maxElTime = t;
                } else if (el > maxEl) {
                    maxEl = el;
                    maxElTime = t;
                }
            } else {
                if (inWindow) {
                    // 窗口结束
                    long windowEnd = t;
                    // 线性插值精化边界（前一段最后一个 > 阈值的点与当前点之间）
                    if (i > 0) {
                        double prevEl = elevations.get(i - 1);
                        long prevT = times.get(i - 1);
                        if (prevEl > thresholdDeg) {
                            double ratio = (thresholdDeg - prevEl) / (el - prevEl);
                            windowEnd = (long) (prevT + ratio * (t - prevT));
                        }
                    }
                    long duration = windowEnd - windowStart;
                    if (duration > 0) {
                        windows.add(new VisibilityWindow(sat.satId(), groundPointId,
                                windowStart, windowEnd, maxEl, duration));
                    }
                    inWindow = false;
                }
            }
        }

        // 处理末尾仍在窗口内的情况
        if (inWindow) {
            long windowEnd = times.get(times.size() - 1);
            long duration = windowEnd - windowStart;
            if (duration > 0) {
                windows.add(new VisibilityWindow(sat.satId(), groundPointId,
                        windowStart, windowEnd, maxEl, duration));
            }
        }

        return windows;
    }

    /**
     * 检测卫星过境切换事件（FR-5.1.1.6）。
     * <p>
     * 比较当前可见状态与上一时刻，返回 ENTER_WINDOW / LEAVE_WINDOW / NO_CHANGE。
     *
     * @param sat          卫星节点
     * @param latDeg       地面点纬度（度）
     * @param lonDeg       地面点经度（度）
     * @param thresholdDeg 仰角阈值（度）
     * @param nowMs        当前仿真时钟（ms）
     * @return 过境切换事件
     */
    public static TransitionEvent checkTransition(SatelliteNode sat, double latDeg, double lonDeg,
                                                  double thresholdDeg, long nowMs) {
        double[] pos = OrbitModel.positionAt(sat.orbitAltitudeKm(), sat.inclinationDeg(),
                sat.raanDeg(), sat.initialMeanAnomalyDeg(), nowMs);
        double[] elAz = OrbitModel.elevationAzimuth(pos, latDeg, lonDeg);
        boolean currentlyVisible = elAz[0] > thresholdDeg;
        boolean wasVisible = sat.isVisible();

        TransitionType type;
        if (currentlyVisible && !wasVisible) {
            type = TransitionType.ENTER_WINDOW;
        } else if (!currentlyVisible && wasVisible) {
            type = TransitionType.LEAVE_WINDOW;
        } else {
            type = TransitionType.NO_CHANGE;
        }

        sat.updateVisibility(currentlyVisible, elAz[0], elAz[1]);
        return new TransitionEvent(type, sat.satId(), nowMs, elAz[0]);
    }

    /**
     * 计算当前可见窗口的剩余时间（ms）。
     * <p>
     * 若卫星当前不可见返回 0；可见时从当前时刻向后扫描找到窗口结束时间。
     *
     * @param sat          卫星节点
     * @param latDeg       地面点纬度（度）
     * @param lonDeg       地面点经度（度）
     * @param thresholdDeg 仰角阈值（度）
     * @param nowMs        当前仿真时钟（ms）
     * @param maxScanMs    最大扫描时长（ms），默认 1200000（20min）
     * @param stepMs       扫描步进（ms）
     * @return 窗口剩余时间（ms），不可见时 0
     */
    public static long windowRemainingMs(SatelliteNode sat, double latDeg, double lonDeg,
                                         double thresholdDeg, long nowMs,
                                         long maxScanMs, long stepMs) {
        if (!sat.isVisible()) {
            return 0;
        }
        for (long t = nowMs; t <= nowMs + maxScanMs; t += stepMs) {
            double[] pos = OrbitModel.positionAt(sat.orbitAltitudeKm(), sat.inclinationDeg(),
                    sat.raanDeg(), sat.initialMeanAnomalyDeg(), t);
            double[] elAz = OrbitModel.elevationAzimuth(pos, latDeg, lonDeg);
            if (elAz[0] <= thresholdDeg) {
                return t - nowMs;
            }
        }
        return maxScanMs;
    }
}
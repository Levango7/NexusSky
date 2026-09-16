package io.aerofleet.sim.orch;

/**
 * 动态重构器（M9 应急任务编排，T4 动态重构）。
 * <p>
 * 根据无人机损毁、低电量、地形变化等事件，决策重构类型：
 * <ul>
 *   <li>损毁：覆盖下降 &lt; 10% → MESH_SELF_HEAL；≥ 10% → FULL_REPLAN</li>
 *   <li>低电量：&lt; 15% → NO_ACTION（必须返航）；&lt; 30% 且有替换 → PARTIAL_REPLAN；否则 NO_ACTION</li>
 *   <li>地形变化 → PARTIAL_REPLAN</li>
 * </ul>
 * <p>
 * 所有阈值构造时配置，不可变。线程安全：无共享可变状态（仅读阈值）。
 */
public class DynamicReconfigurator {

    /** 心跳超时阈值（ms）。 */
    private final long heartbeatTimeoutMs;
    /** 低电量阈值（%）。 */
    private final int lowBatteryThreshold;
    /** 危急电量阈值（%）。 */
    private final int criticalBatteryThreshold;
    /** 覆盖下降触发全量重规划阈值（比例，0~1）。 */
    private final double coverageDeclineReplanThreshold;

    /**
     * 构造动态重构器。
     *
     * @param heartbeatTimeoutMs             心跳超时阈值（ms）
     * @param lowBatteryThreshold            低电量阈值（%）
     * @param criticalBatteryThreshold       危急电量阈值（%）
     * @param coverageDeclineReplanThreshold 覆盖下降触发全量重规划阈值（比例，0~1）
     */
    public DynamicReconfigurator(long heartbeatTimeoutMs, int lowBatteryThreshold,
                                 int criticalBatteryThreshold,
                                 double coverageDeclineReplanThreshold) {
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.lowBatteryThreshold = lowBatteryThreshold;
        this.criticalBatteryThreshold = criticalBatteryThreshold;
        this.coverageDeclineReplanThreshold = coverageDeclineReplanThreshold;
    }

    /**
     * 无人机损毁处理。
     * <p>
     * 覆盖下降比例 = (currentCoverage - coverageAfterLoss) / currentCoverage。
     * <ul>
     *   <li>下降 &lt; {@link #coverageDeclineReplanThreshold} → MESH_SELF_HEAL（仅补链）</li>
     *   <li>下降 ≥ {@link #coverageDeclineReplanThreshold} → FULL_REPLAN（重新分配全部无人机）</li>
     * </ul>
     *
     * @param planId           所属计划 id
     * @param droneId          损毁无人机 id
     * @param currentCoverage  损毁前覆盖率
     * @param coverageAfterLoss 损毁后覆盖率
     * @return 重构结果
     */
    public ReconfigResult onDroneLost(long planId, int droneId,
                                      double currentCoverage, double coverageAfterLoss) {
        double declineRatio;
        if (currentCoverage <= 0.0) {
            declineRatio = 1.0;
        } else {
            declineRatio = (currentCoverage - coverageAfterLoss) / currentCoverage;
        }
        if (declineRatio < coverageDeclineReplanThreshold) {
            return new ReconfigResult(ReconfigResult.Type.MESH_SELF_HEAL, 500L,
                    "drone " + droneId + " lost, coverage decline "
                            + String.format("%.2f%%", declineRatio * 100)
                            + " < threshold, mesh self-heal");
        } else {
            return new ReconfigResult(ReconfigResult.Type.FULL_REPLAN, 5000L,
                    "drone " + droneId + " lost, coverage decline "
                            + String.format("%.2f%%", declineRatio * 100)
                            + " >= threshold, full replan");
        }
    }

    /**
     * 低电量处理。
     * <ul>
     *   <li>battery &lt; {@link #criticalBatteryThreshold} → NO_ACTION（必须返航，由调用方处理）</li>
     *   <li>battery &lt; {@link #lowBatteryThreshold} 且 hasReplacement → PARTIAL_REPLAN（轮换）</li>
     *   <li>battery &lt; {@link #lowBatteryThreshold} 且 !hasReplacement → NO_ACTION</li>
     *   <li>battery ≥ {@link #lowBatteryThreshold} → NO_ACTION</li>
     * </ul>
     *
     * @param planId        所属计划 id
     * @param droneId       无人机 id
     * @param battery       当前电量（%）
     * @param hasReplacement 是否有替换无人机
     * @return 重构结果
     */
    public ReconfigResult onLowBattery(long planId, int droneId, int battery,
                                       boolean hasReplacement) {
        if (battery < criticalBatteryThreshold) {
            return new ReconfigResult(ReconfigResult.Type.NO_ACTION, 0L,
                    "drone " + droneId + " battery " + battery
                            + "% < critical " + criticalBatteryThreshold
                            + "%, must return, handled by caller");
        }
        if (battery < lowBatteryThreshold) {
            if (hasReplacement) {
                return new ReconfigResult(ReconfigResult.Type.PARTIAL_REPLAN, 2000L,
                        "drone " + droneId + " battery " + battery
                                + "% < low " + lowBatteryThreshold
                                + "%, replacement available, partial replan (rotation)");
            } else {
                return new ReconfigResult(ReconfigResult.Type.NO_ACTION, 0L,
                        "drone " + droneId + " battery " + battery
                                + "% < low " + lowBatteryThreshold
                                + "%, no replacement, no action");
            }
        }
        return new ReconfigResult(ReconfigResult.Type.NO_ACTION, 0L,
                "drone " + droneId + " battery " + battery
                        + "% >= low " + lowBatteryThreshold + "%, no action");
    }

    /**
     * 地形变化处理：始终 PARTIAL_REPLAN（仅重算受影响子区域）。
     *
     * @param planId 所属计划 id
     * @param gridX  变化网格 x 坐标
     * @param gridY  变化网格 y 坐标
     * @return 重构结果（PARTIAL_REPLAN）
     */
    public ReconfigResult onTerrainChanged(long planId, int gridX, int gridY) {
        return new ReconfigResult(ReconfigResult.Type.PARTIAL_REPLAN, 3000L,
                "terrain changed at grid (" + gridX + "," + gridY
                        + "), partial replan for affected sub-region");
    }

    /** @return 心跳超时阈值（ms）。 */
    public long getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    /** @return 低电量阈值（%）。 */
    public int getLowBatteryThreshold() {
        return lowBatteryThreshold;
    }

    /** @return 危急电量阈值（%）。 */
    public int getCriticalBatteryThreshold() {
        return criticalBatteryThreshold;
    }

    /** @return 覆盖下降触发全量重规划阈值。 */
    public double getCoverageDeclineReplanThreshold() {
        return coverageDeclineReplanThreshold;
    }
}
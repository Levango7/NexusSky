package io.aerofleet.sim.orch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 部署方案（M9 应急任务编排，T3 覆盖优化算法）。
 * <p>
 * 描述一次应急任务编排中所有无人机的部署结果，包含部署列表与整体指标。
 * 所有字段 final 不可变；部署列表做防御性拷贝保证内部不可变。
 * <p>
 * 字段说明：
 * <pre>
 * deployments       无人机部署列表
 * coverageRate      覆盖率（0-100）
 * connectRate       连通率（0-100）
 * expectedServiceMs 预期服务时长（ms）
 * </pre>
 */
public final class DeploymentPlan {

    private final List<DroneDeployment> deployments;
    private final double coverageRate;
    private final double connectRate;
    private final long expectedServiceMs;

    /**
     * 构造部署方案。
     *
     * @param deployments       无人机部署列表（做防御性拷贝）
     * @param coverageRate      覆盖率（0-100）
     * @param connectRate       连通率（0-100）
     * @param expectedServiceMs 预期服务时长（ms）
     */
    public DeploymentPlan(List<DroneDeployment> deployments, double coverageRate,
                          double connectRate, long expectedServiceMs) {
        this.deployments = deployments == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(deployments));
        this.coverageRate = coverageRate;
        this.connectRate = connectRate;
        this.expectedServiceMs = expectedServiceMs;
    }

    /** @return 无人机部署列表（不可变） */
    public List<DroneDeployment> getDeployments() {
        return deployments;
    }

    /** @return 覆盖率（0-100） */
    public double getCoverageRate() {
        return coverageRate;
    }

    /** @return 连通率（0-100） */
    public double getConnectRate() {
        return connectRate;
    }

    /** @return 预期服务时长（ms） */
    public long getExpectedServiceMs() {
        return expectedServiceMs;
    }

    /**
     * 按 droneId 查找部署方案。
     *
     * @param droneId 无人机唯一标识
     * @return 对应的部署方案，未找到返回 null
     */
    public DroneDeployment getDeployment(int droneId) {
        for (DroneDeployment d : deployments) {
            if (d.droneId == droneId) {
                return d;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "DeploymentPlan{drones=" + deployments.size()
                + ", coverage=" + String.format("%.2f", coverageRate) + "%"
                + ", connect=" + String.format("%.2f", connectRate) + "%"
                + ", serviceMs=" + expectedServiceMs + "}";
    }
}
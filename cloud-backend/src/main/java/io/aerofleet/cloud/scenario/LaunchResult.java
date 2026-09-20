package io.aerofleet.cloud.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景启动结果（P0-2）。
 * <p>
 * 由 {@link ScenarioLauncherService#launch(ScenarioTemplate, double, double)} 返回，
 * 描述本次启动的编排计划 ID、状态、分配的无人机列表与预估覆盖率。
 */
public class LaunchResult {

    /** 启动状态。 */
    public enum Status {
        /** 全部无人机成功分配并启动。 */
        SUCCESS,
        /** 部分无人机分配成功（机队不足或部分无人机不可用）。 */
        PARTIAL,
        /** 启动失败（无可用无人机或模板无效）。 */
        FAILED
    }

    private String launchId;
    private Status status;
    private long planId;
    private List<Integer> assignedDrones;
    private String message;
    private double estimatedCoveragePct;

    public LaunchResult() {
        this.assignedDrones = new ArrayList<>();
    }

    public LaunchResult(String launchId, Status status, long planId,
                        List<Integer> assignedDrones, String message, double estimatedCoveragePct) {
        this.launchId = launchId;
        this.status = status;
        this.planId = planId;
        this.assignedDrones = assignedDrones;
        this.message = message;
        this.estimatedCoveragePct = estimatedCoveragePct;
    }

    public String getLaunchId() {
        return launchId;
    }

    public void setLaunchId(String launchId) {
        this.launchId = launchId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long getPlanId() {
        return planId;
    }

    public void setPlanId(long planId) {
        this.planId = planId;
    }

    public List<Integer> getAssignedDrones() {
        return assignedDrones;
    }

    public void setAssignedDrones(List<Integer> assignedDrones) {
        this.assignedDrones = assignedDrones;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public double getEstimatedCoveragePct() {
        return estimatedCoveragePct;
    }

    public void setEstimatedCoveragePct(double estimatedCoveragePct) {
        this.estimatedCoveragePct = estimatedCoveragePct;
    }
}
package io.aerofleet.cloud.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景演练评估报告（P0-2）。
 * <p>
 * 演练以模拟执行方式进行（不实际起飞），结束后生成评估报告：
 * 通过项、失败项、综合评分与改进建议。
 */
public class DrillResult {

    private String drillId;
    private String templateId;
    private Instant startTime;
    private Instant endTime;
    private List<String> passedChecks;
    private List<String> failedChecks;
    private double score;
    private List<String> recommendations;

    public DrillResult() {
        this.passedChecks = new ArrayList<>();
        this.failedChecks = new ArrayList<>();
        this.recommendations = new ArrayList<>();
    }

    public DrillResult(String drillId, String templateId, Instant startTime, Instant endTime,
                       List<String> passedChecks, List<String> failedChecks,
                       double score, List<String> recommendations) {
        this.drillId = drillId;
        this.templateId = templateId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.passedChecks = passedChecks;
        this.failedChecks = failedChecks;
        this.score = score;
        this.recommendations = recommendations;
    }

    public String getDrillId() {
        return drillId;
    }

    public void setDrillId(String drillId) {
        this.drillId = drillId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public List<String> getPassedChecks() {
        return passedChecks;
    }

    public void setPassedChecks(List<String> passedChecks) {
        this.passedChecks = passedChecks;
    }

    public List<String> getFailedChecks() {
        return failedChecks;
    }

    public void setFailedChecks(List<String> failedChecks) {
        this.failedChecks = failedChecks;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }
}
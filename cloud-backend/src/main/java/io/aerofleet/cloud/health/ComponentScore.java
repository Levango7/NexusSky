package io.aerofleet.cloud.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单部件健康评分（P1-2 健康管理）。
 * <p>
 * 描述某个部件（电池/电机/振动/温度/通信/IMU/GPS）的健康分数、状态、原始指标与维护建议。
 * 不可变对象，构造后 {@code metrics} 返回不可修改视图。
 */
public class ComponentScore {

    /** 部件状态等级。 */
    public enum Status {
        /** 健康（评分 >= 80）。 */
        HEALTHY,
        /** 警告（评分 60-79）。 */
        WARNING,
        /** 危险（评分 < 60）。 */
        CRITICAL
    }

    private final ComponentType componentType;
    private final int score;
    private final Status status;
    private final Map<String, Double> metrics;
    private final String recommendation;

    public ComponentScore(ComponentType componentType,
                           int score,
                           Status status,
                           Map<String, Double> metrics,
                           String recommendation) {
        this.componentType = componentType;
        this.score = score;
        this.status = status;
        this.metrics = metrics == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        this.recommendation = recommendation;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    /** 健康分数（0-100）。 */
    public int getScore() {
        return score;
    }

    public Status getStatus() {
        return status;
    }

    /** 原始指标快照（不可修改）。 */
    public Map<String, Double> getMetrics() {
        return metrics;
    }

    /** 维护建议（可能为 null）。 */
    public String getRecommendation() {
        return recommendation;
    }

    /** 根据分数推断状态等级。 */
    public static Status statusOf(int score) {
        if (score >= 80) {
            return Status.HEALTHY;
        } else if (score >= 60) {
            return Status.WARNING;
        } else {
            return Status.CRITICAL;
        }
    }
}
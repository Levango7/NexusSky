package io.aerofleet.cloud.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无人机健康评分模型（P1-2 健康管理）。
 * <p>
 * 汇总单机各部件评分，给出总体分数与等级（A/B/C/D/F）。
 * 不可变对象，构造后 {@code componentScores} 返回不可修改视图。
 */
public class HealthScore {

    /** 总体健康等级。 */
    public enum Grade {
        A, B, C, D, F
    }

    private final int sysid;
    private final long timestamp;
    private final int overallScore;
    private final Grade grade;
    private final Map<ComponentType, ComponentScore> componentScores;

    public HealthScore(int sysid,
                       long timestamp,
                       int overallScore,
                       Grade grade,
                       Map<ComponentType, ComponentScore> componentScores) {
        this.sysid = sysid;
        this.timestamp = timestamp;
        this.overallScore = overallScore;
        this.grade = grade;
        this.componentScores = componentScores == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(componentScores));
    }

    public int getSysid() {
        return sysid;
    }

    /** 评分时间戳（epoch ms）。 */
    public long getTimestamp() {
        return timestamp;
    }

    /** 总体健康分数（0-100）。 */
    public int getOverallScore() {
        return overallScore;
    }

    public Grade getGrade() {
        return grade;
    }

    /** 各部件评分（不可修改）。 */
    public Map<ComponentType, ComponentScore> getComponentScores() {
        return componentScores;
    }

    /** 根据分数映射等级：90-100=A, 80-89=B, 70-79=C, 60-69=D, <60=F。 */
    public static Grade gradeOf(int score) {
        if (score >= 90) {
            return Grade.A;
        } else if (score >= 80) {
            return Grade.B;
        } else if (score >= 70) {
            return Grade.C;
        } else if (score >= 60) {
            return Grade.D;
        } else {
            return Grade.F;
        }
    }
}
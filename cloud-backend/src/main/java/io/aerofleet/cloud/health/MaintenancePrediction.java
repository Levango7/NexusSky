package io.aerofleet.cloud.health;

import java.time.LocalDate;

/**
 * 预测性维护建议（P1-2 预测性维护）。
 * <p>
 * 基于健康评分趋势与遥测指标，预测某部件的维护需求、预计失效时间与置信度。
 * 不可变对象。
 */
public class MaintenancePrediction {

    /** 维护紧迫度。 */
    public enum Urgency {
        /** 立即维护（已失效或临界）。 */
        IMMEDIATE,
        /** 7 天内维护。 */
        WITHIN_7_DAYS,
        /** 30 天内维护。 */
        WITHIN_30_DAYS,
        /** 正常（无需特别维护）。 */
        NORMAL
    }

    private final int sysid;
    private final ComponentType predictedComponent;
    private final Urgency urgency;
    private final LocalDate predictedFailureDate;
    private final int confidencePct;
    private final String reason;

    public MaintenancePrediction(int sysid,
                                  ComponentType predictedComponent,
                                  Urgency urgency,
                                  LocalDate predictedFailureDate,
                                  int confidencePct,
                                  String reason) {
        this.sysid = sysid;
        this.predictedComponent = predictedComponent;
        this.urgency = urgency;
        this.predictedFailureDate = predictedFailureDate;
        this.confidencePct = confidencePct;
        this.reason = reason;
    }

    public int getSysid() {
        return sysid;
    }

    public ComponentType getPredictedComponent() {
        return predictedComponent;
    }

    public Urgency getUrgency() {
        return urgency;
    }

    /** 预计失效/维护日期（可能为 null，表示暂无法预测）。 */
    public LocalDate getPredictedFailureDate() {
        return predictedFailureDate;
    }

    /** 预测置信度百分比 [0,100]。 */
    public int getConfidencePct() {
        return confidencePct;
    }

    /** 预测依据说明。 */
    public String getReason() {
        return reason;
    }
}
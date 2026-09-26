package io.aerofleet.cloud.mission.emergency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应急指挥命令（接报→研判→部署→执行→评估→总结全生命周期载体）。
 * <p>
 * 封装一次应急救援事件的完整信息，包括事件元数据、当前阶段、阶段转移历史、
 * 分配的无人机、研判/部署/评估/总结结果等。
 * <p>
 * 线程安全说明：可变字段采用 volatile + 防御性拷贝；集合字段使用
 * synchronizedList/synchronizedSet 保护。由 {@link EmergencyCommandWorkflow}
 * 在 REST 线程写，查询方法可并发读。
 */
public final class EmergencyCommand {

    /** 事件类型枚举。 */
    public enum IncidentType {
        /** 火灾 */
        FIRE("火灾"),
        /** 地震 */
        EARTHQUAKE("地震"),
        /** 洪水 */
        FLOOD("洪水"),
        /** 泥石流 */
        LANDSLIDE("泥石流"),
        /** 安防报警 */
        SECURITY_ALARM("安防报警"),
        /** 其他 */
        OTHER("其他");

        private final String displayName;

        IncidentType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** 按中文名称或枚举名解析，未匹配返回 OTHER。 */
        public static IncidentType fromString(String s) {
            if (s == null || s.isBlank()) {
                return OTHER;
            }
            String trimmed = s.trim();
            for (IncidentType t : values()) {
                if (t.name().equalsIgnoreCase(trimmed) || t.displayName.equals(trimmed)) {
                    return t;
                }
            }
            return OTHER;
        }
    }

    /** 严重级别枚举。 */
    public enum Severity {
        /** 信息 */
        INFO(1),
        /** 警告 */
        WARN(2),
        /** 严重 */
        CRITICAL(3);

        private final int level;

        Severity(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }

        /** 按名称或级别数字解析，未匹配返回 INFO。 */
        public static Severity fromString(String s) {
            if (s == null || s.isBlank()) {
                return INFO;
            }
            String trimmed = s.trim();
            for (Severity v : values()) {
                if (v.name().equalsIgnoreCase(trimmed)) {
                    return v;
                }
            }
            try {
                int lvl = Integer.parseInt(trimmed);
                for (Severity v : values()) {
                    if (v.level == lvl) {
                        return v;
                    }
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            return INFO;
        }
    }

    /** 事件位置（经纬度 + 海拔）。 */
    public static final class Location {
        private final double lat;
        private final double lon;
        private final double alt;

        public Location(double lat, double lon, double alt) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
        }

        public double getLat() { return lat; }
        public double getLon() { return lon; }
        public double getAlt() { return alt; }

        @Override
        public String toString() {
            return "Location{lat=" + lat + ", lon=" + lon + ", alt=" + alt + "}";
        }
    }

    /** 阶段转移记录。 */
    public static final class PhaseTransition {
        private final EmergencyCommandPhase fromPhase;
        private final EmergencyCommandPhase toPhase;
        private final long timestampMs;
        private final String operatorName;
        private final String notes;

        public PhaseTransition(EmergencyCommandPhase fromPhase, EmergencyCommandPhase toPhase,
                               long timestampMs, String operatorName, String notes) {
            this.fromPhase = fromPhase;
            this.toPhase = toPhase;
            this.timestampMs = timestampMs;
            this.operatorName = operatorName;
            this.notes = notes;
        }

        public EmergencyCommandPhase getFromPhase() { return fromPhase; }
        public EmergencyCommandPhase getToPhase() { return toPhase; }
        public long getTimestampMs() { return timestampMs; }
        public String getOperatorName() { return operatorName; }
        public String getNotes() { return notes; }

        @Override
        public String toString() {
            return "PhaseTransition{" + fromPhase + "->" + toPhase
                    + ", ts=" + timestampMs + ", op=" + operatorName
                    + ", notes='" + notes + "'}";
        }
    }

    /** 部署计划。 */
    public static final class DeploymentPlan {
        private final String planName;
        private final String strategy;
        private final int estimatedDurationMin;
        private final String communicationRelay;

        public DeploymentPlan(String planName, String strategy, int estimatedDurationMin,
                              String communicationRelay) {
            this.planName = planName;
            this.strategy = strategy;
            this.estimatedDurationMin = estimatedDurationMin;
            this.communicationRelay = communicationRelay;
        }

        public String getPlanName() { return planName; }
        public String getStrategy() { return strategy; }
        public int getEstimatedDurationMin() { return estimatedDurationMin; }
        public String getCommunicationRelay() { return communicationRelay; }

        @Override
        public String toString() {
            return "DeploymentPlan{name='" + planName + "', strategy='" + strategy
                    + "', dur=" + estimatedDurationMin + "min, relay=" + communicationRelay + "}";
        }
    }

    // =====================================================================
    // 字段
    // =====================================================================

    private final String id;
    private final IncidentType incidentType;
    private final Severity severity;
    private final Location location;
    private final String description;
    private final String reporterName;
    private final String reporterContact;
    private final long receiveTimeMs;

    private volatile EmergencyCommandPhase currentPhase;
    private final List<PhaseTransition> phaseHistory;
    private final Set<Integer> assignedDrones;

    private volatile String assessmentResult;
    private volatile DeploymentPlan deploymentPlan;
    private final List<String> executionLog;
    private volatile String evaluationResult;
    private volatile String summary;
    private volatile long closedTimeMs;

    /**
     * 构造应急指挥命令。
     *
     * @param id              命令 ID
     * @param incidentType    事件类型
     * @param severity        严重级别
     * @param location        事件位置
     * @param description     事件描述
     * @param reporterName    报告人姓名
     * @param reporterContact 报告人联系方式
     * @param receiveTimeMs   接报时间戳（毫秒）
     */
    public EmergencyCommand(String id, IncidentType incidentType, Severity severity,
                            Location location, String description,
                            String reporterName, String reporterContact, long receiveTimeMs) {
        this.id = id;
        this.incidentType = incidentType;
        this.severity = severity;
        this.location = location;
        this.description = description;
        this.reporterName = reporterName;
        this.reporterContact = reporterContact;
        this.receiveTimeMs = receiveTimeMs;
        this.currentPhase = EmergencyCommandPhase.RECEIVED;
        this.phaseHistory = Collections.synchronizedList(new ArrayList<>());
        this.assignedDrones = Collections.synchronizedSet(new LinkedHashSet<>());
        this.executionLog = Collections.synchronizedList(new ArrayList<>());
        this.assessmentResult = null;
        this.deploymentPlan = null;
        this.evaluationResult = null;
        this.summary = null;
        this.closedTimeMs = 0;
    }

    // =====================================================================
    // 只读字段
    // =====================================================================

    public String getId() { return id; }
    public IncidentType getIncidentType() { return incidentType; }
    public Severity getSeverity() { return severity; }
    public Location getLocation() { return location; }
    public String getDescription() { return description; }
    public String getReporterName() { return reporterName; }
    public String getReporterContact() { return reporterContact; }
    public long getReceiveTimeMs() { return receiveTimeMs; }

    // =====================================================================
    // 可变字段
    // =====================================================================

    public EmergencyCommandPhase getCurrentPhase() { return currentPhase; }

    /** 包级可见：仅 {@link EmergencyCommandWorkflow} 可写。 */
    void setCurrentPhase(EmergencyCommandPhase phase) {
        synchronized (phaseHistory) {
            this.currentPhase = phase;
        }
    }

    /** 返回阶段历史防御性拷贝。 */
    public List<PhaseTransition> getPhaseHistory() {
        synchronized (phaseHistory) {
            return new ArrayList<>(phaseHistory);
        }
    }

    /** 包级可见：追加阶段转移记录。 */
    void addPhaseTransition(PhaseTransition transition) {
        synchronized (phaseHistory) {
            phaseHistory.add(transition);
        }
    }

    /** 返回已分配无人机集合防御性拷贝。 */
    public Set<Integer> getAssignedDrones() {
        synchronized (assignedDrones) {
            return new LinkedHashSet<>(assignedDrones);
        }
    }

    /** 分配无人机（去重）。 */
    public void assignDrone(int droneId) {
        assignedDrones.add(droneId);
    }

    /** 批量分配无人机。 */
    public void assignDrones(Set<Integer> droneIds) {
        if (droneIds != null) {
            assignedDrones.addAll(droneIds);
        }
    }

    public String getAssessmentResult() { return assessmentResult; }
    public void setAssessmentResult(String result) { this.assessmentResult = result; }

    public DeploymentPlan getDeploymentPlan() { return deploymentPlan; }
    public void setDeploymentPlan(DeploymentPlan plan) { this.deploymentPlan = plan; }

    /** 返回执行日志防御性拷贝。 */
    public List<String> getExecutionLog() {
        synchronized (executionLog) {
            return new ArrayList<>(executionLog);
        }
    }

    /** 追加执行日志。 */
    public void appendExecutionLog(String entry) {
        executionLog.add(entry);
    }

    public String getEvaluationResult() { return evaluationResult; }
    public void setEvaluationResult(String result) { this.evaluationResult = result; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public long getClosedTimeMs() { return closedTimeMs; }
    public void setClosedTimeMs(long closedTimeMs) { this.closedTimeMs = closedTimeMs; }

    /**
     * 判断命令是否已关闭（终态）。
     *
     * @return true 若当前阶段为 CLOSED
     */
    public boolean isClosed() {
        return currentPhase == EmergencyCommandPhase.CLOSED;
    }
}
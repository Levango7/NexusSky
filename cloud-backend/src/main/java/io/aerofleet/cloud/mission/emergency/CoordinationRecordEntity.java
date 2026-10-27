package io.aerofleet.cloud.mission.emergency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.CoordinationEvaluation;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.CoordinationRecord;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.CoordinationReport;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.DeploymentInfo;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.ResourceConsumption;
import io.aerofleet.cloud.mission.emergency.AirGroundCoordinationService.ResponseTiming;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空地协同记录归档实体（V33；审计 §2.1 第 6 条）。
 *
 * <p>四个复合对象（deployment/evaluation/report）的 (反)序列化一律走**显式字段映射**
 * （{@link #toMaps}/{@link #fromMaps}）：这些是不可变类（仅 getter/全参构造），
 * Jackson 反射读写会在反序列化时踩"无默认构造"的坑；显式映射把序列化与反序列化
 * 放在同一处，加字段时两边一起看见，不给"半个字段静默丢失"留缝。
 */
@Entity
@Table(name = "coordination_records")
public class CoordinationRecordEntity {

    private static final Logger log = LoggerFactory.getLogger(CoordinationRecordEntity.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @Column(name = "coordination_id", length = 64)
    private String coordinationId;

    @Column(name = "alarm_event_id", length = 128)
    private String alarmEventId;

    @Column(name = "cmd_id", length = 64)
    private String cmdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 32, nullable = false)
    private EmergencyCommandPhase phase;

    @Column(name = "plan_id")
    private long planId;

    @Column(name = "start_time_ms", nullable = false)
    private long startTimeMs;

    @Column(name = "assess_time_ms", nullable = false)
    private long assessTimeMs;

    @Column(name = "deploy_time_ms", nullable = false)
    private long deployTimeMs;

    @Column(name = "execute_time_ms", nullable = false)
    private long executeTimeMs;

    @Column(name = "assessment_result", length = 2048)
    private String assessmentResult;

    @Column(name = "deployment_info")
    private String deploymentInfoJson;

    @Column(name = "evaluation")
    private String evaluationJson;

    @Column(name = "report")
    private String reportJson;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected CoordinationRecordEntity() {
        // JPA 用
    }

    public static CoordinationRecordEntity fromRecord(CoordinationRecord r) {
        CoordinationRecordEntity e = new CoordinationRecordEntity();
        e.coordinationId = r.getCoordinationId();
        e.alarmEventId = r.getAlarmEventId();
        e.cmdId = r.getCmdId();
        e.phase = r.getPhase();
        e.planId = r.getPlanId();
        e.startTimeMs = r.getStartTimeMs();
        e.assessTimeMs = r.getAssessTimeMs();
        e.deployTimeMs = r.getDeployTimeMs();
        e.executeTimeMs = r.getExecuteTimeMs();
        e.assessmentResult = r.getAssessmentResult();
        e.deploymentInfoJson = writeJson(e.coordinationId, "deploymentInfo", toMap(r.getDeploymentInfo()));
        e.evaluationJson = writeJson(e.coordinationId, "evaluation", toMap(r.getEvaluation()));
        e.reportJson = writeJson(e.coordinationId, "report", toMap(r.getReport()));
        e.updatedAt = Instant.now();
        return e;
    }

    /** 重建域对象（读穿路径与测试共用）。复合对象坏 JSON 降级 null 并留痕，不炸读取。 */
    public CoordinationRecord toRecord() {
        CoordinationRecord r = new CoordinationRecord(coordinationId, alarmEventId, cmdId);
        r.setPhase(phase);
        r.restorePhaseTimestamps(assessTimeMs, deployTimeMs, executeTimeMs);
        r.setPlanId(planId);
        r.setAssessmentResult(assessmentResult);
        r.setDeploymentInfo(fromDeployment(readJson(coordinationId, "deploymentInfo", deploymentInfoJson)));
        r.setEvaluation(fromEvaluation(readJson(coordinationId, "evaluation", evaluationJson)));
        r.setReport(fromReport(readJson(coordinationId, "report", reportJson)));
        return r;
    }

    // ------------------------------------------------------------------
    // 显式映射：序列化与反序列化在同一处（加字段时两边一起改）
    // ------------------------------------------------------------------

    static Map<String, Object> toMap(DeploymentInfo d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioType", d.getScenarioType());
        m.put("centerLat", d.getCenterLat());
        m.put("centerLon", d.getCenterLon());
        m.put("radius", d.getRadius());
        m.put("droneIds", d.getDroneIds());
        m.put("groundDeployAvailable", d.isGroundDeployAvailable());
        m.put("groundDeploySummary", d.getGroundDeploySummary());
        m.put("priority", d.getPriority());
        return m;
    }

    static DeploymentInfo fromDeployment(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        DeploymentInfo d = new DeploymentInfo();
        d.setScenarioType(intOf(m.get("scenarioType")));
        d.setCenterLat(intOf(m.get("centerLat")));
        d.setCenterLon(intOf(m.get("centerLon")));
        d.setRadius(intOf(m.get("radius")));
        d.setGroundDeployAvailable(boolOf(m.get("groundDeployAvailable")));
        d.setGroundDeploySummary(stringOf(m.get("groundDeploySummary")));
        d.setPriority(stringOf(m.get("priority")));
        Object ids = m.get("droneIds");
        if (ids instanceof List<?> list) {
            List<Integer> droneIds = new ArrayList<>();
            for (Object o : list) {
                droneIds.add(intOf(o));
            }
            d.setDroneIds(droneIds);
        }
        return d;
    }

    static Map<String, Object> toMap(CoordinationEvaluation e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coverageRate", e.coverageRate);
        m.put("deviceCoverageRate", e.deviceCoverageRate);
        m.put("connectivityRate", e.connectivityRate);
        m.put("timing", Map.of(
                "receiveToAssessSec", e.timing == null ? 0L : e.timing.receiveToAssessSec,
                "assessToDeploySec", e.timing == null ? 0L : e.timing.assessToDeploySec,
                "deployToExecuteSec", e.timing == null ? 0L : e.timing.deployToExecuteSec,
                "totalResponseSec", e.timing == null ? 0L : e.timing.totalResponseSec));
        m.put("consumption", Map.of(
                "droneCount", e.consumption == null ? 0 : e.consumption.droneCount,
                "deviceCount", e.consumption == null ? 0 : e.consumption.deviceCount,
                "avgBatteryPct", e.consumption == null ? 0 : e.consumption.avgBatteryPct,
                "meshNodes", e.consumption == null ? 0 : e.consumption.meshNodes));
        return m;
    }

    static CoordinationEvaluation fromEvaluation(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> t = m.get("timing") instanceof Map<?, ?> tm ? (Map<String, Object>) tm : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> c = m.get("consumption") instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
        return new CoordinationEvaluation(
                intOf(m.get("coverageRate")),
                intOf(m.get("deviceCoverageRate")),
                intOf(m.get("connectivityRate")),
                new ResponseTiming(longOf(t.get("receiveToAssessSec")), longOf(t.get("assessToDeploySec")),
                        longOf(t.get("deployToExecuteSec")), longOf(t.get("totalResponseSec"))),
                new ResourceConsumption(intOf(c.get("droneCount")), intOf(c.get("deviceCount")),
                        intOf(c.get("avgBatteryPct")), intOf(c.get("meshNodes"))));
    }

    static Map<String, Object> toMap(CoordinationReport r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coordinationId", r.getCoordinationId());
        m.put("alarmEventId", r.getAlarmEventId());
        m.put("cmdId", r.getCmdId());
        m.put("planId", r.getPlanId());
        m.put("overview", r.getOverview());
        m.put("evaluationSummary", r.getEvaluationSummary());
        m.put("summary", r.getSummary());
        m.put("recommendations", r.getRecommendations());
        m.put("phaseTimeline", r.getPhaseTimeline());
        m.put("generatedAtMs", r.getGeneratedAtMs());
        return m;
    }

    static CoordinationReport fromReport(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new CoordinationReport(
                stringOf(m.get("coordinationId")), stringOf(m.get("alarmEventId")),
                stringOf(m.get("cmdId")), longOf(m.get("planId")),
                stringOf(m.get("overview")), stringOf(m.get("evaluationSummary")),
                stringOf(m.get("summary")), stringListOf(m.get("recommendations")),
                stringListOf(m.get("phaseTimeline")), longOf(m.get("generatedAtMs")));
    }

    // ------------------------------------------------------------------
    // JSON 读写（坏数据降级 null 并留痕——归档读取要的是"能看"，一条坏行不炸接口）
    // ------------------------------------------------------------------

    private static String writeJson(String id, String what, Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception ex) {
            log.warn("coordination {} 序列化失败（{}），按 null 落库：{}", id, what, ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String id, String what, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception ex) {
            log.warn("coordination {} 反序列化失败（{}），按 null 降级：{}", id, what, ex.getMessage());
            return null;
        }
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean boolOf(Object o) {
        return o instanceof Boolean b && b;
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> stringListOf(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                out.add(stringOf(item));
            }
        }
        return out;
    }

    public String getCoordinationId() {
        return coordinationId;
    }

    public EmergencyCommandPhase getPhase() {
        return phase;
    }
}

package io.aerofleet.cloud.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 应急救援场景模板（P0-2）。
 * <p>
 * 描述一类典型应急场景的无人机部署配置：灾害类型、规模、机队数量、
 * 作业半径、悬停高度、持续时长、协同策略、预设航点、通信模式等。
 * <p>
 * 由 {@link ScenarioPresetFactory} 提供 18 个内置预设（6 灾害 × 3 规模），
 * 亦支持通过 {@link ScenarioTemplateController} 创建自定义模板。
 */
public class ScenarioTemplate {

    /** 协同策略：决定无人机角色分配（侦察 / 中继 / 执行）。 */
    public enum CollaborationStrategy {
        /** 仅侦察：全部无人机执行侦察任务。 */
        RECON_ONLY,
        /** 侦察 + 中继：部分无人机执行中继通信任务。 */
        RECON_RELAY,
        /** 侦察 + 中继 + 执行：全角色协同。 */
        RECON_RELAY_EXEC
    }

    /** 通信模式：决定无人机间通信链路选择。 */
    public enum CommunicationMode {
        /** Mesh 自组网。 */
        MESH,
        /** 卫星通信。 */
        SAT,
        /** Mesh + 卫星双链路冗余。 */
        BOTH
    }

    /** 严重程度：影响机队规模与作业半径。 */
    public enum SeverityLevel {
        /** 小规模：少量无人机、小半径。 */
        SMALL,
        /** 中规模：中等机队、中等半径。 */
        MEDIUM,
        /** 大规模：大机队、大半径。 */
        LARGE
    }

    /** 预设航点（lat/lon 经纬度对）。 */
    public static class Wp {
        public double lat;
        public double lon;

        public Wp() {
        }

        public Wp(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private String id;
    private String name;
    private DisasterType disasterType;
    private SeverityLevel severityLevel;
    private String description;
    private int droneCount;
    private double radiusKm;
    private double hoverAltitudeM;
    private int durationMin;
    private CollaborationStrategy collaborationStrategy;
    private List<Wp> presetWaypoints;
    private CommunicationMode communicationMode;
    private Instant createdAt;
    private Instant updatedAt;

    public ScenarioTemplate() {
        this.presetWaypoints = new ArrayList<>();
    }

    public ScenarioTemplate(String id, String name, DisasterType disasterType, SeverityLevel severityLevel,
                            String description, int droneCount, double radiusKm,
                            double hoverAltitudeM, int durationMin,
                            CollaborationStrategy collaborationStrategy,
                            CommunicationMode communicationMode) {
        this.id = id;
        this.name = name;
        this.disasterType = disasterType;
        this.severityLevel = severityLevel;
        this.description = description;
        this.droneCount = droneCount;
        this.radiusKm = radiusKm;
        this.hoverAltitudeM = hoverAltitudeM;
        this.durationMin = durationMin;
        this.collaborationStrategy = collaborationStrategy;
        this.presetWaypoints = new ArrayList<>();
        this.communicationMode = communicationMode;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DisasterType getDisasterType() {
        return disasterType;
    }

    public void setDisasterType(DisasterType disasterType) {
        this.disasterType = disasterType;
    }

    public SeverityLevel getSeverityLevel() {
        return severityLevel;
    }

    public void setSeverityLevel(SeverityLevel severityLevel) {
        this.severityLevel = severityLevel;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getDroneCount() {
        return droneCount;
    }

    public void setDroneCount(int droneCount) {
        this.droneCount = droneCount;
    }

    public double getRadiusKm() {
        return radiusKm;
    }

    public void setRadiusKm(double radiusKm) {
        this.radiusKm = radiusKm;
    }

    public double getHoverAltitudeM() {
        return hoverAltitudeM;
    }

    public void setHoverAltitudeM(double hoverAltitudeM) {
        this.hoverAltitudeM = hoverAltitudeM;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(int durationMin) {
        this.durationMin = durationMin;
    }

    public CollaborationStrategy getCollaborationStrategy() {
        return collaborationStrategy;
    }

    public void setCollaborationStrategy(CollaborationStrategy collaborationStrategy) {
        this.collaborationStrategy = collaborationStrategy;
    }

    public List<Wp> getPresetWaypoints() {
        return presetWaypoints;
    }

    public void setPresetWaypoints(List<Wp> presetWaypoints) {
        this.presetWaypoints = presetWaypoints;
    }

    public CommunicationMode getCommunicationMode() {
        return communicationMode;
    }

    public void setCommunicationMode(CommunicationMode communicationMode) {
        this.communicationMode = communicationMode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 空地态势融合视图。
 * <p>
 * 将安防设备状态、无人机状态、报警事件和 mesh 拓扑信息融合为统一态势感知视图，
 * 供指挥中心实时掌握空地协同态势。由 {@link AirGroundCoordinationService#fuseAirGroundSituation()}
 * 生成。
 * <p>
 * 不可变值对象：所有集合字段在构造时做防御性拷贝并包装为不可变视图，线程安全。
 *
 * @see AirGroundCoordinationService
 */
public final class AirGroundSituation {

    /** 安防设备状态列表。 */
    private final List<SurveillanceDevice> surveillanceDevices;
    /** 无人机状态列表（sysid → 状态信息）。 */
    private final List<DroneStatus> droneStatuses;
    /** 报警事件列表。 */
    private final List<AlarmEvent> alarmEvents;
    /** mesh 拓扑信息（通信中继网络拓扑）。 */
    private final MeshTopology meshTopology;
    /** 态势生成时间戳（毫秒）。 */
    private final long generatedAtMs;

    /**
     * 构造空地态势融合视图。
     *
     * @param surveillanceDevices 安防设备状态列表
     * @param droneStatuses       无人机状态列表
     * @param alarmEvents         报警事件列表
     * @param meshTopology        mesh 拓扑信息
     * @param generatedAtMs       态势生成时间戳
     */
    public AirGroundSituation(List<SurveillanceDevice> surveillanceDevices,
                              List<DroneStatus> droneStatuses,
                              List<AlarmEvent> alarmEvents,
                              MeshTopology meshTopology,
                              long generatedAtMs) {
        this.surveillanceDevices = surveillanceDevices == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(surveillanceDevices));
        this.droneStatuses = droneStatuses == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(droneStatuses));
        this.alarmEvents = alarmEvents == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(alarmEvents));
        this.meshTopology = meshTopology == null ? MeshTopology.empty() : meshTopology;
        this.generatedAtMs = generatedAtMs;
    }

    public List<SurveillanceDevice> getSurveillanceDevices() {
        return surveillanceDevices;
    }

    public List<DroneStatus> getDroneStatuses() {
        return droneStatuses;
    }

    public List<AlarmEvent> getAlarmEvents() {
        return alarmEvents;
    }

    public MeshTopology getMeshTopology() {
        return meshTopology;
    }

    public long getGeneratedAtMs() {
        return generatedAtMs;
    }

    /** 在线安防设备数量。 */
    public int onlineDeviceCount() {
        return (int) surveillanceDevices.stream()
                .filter(d -> d.status == SurveillanceDevice.Status.ONLINE)
                .count();
    }

    /** 在线无人机数量。 */
    public int onlineDroneCount() {
        return (int) droneStatuses.stream()
                .filter(DroneStatus::isOnline)
                .count();
    }

    /** 未确认报警事件数量。 */
    public int unacknowledgedAlarmCount() {
        return (int) alarmEvents.stream()
                .filter(e -> !e.isAcknowledged())
                .count();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AirGroundSituation)) return false;
        AirGroundSituation that = (AirGroundSituation) o;
        return generatedAtMs == that.generatedAtMs
                && Objects.equals(surveillanceDevices, that.surveillanceDevices)
                && Objects.equals(droneStatuses, that.droneStatuses)
                && Objects.equals(alarmEvents, that.alarmEvents)
                && Objects.equals(meshTopology, that.meshTopology);
    }

    @Override
    public int hashCode() {
        return Objects.hash(surveillanceDevices, droneStatuses, alarmEvents, meshTopology, generatedAtMs);
    }

    @Override
    public String toString() {
        return "AirGroundSituation{devices=" + surveillanceDevices.size()
                + ", drones=" + droneStatuses.size()
                + ", alarms=" + alarmEvents.size()
                + ", mesh=" + meshTopology
                + ", ts=" + generatedAtMs + "}";
    }

    // =====================================================================
    // 内嵌类型
    // =====================================================================

    /** 无人机状态快照。 */
    public static final class DroneStatus {
        /** 无人机系统 ID（MAVLink sysid）。 */
        public final int sysid;
        /** 在线状态。 */
        public final boolean online;
        /** 当前纬度（度），0 表示未上报。 */
        public final double lat;
        /** 当前经度（度），0 表示未上报。 */
        public final double lon;
        /** 当前海拔（米）。 */
        public final double alt;
        /** 电池剩余百分比（0~100）。 */
        public final int batteryPct;
        /** 当前任务阶段描述。 */
        public final String missionPhase;

        public DroneStatus(int sysid, boolean online, double lat, double lon, double alt,
                           int batteryPct, String missionPhase) {
            this.sysid = sysid;
            this.online = online;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.batteryPct = Math.max(0, Math.min(100, batteryPct));
            this.missionPhase = missionPhase == null ? "" : missionPhase;
        }

        public boolean isOnline() {
            return online;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DroneStatus)) return false;
            DroneStatus that = (DroneStatus) o;
            return sysid == that.sysid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sysid);
        }

        @Override
        public String toString() {
            return "DroneStatus{sysid=" + sysid
                    + ", online=" + online
                    + ", lat=" + lat
                    + ", lon=" + lon
                    + ", alt=" + alt
                    + ", bat=" + batteryPct + "%"
                    + ", phase=" + missionPhase + "}";
        }
    }

    /** mesh 通信拓扑信息。 */
    public static final class MeshTopology {
        /** mesh 节点总数。 */
        public final int nodeCount;
        /** mesh 连接数（活跃链路数）。 */
        public final int linkCount;
        /** mesh 覆盖率（0~100）。 */
        public final int coverageRate;
        /** 中继节点列表（sysid）。 */
        private final List<Integer> relayNodes;

        public MeshTopology(int nodeCount, int linkCount, int coverageRate, List<Integer> relayNodes) {
            this.nodeCount = nodeCount;
            this.linkCount = linkCount;
            this.coverageRate = Math.max(0, Math.min(100, coverageRate));
            this.relayNodes = relayNodes == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(List.copyOf(relayNodes));
        }

        public static MeshTopology empty() {
            return new MeshTopology(0, 0, 0, Collections.emptyList());
        }

        public List<Integer> getRelayNodes() {
            return relayNodes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MeshTopology)) return false;
            MeshTopology that = (MeshTopology) o;
            return nodeCount == that.nodeCount
                    && linkCount == that.linkCount
                    && coverageRate == that.coverageRate;
        }

        @Override
        public int hashCode() {
            return Objects.hash(nodeCount, linkCount, coverageRate);
        }

        @Override
        public String toString() {
            return "MeshTopology{nodes=" + nodeCount
                    + ", links=" + linkCount
                    + ", cov=" + coverageRate + "%"
                    + ", relays=" + relayNodes + "}";
        }
    }
}
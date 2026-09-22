package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.AlertBus;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Attitude;
import io.aerofleet.mavlink.messages.EnvironmentAlert;
import io.aerofleet.mavlink.messages.EnvironmentStatus;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.GpsRawInt;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import io.aerofleet.mavlink.messages.MissionCurrent;
import io.aerofleet.mavlink.messages.RadioStatus;
import io.aerofleet.mavlink.messages.Statustext;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.VfrHud;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static io.aerofleet.mavlink.enums.MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;

/**
 * 核心遥测监听器：监听 {@link MavlinkMessageEvent} 中的标准 MAVLink 消息，
 * 更新 {@link DeviceRegistry} 中的 {@link DroneSnapshot} 状态。
 * <p>
 * 从 TelemetryIngestService 的 onXxx() 方法迁移而来，保留所有原始业务逻辑。
 * 依赖仅 DeviceRegistry + AlertBus（同包/近包），无循环依赖。
 */
@Component
public class TelemetrySnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySnapshotListener.class);

    private final DeviceRegistry registry;
    private final AlertBus alerts;

    public TelemetrySnapshotListener(DeviceRegistry registry, AlertBus alerts) {
        this.registry = registry;
        this.alerts = alerts;
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.Heartbeat).ID")
    public void onHeartbeat(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        Heartbeat hb = (Heartbeat) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.lastHeartbeatMs = System.currentTimeMillis();
        s.customMode = hb.customMode;
        s.baseMode = hb.baseMode;
        s.systemStatus = hb.systemStatus;
        s.armed = (hb.baseMode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;
        s.mode = px4NavStateLabel(hb.customMode);
        if (!s.online) {
            s.online = true;
            log.info("Drone back online: sysid={}", sysid);
        }
    }

    /** PX4 main-nav-state labels (custom_mode of a PX4 heartbeat). */
    static String px4NavStateLabel(int customMode) {
        return switch (customMode) {
            case 0 -> "MANUAL";
            case 1 -> "ALTITUDE";
            case 2 -> "POSITION";
            case 3 -> "MISSION";
            case 4 -> "RTL";
            case 5, 14, 15, 16, 17, 18, 19, 20, 21 -> "HOLD";
            case 8 -> "ACRO";
            case 9, 10, 11 -> "STABILIZED";
            case 12 -> "DESCENT/LAND";
            case 13 -> "STANDBY";
            default -> "UNKNOWN";
        };
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.SysStatus).ID")
    public void onSysStatus(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        SysStatus st = (SysStatus) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.voltage = st.voltageBattery;
        s.current = st.currentBattery;
        s.battery = st.batteryRemaining;
        s.load = st.load;
    }

    /**
     * RADIO_STATUS (E1): SiK raw rssi (~2x dB) -> dBm, mirrored remrssi
     * means a symmetric link. Drives the signal bar and link-quality alert.
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.RadioStatus).ID")
    public void onRadioStatus(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        RadioStatus rs = (RadioStatus) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        if (rs.rssi != RadioStatus.INVALID) {
            s.rssiDbm = RadioEnvironmentDbm.fromSik(rs.rssi);
        }
        if (rs.remrssi != RadioStatus.INVALID) {
            s.remRssiDbm = RadioEnvironmentDbm.fromSik(rs.remrssi);
        }
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.GpsRawInt).ID")
    public void onGps(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        GpsRawInt g = (GpsRawInt) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        boolean wasHealthy = s.gpsHealthy;
        s.fixType = g.fixType;
        s.satellites = g.satellitesVisible;
        s.eph = g.eph;
        s.gpsHealthy = g.fixType >= 3 && g.satellitesVisible >= 6;
        if (wasHealthy && !s.gpsHealthy) {
            AlertEntry entry = new AlertEntry(2, "GPS fix degraded (fixType="
                    + g.fixType + ", sats=" + g.satellitesVisible + ")",
                    System.currentTimeMillis());
            s.alerts.add(entry);
            alerts.publish(sysid, entry);
            log.warn("GPS degraded: sysid={} fixType={} sats={}", sysid, g.fixType, g.satellitesVisible);
        } else if (!wasHealthy && s.gpsHealthy) {
            AlertEntry entry = new AlertEntry(5, "GPS fix restored",
                    System.currentTimeMillis());
            s.alerts.add(entry);
            alerts.publish(sysid, entry);
            log.info("GPS restored: sysid={}", sysid);
        }
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.Attitude).ID")
    public void onAttitude(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        Attitude a = (Attitude) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.roll = Math.toDegrees(a.roll);
        s.pitch = Math.toDegrees(a.pitch);
        s.yaw = Math.toDegrees(a.yaw);
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.GlobalPositionInt).ID")
    public void onPosition(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        GlobalPositionInt p = (GlobalPositionInt) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.lat = p.lat();
        s.lon = p.lon();
        s.relativeAlt = p.relativeAltM();
        s.amslAlt = p.altMm / 1000.0;
        s.vx = p.vx / 100.0;
        s.vy = p.vy / 100.0;
        s.vz = p.vz / 100.0;
        if (p.hdg != MavEnums.HDG_UNKNOWN) {
            s.heading = p.hdg / 100.0;
        }
        if (p.latE7 != 0 || p.lonE7 != 0) {
            s.track.add(new TrackPoint(p.lat(), p.lon(), p.relativeAltM(),
                    System.currentTimeMillis()));
        }
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.VfrHud).ID")
    public void onVfrHud(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        VfrHud h = (VfrHud) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.groundspeed = h.groundspeed;
        s.airspeed = h.airspeed;
        s.climb = h.climb;
        if (h.heading >= 0) {
            s.heading = h.heading;
        }
        s.throttle = h.throttle;
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.MissionCurrent).ID")
    public void onMissionCurrent(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        MissionCurrent m = (MissionCurrent) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.missionSeq = m.seq;
        s.missionTotal = m.total;
        s.missionState = m.missionState;
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.Statustext).ID")
    public void onStatustext(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        Statustext t = (Statustext) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        AlertEntry entry = new AlertEntry(t.severity, t.text, System.currentTimeMillis());
        s.alerts.add(entry);
        alerts.publish(sysid, entry);
        log.info("STATUSTEXT sysid={} sev={} text={}", sysid, t.severity, t.text);
    }

    /**
     * M0b 环境告警接入（FR-23）：结构化告警直接发布到 AlertBus，
     * 不依赖 STATUSTEXT 映射（EnvironmentAlert 已含 type/severity/value/threshold/text）。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.EnvironmentAlert).ID")
    public void onEnvironmentAlert(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        EnvironmentAlert msg = (EnvironmentAlert) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        AlertEntry entry = new AlertEntry(msg.severity, msg.text, System.currentTimeMillis());
        s.alerts.add(entry);
        alerts.publish(sysid, entry);
        log.info("ENV_ALERT sysid={} type={} sev={} text={}", sysid, msg.alertType, msg.severity, msg.text);
    }

    /**
     * M0b 环境状态视图更新（FR-24）：更新 DroneSnapshot 环境字段。
     * 单位还原：temperature c°C→°C / windSpeed cm/s→m/s / windDirection cdeg→deg / gust cm/s→m/s。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.EnvironmentStatus).ID")
    public void onEnvironmentStatus(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        EnvironmentStatus msg = (EnvironmentStatus) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.envTemperature = msg.temperature / 100.0;
        s.envHumidity = msg.humidity;
        s.envWindSpeed = msg.windSpeed / 100.0;
        s.envWindDirection = msg.windDirection / 100.0;
        s.envGust = msg.gust / 100.0;
        s.envWeather = msg.weather;
        s.envVisibility = msg.visibility;
        s.envRainRate = msg.rainRate;
    }

    /**
     * M5 mesh 心跳处理（FR-27）：更新节点在线状态与位置/电量/邻居数。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.MeshHeartbeatMsg).ID")
    public void onMeshHeartbeat(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        MeshHeartbeatMsg msg = (MeshHeartbeatMsg) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.lastHeartbeatMs = System.currentTimeMillis();
        if (!s.online) {
            s.online = true;
            log.info("Mesh node online: sysid={}", sysid);
        }
        log.debug("MESH_HEARTBEAT sysid={} neighbors={} battery={}%", sysid, msg.neighborCount, msg.batteryPercent);
    }
}
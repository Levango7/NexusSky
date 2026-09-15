package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.api.HardwareDataController;
import io.aerofleet.cloud.telemetry.AlertBus;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.cloud.vision.RadarController;
import io.aerofleet.cloud.vision.RotorController;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.Attitude;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.GpsRawInt;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.ImuDataMsg;
import io.aerofleet.mavlink.messages.LidarDataMsg;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionCurrent;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequest;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.RadarScanMsg;
import io.aerofleet.mavlink.messages.RadarTargetMsg;
import io.aerofleet.mavlink.messages.RadioStatus;
import io.aerofleet.mavlink.messages.RotorTelemetryMsg;
import io.aerofleet.mavlink.messages.Statustext;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.VfrHud;
import io.aerofleet.mavlink.messages.EnvironmentAlert;
import io.aerofleet.mavlink.messages.EnvironmentStatus;
import io.aerofleet.mavlink.enums.MavEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import static io.aerofleet.mavlink.enums.MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;

/**
 * Central MAVLink frame dispatcher: feeds the device registry with decoded
 * telemetry and routes command/mission responses into PendingAcks so
 * DroneCommandService futures can complete.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final DeviceRegistry registry;
    private final PendingAcks pendings;
    private final AlertBus alerts;
    private final RadarController radarController;
    private final RotorController rotorController;
    private final HardwareDataController hardwareDataController;

    public TelemetryIngestService(DeviceRegistry registry, PendingAcks pendings, AlertBus alerts,
                                  @Lazy RadarController radarController, @Lazy RotorController rotorController,
                                  @Lazy HardwareDataController hardwareDataController) {
        this.registry = registry;
        this.pendings = pendings;
        this.alerts = alerts;
        this.radarController = radarController;
        this.rotorController = rotorController;
        this.hardwareDataController = hardwareDataController;
    }

    /** Called by the UDP transport for every CRC-valid frame. Never throws. */
    public void handle(MavlinkFrame frame) {
        try {
            MavlinkMessage msg = MavlinkMessage.decode(frame);
            if (msg == null) {
                return; // not one of our message types: ignore at scaffold stage
            }
            int sysid = frame.getSystemId();
            switch (frame.getMessageId()) {
                case Heartbeat.ID -> onHeartbeat(sysid, (Heartbeat) msg);
                case SysStatus.ID -> onSysStatus(sysid, (SysStatus) msg);
                case GpsRawInt.ID -> onGps(sysid, (GpsRawInt) msg);
                case Attitude.ID -> onAttitude(sysid, (Attitude) msg);
                case GlobalPositionInt.ID -> onPosition(sysid, (GlobalPositionInt) msg);
                case VfrHud.ID -> onVfrHud(sysid, (VfrHud) msg);
                case MissionCurrent.ID -> onMissionCurrent(sysid, (MissionCurrent) msg);
                case Statustext.ID -> onStatustext(sysid, (Statustext) msg);
                case RadioStatus.ID -> onRadioStatus(sysid, (RadioStatus) msg);
                case CommandAck.ID -> pendings.offer(CommandAck.ID, msg, sysid);
                case MissionRequestInt.ID -> pendings.offer(MissionRequestInt.ID, msg, sysid);
                case MissionRequest.ID -> pendings.offer(MissionRequest.ID, msg, sysid);
                case MissionAckMsg.ID -> pendings.offer(MissionAckMsg.ID, msg, sysid);
                // Mission-download direction: the drone replying to our pull.
                case MissionCountMsg.ID -> pendings.offer(MissionCountMsg.ID, msg, sysid);
                case MissionItemInt.ID -> pendings.offer(MissionItemInt.ID, msg, sysid);
                // M0b 环境气象消息（FR-23/24）：环境告警接入 AlertBus，环境状态更新视图
                case EnvironmentAlert.ID -> onEnvironmentAlert(sysid, (EnvironmentAlert) msg);
                case EnvironmentStatus.ID -> onEnvironmentStatus(sysid, (EnvironmentStatus) msg);
                // M4 硬件抽象遥测路由（msgId 437-441，FR-18~FR-22）：
                // 解码后分发至对应 controller 回调，驱动雷达状态/目标缓存、旋翼遥测、LiDAR/IMU 缓存。
                case RadarScanMsg.ID -> radarController.onRadarScan((RadarScanMsg) msg);
                case RadarTargetMsg.ID -> radarController.onRadarTarget((RadarTargetMsg) msg);
                case RotorTelemetryMsg.ID -> rotorController.onRotorTelemetry((RotorTelemetryMsg) msg);
                case LidarDataMsg.ID -> hardwareDataController.onLidarData((LidarDataMsg) msg);
                case ImuDataMsg.ID -> hardwareDataController.onImuData((ImuDataMsg) msg);
                default -> { /* SYSTEM_TIME / HOME_POSITION etc.: not needed yet */ }
            }
        } catch (RuntimeException e) {
            // Decode errors must never kill the UDP receive loop
            log.debug("Failed to process frame msgId={} from sysid={}: {}",
                    frame.getMessageId(), frame.getSystemId(), e.getMessage());
        }
    }

    private void onHeartbeat(int sysid, Heartbeat hb) {
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

    private void onSysStatus(int sysid, SysStatus st) {
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
    private void onRadioStatus(int sysid, RadioStatus rs) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        if (rs.rssi != RadioStatus.INVALID) {
            s.rssiDbm = RadioEnvironmentDbm.fromSik(rs.rssi);
        }
        if (rs.remrssi != RadioStatus.INVALID) {
            s.remRssiDbm = RadioEnvironmentDbm.fromSik(rs.remrssi);
        }
    }

    private void onGps(int sysid, GpsRawInt g) {
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

    private void onAttitude(int sysid, Attitude a) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.roll = Math.toDegrees(a.roll);
        s.pitch = Math.toDegrees(a.pitch);
        s.yaw = Math.toDegrees(a.yaw);
    }

    private void onPosition(int sysid, GlobalPositionInt p) {
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

    private void onVfrHud(int sysid, VfrHud h) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.groundspeed = h.groundspeed;
        s.airspeed = h.airspeed;
        s.climb = h.climb;
        if (h.heading >= 0) {
            s.heading = h.heading;
        }
        s.throttle = h.throttle;
    }

    private void onMissionCurrent(int sysid, MissionCurrent m) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.missionSeq = m.seq;
        s.missionTotal = m.total;
        s.missionState = m.missionState;
    }

    private void onStatustext(int sysid, Statustext t) {
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
    private void onEnvironmentAlert(int sysid, EnvironmentAlert msg) {
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
    private void onEnvironmentStatus(int sysid, EnvironmentStatus msg) {
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
}

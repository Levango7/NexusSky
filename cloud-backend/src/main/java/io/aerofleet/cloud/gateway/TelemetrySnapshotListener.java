package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.AlertBus;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Attitude;
import io.aerofleet.mavlink.messages.AutopilotVersion;
import io.aerofleet.mavlink.messages.EnvironmentAlert;
import io.aerofleet.mavlink.messages.EnvironmentStatus;
import io.aerofleet.mavlink.messages.ExtendedSysState;
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
        s.autopilot = hb.autopilot;
        s.armed = (hb.baseMode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;
        s.mode = modeLabel(hb.autopilot, hb.customMode);
        if (!s.online) {
            s.online = true;
            log.info("Drone back online: sysid={}", sysid);
        }
    }

    /**
     * 按固件口径翻译 custom_mode。两种固件用的是完全不同的枚举，硬解会出危险误报：
     * 真机 SITL 实测 ArduCopter 进 GUIDED（custom_mode=4）被 PX4 表解成 "RTL"。
     * PX4 的口径是打包字（见 {@link #px4NavStateLabel}）；autopilot 未知（-1，
     * 尚未收到心跳）时同样按 PX4 打包字口径。
     */
    static String modeLabel(int autopilot, int customMode) {
        return autopilot == MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA
                ? arduCopterModeLabel(customMode)
                : px4NavStateLabel(customMode);
    }

    /**
     * ArduCopter flight-mode labels（custom_mode of an ArduPilot copter heartbeat，
     * 取自 ArduCopter/mode.h 的 mode 编号）。注意 ArduPlane/Rover 编号不同，
     * 车队当前只有多旋翼，机型的专用表等接入再补。
     */
    static String arduCopterModeLabel(int customMode) {
        return switch (customMode) {
            case 0 -> "STABILIZE";
            case 1 -> "ACRO";
            case 2 -> "ALT_HOLD";
            case 3 -> "AUTO";
            case 4 -> "GUIDED";
            case 5 -> "LOITER";
            case 6 -> "RTL";
            case 7 -> "CIRCLE";
            case 9 -> "LAND";
            case 11 -> "DRIFT";
            case 13 -> "SPORT";
            case 14 -> "FLIP";
            case 15 -> "AUTOTUNE";
            case 16 -> "POSHOLD";
            case 17 -> "BRAKE";
            case 18 -> "THROW";
            case 19 -> "AVOID_ADSB";
            case 20 -> "GUIDED_NOGPS";
            case 21 -> "SMART_RTL";
            case 22 -> "FLOWHOLD";
            case 23 -> "FOLLOW";
            case 24 -> "ZIGZAG";
            case 25 -> "SYSTEMID";
            case 26 -> "AUTOROTATE";
            case 27 -> "AUTO_RTL";
            case 28 -> "TURTLE";
            default -> "UNKNOWN";
        };
    }

    /**
     * PX4 nav-state labels：真 PX4 的心跳把 {@code (main_mode << 16) | (sub_mode << 24)}
     * 打包成 custom_mode（PX4 源码 px4_custom_mode.h 联合体 + mavlink_messages.cpp
     * get_px4_custom_mode()；SITL 实测地面待命 = 0x03040000 = AUTO/LOITER）。
     * <p>
     * 此前这张表按裸值 0..22 查表（drone-sim 的历史口径），真 PX4 的打包字一个都
     * 命中不了 → 恒 "UNKNOWN"。改为打包字解码后 sim 也同步发打包字（单一口径）。
     * <p>
     * 打包字有编码缺口要据实呈现：v1.13 的 get_px4_custom_mode() 对 AUTO_READY/
     * UNUSED/RATTITUDE 等 nav_state 不填字段，data 保持 0——0 一律 UNKNOWN，
     * 不猜测成某个具体模式。
     */
    static String px4NavStateLabel(int customMode) {
        int mainMode = (customMode >>> 16) & 0xFF;
        int subMode = (customMode >>> 24) & 0xFF;
        return switch (mainMode) {
            case 1 -> "MANUAL";
            case 2 -> "ALTITUDE";                     // PX4 称 ALTCTL
            case 3 -> subMode == 1 ? "ORBIT" : "POSITION";   // POSCTL / POSCTL_ORBIT
            case 4 -> switch (subMode) {              // AUTO 主模式看子模式
                case 1 -> "AUTO_READY";
                case 2, 10 -> "TAKEOFF";              // AUTO_TAKEOFF / VTOL_TAKEOFF
                case 3 -> "HOLD";                     // AUTO_LOITER（QGC 里显示 "Hold"）
                case 4 -> "MISSION";
                case 5 -> "RTL";
                case 6 -> "LAND";
                case 8 -> "FOLLOW";
                case 9 -> "PRECLAND";
                default -> "AUTO";                    // 含 7（保留值，勿用）
            };
            case 5 -> "ACRO";
            case 6 -> "OFFBOARD";
            case 7 -> "STABILIZED";
            case 8 -> "RATTITUDE";
            default -> "UNKNOWN";                     // main=0（PX4 打包缺口）/ 未知
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

    /**
     * 飞行阶段（EXTENDED_SYS_STATE，2026-10-24）。
     * <p>
     * 之前这条消息在 {@code MavlinkMessage.decode()} 里落到 default 返回 null，被整帧丢弃——
     * 于是 GCS 上"起飞中 / 降落中"只能靠 HEARTBEAT.custom_mode 猜，而 custom_mode 是
     * 固件相关的导航状态（PX4 与 ArduPilot 枚举不同）。接上后 landed_state 是与机型无关的真值。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ExtendedSysState).ID")
    public void onExtendedSysState(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        ExtendedSysState e = (ExtendedSysState) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.landedState = e.landedState;
        s.vtolState = e.vtolState;
    }

    /**
     * 固件版本与能力位（AUTOPILOT_VERSION，2026-10-25）。
     * <p>
     * PX4 按请求（或周期）下发。接上之前这条消息同样落在 {@code decode()} 的 default 里被整帧丢弃，
     * 于是 GCS 无法回答"这是哪版固件、支不支持某项能力"。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.AutopilotVersion).ID")
    public void onAutopilotVersion(MavlinkMessageEvent event) {
        int sysid = event.getSysid();
        AutopilotVersion v = (AutopilotVersion) event.getMessage();
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.flightSwVersion = v.flightSwVersion;
        s.firmwareVersion = v.firmwareVersion();
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
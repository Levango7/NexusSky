package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.vision.RadarController;
import io.aerofleet.cloud.vision.RotorController;
import io.aerofleet.mavlink.enums.ScanMode;
import io.aerofleet.mavlink.messages.ImuDataMsg;
import io.aerofleet.mavlink.messages.LidarDataMsg;
import io.aerofleet.mavlink.messages.RadarTargetMsg;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 硬件数据 REST 端点（M4 硬件抽象，FR-24~FR-28/DFX 4.5）。
 * <p>
 * 独立路径前缀 /api/v1/radar/*、/api/v1/rotor/*、/api/v1/lidar/*、/api/v1/imu/*，
 * 既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/v1/radar/config           配置雷达扫描（FR-24）
 * GET  /api/v1/radar/config/{sysid}   查询雷达配置（FR-24）
 * GET  /api/v1/radar/status/{sysid}   查询扫描状态（FR-24）
 * GET  /api/v1/radar/targets/{sysid}  查询目标列表（FR-25）
 * POST /api/v1/rotor/config           配置气动参数（FR-26）
 * GET  /api/v1/rotor/telemetry/{sysid} 查询气动遥测（FR-26）
 * GET  /api/v1/lidar/data/{sysid}     查询 LiDAR 数据（FR-27）
 * GET  /api/v1/imu/data/{sysid}       查询 IMU 数据（FR-28）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class HardwareDataController {


    private final RadarController radarController;
    private final RotorController rotorController;
    private final ConcurrentHashMap<Integer, LidarDataMsg> lidarCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ImuDataMsg> imuCache = new ConcurrentHashMap<>();

    public HardwareDataController(RadarController radarController,
                                  RotorController rotorController) {
        this.radarController = radarController;
        this.rotorController = rotorController;
    }

    // ===== 雷达端点 =====

    /** FR-24 配置雷达扫描。 */
    @PostMapping("/radar/config")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> configureRadar(@RequestBody Map<String, Object> body) {
        try {
            int sysid = num(body, "sysid").intValue();
            // FR: sysid 范围校验（MAVLink sysid 为 u8，有效范围 1~255）
            if (sysid < 1 || sysid > 255) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "sysid must be in [1, 255]"));
            }
            ScanMode mode = ScanMode.valueOf(str(body, "mode"));
            double azimCenter = num(body, "azimCenter").doubleValue();
            double azimWidth = num(body, "azimWidth").doubleValue();
            double elevCenter = num(body, "elevCenter").doubleValue();
            double beamWidth = num(body, "beamWidth").doubleValue();
            double range = num(body, "range").doubleValue();
            int scanPeriodMs = num(body, "scanPeriodMs").intValue();
            boolean enabled = body.get("enabled") instanceof Boolean b ? b : false;

            RadarController.RadarScanConfig cfg = new RadarController.RadarScanConfig(
                    sysid, mode, azimCenter, azimWidth, elevCenter, beamWidth,
                    range, scanPeriodMs, enabled);
            RadarController.RadarScanConfig stored = radarController.configure(cfg);
            return ResponseEntity.ok(radarConfigView(stored));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** FR-24 查询雷达配置。 */
    @GetMapping("/radar/config/{sysid}")
    public ResponseEntity<Map<String, Object>> getRadarConfig(@PathVariable("sysid") int sysid) {
        RadarController.RadarScanConfig cfg = radarController.getConfig(sysid);
        if (cfg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", "sysid " + sysid + " radar not configured"));
        }
        return ResponseEntity.ok(radarConfigView(cfg));
    }

    /** FR-24 查询扫描状态。 */
    @GetMapping("/radar/status/{sysid}")
    public ResponseEntity<Map<String, Object>> getRadarStatus(@PathVariable("sysid") int sysid) {
        RadarController.RadarScanStatus s = radarController.getStatus(sysid);
        return ResponseEntity.ok(radarStatusView(sysid, s));
    }

    /** FR-25 查询目标列表。 */
    @GetMapping("/radar/targets/{sysid}")
    public ResponseEntity<Map<String, Object>> getRadarTargets(@PathVariable("sysid") int sysid) {
        List<RadarTargetMsg> targets = radarController.getTargets(sysid);
        List<Map<String, Object>> targetList = new ArrayList<>();
        for (RadarTargetMsg t : targets) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("targetId", t.targetId);
            tv.put("distance", t.distance);
            tv.put("azimDeg", t.azimDeg);
            tv.put("elevDeg", t.elevDeg);
            tv.put("radialVelocity", t.radialVelocity);
            tv.put("rcs", t.rcs);
            tv.put("trackState", t.trackState);
            tv.put("timestamp", t.timestamp);
            targetList.add(tv);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("count", targetList.size());
        v.put("targets", targetList);
        return ResponseEntity.ok(v);
    }

    // ===== 动力端点 =====

    /** FR-26 配置气动参数。 */
    @PostMapping("/rotor/config")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> configureRotor(@RequestBody Map<String, Object> body) {
        try {
            int sysid = num(body, "sysid").intValue();
            int rotorCount = num(body, "rotorCount").intValue();
            double diameter = num(body, "diameter").doubleValue();
            double pitch = num(body, "pitch").doubleValue();
            double maxRpm = num(body, "maxRpm").doubleValue();
            double airDensity = num(body, "airDensity").doubleValue();

            RotorController.RotorConfig cfg = new RotorController.RotorConfig(
                    sysid, rotorCount, diameter, pitch, maxRpm, airDensity);
            RotorController.RotorConfig stored = rotorController.configure(cfg);
            return ResponseEntity.ok(rotorConfigView(stored));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** FR-26 查询气动遥测。 */
    @GetMapping("/rotor/telemetry/{sysid}")
    public ResponseEntity<Map<String, Object>> getRotorTelemetry(@PathVariable("sysid") int sysid) {
        RotorController.RotorTelemetry t = rotorController.getTelemetry(sysid);
        return ResponseEntity.ok(rotorTelemetryView(sysid, t));
    }

    // ===== LiDAR 端点 =====

    /** FR-27 查询 LiDAR 数据。 */
    @GetMapping("/lidar/data/{sysid}")
    public ResponseEntity<Map<String, Object>> getLidarData(@PathVariable("sysid") int sysid) {
        LidarDataMsg msg = lidarCache.get(sysid);
        if (msg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", "sysid " + sysid + " no LiDAR data"));
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("nearestDistance", msg.nearestDistance);
        v.put("pointCount", msg.pointCount);
        v.put("density", msg.density);
        v.put("avgIntensity", msg.avgIntensity);
        return ResponseEntity.ok(v);
    }

    // ===== IMU 端点 =====

    /** FR-28 查询 IMU 数据。 */
    @GetMapping("/imu/data/{sysid}")
    public ResponseEntity<Map<String, Object>> getImuData(@PathVariable("sysid") int sysid) {
        ImuDataMsg msg = imuCache.get(sysid);
        if (msg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", "sysid " + sysid + " no IMU data"));
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("accelX", msg.accelX);
        v.put("accelY", msg.accelY);
        v.put("accelZ", msg.accelZ);
        v.put("gyroX", msg.gyroX);
        v.put("gyroY", msg.gyroY);
        v.put("gyroZ", msg.gyroZ);
        v.put("magX", msg.magX);
        v.put("magY", msg.magY);
        v.put("magZ", msg.magZ);
        v.put("tempC", msg.tempC);
        return ResponseEntity.ok(v);
    }

    // ===== 遥测路由回调 =====

    /** 接收 LidarDataMsg 并缓存（供 REST 查询）。 */
    public void onLidarData(LidarDataMsg msg) {
        lidarCache.put(msg.sysid, msg);
    }

    /** 接收 ImuDataMsg 并缓存（供 REST 查询）。 */
    public void onImuData(ImuDataMsg msg) {
        imuCache.put(msg.sysid, msg);
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理硬件消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.LidarDataMsg).ID")
    public void onLidarDataEvent(MavlinkMessageEvent event) {
        onLidarData((LidarDataMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ImuDataMsg).ID")
    public void onImuDataEvent(MavlinkMessageEvent event) {
        onImuData((ImuDataMsg) event.getMessage());
    }

    // ===== 视图辅助 =====

    private Map<String, Object> radarConfigView(RadarController.RadarScanConfig cfg) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", cfg.sysid());
        v.put("mode", cfg.mode().name());
        v.put("azimCenter", cfg.azimCenter());
        v.put("azimWidth", cfg.azimWidth());
        v.put("elevCenter", cfg.elevCenter());
        v.put("beamWidth", cfg.beamWidth());
        v.put("range", cfg.range());
        v.put("scanPeriodMs", cfg.scanPeriodMs());
        v.put("enabled", cfg.enabled());
        return v;
    }

    private Map<String, Object> radarStatusView(int sysid, RadarController.RadarScanStatus s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("mode", s.mode);
        v.put("beamAzim", s.beamAzim);
        v.put("beamElev", s.beamElev);
        v.put("targetCount", s.targetCount);
        v.put("lastScanTime", s.lastScanTime);
        return v;
    }

    private Map<String, Object> rotorConfigView(RotorController.RotorConfig cfg) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", cfg.sysid());
        v.put("rotorCount", cfg.rotorCount());
        v.put("diameter", cfg.diameter());
        v.put("pitch", cfg.pitch());
        v.put("maxRpm", cfg.maxRpm());
        v.put("airDensity", cfg.airDensity());
        return v;
    }

    private Map<String, Object> rotorTelemetryView(int sysid, RotorController.RotorTelemetry t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("rotorIndex", t.rotorIndex);
        v.put("rpm", t.rpm);
        v.put("thrust", t.thrust);
        v.put("power", t.power);
        v.put("totalThrust", t.totalThrust);
        v.put("totalPower", t.totalPower);
        v.put("lastUpdateTime", t.lastUpdateTime);
        return v;
    }

    private static Number num(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n;
        throw new IllegalArgumentException("missing numeric field '" + key + "'");
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("missing string field '" + key + "'");
    }
}
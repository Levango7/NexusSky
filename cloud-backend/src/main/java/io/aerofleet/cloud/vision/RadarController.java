package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.ScanMode;

import io.aerofleet.mavlink.messages.RadarScanMsg;
import io.aerofleet.mavlink.messages.RadarTargetMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 雷达控制核心（M4 硬件抽象，FR-03/FR-24/FR-25/DFX 4.2）。
 * <p>
 * 持有 {@code ConcurrentHashMap<sysid, RadarScanConfig>}（配置）、
 * {@code ConcurrentHashMap<sysid, RadarScanStatus>}（扫描状态）与
 * {@code ConcurrentHashMap<sysid, List<RadarTargetMsg>>}（目标缓存）。
 * <p>
 * 配置经 REST 入站后存储并经 {@link DroneCommandService} 下发扫描模式切换命令（FR-03）。
 * 目标报告由 drone-sim 上报 {@link RadarTargetMsg} 后缓存（FR-25）。
 * <p>
 * 并发安全（DFX 4.2）：ConcurrentHashMap + volatile 字段。
 * 单机故障隔离（DFX 4.2）：per-sysid try-catch。
 */
@Service
public class RadarController {

    private static final Logger log = LoggerFactory.getLogger(RadarController.class);

    /** 自定义命令 ID：雷达扫描配置（NexusSky 扩展，不与 MAVLink common 冲突）。 */
    private static final int MAV_CMD_NEXUS_RADAR_CONFIG = 420;

    /** 每架机维护的目标列表上限，超过时淘汰最旧目标。 */
    private static final int MAX_TARGETS_PER_DRONE = 64;

    private final ConcurrentHashMap<Integer, RadarScanConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, RadarScanStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<RadarTargetMsg>> targets = new ConcurrentHashMap<>();
    private final DroneCommandService commands;

    public RadarController(DroneCommandService commands) {
        this.commands = commands;
    }

    /**
     * FR-03 雷达扫描配置。
     * <p>
     * 校验参数（由 RadarScanConfig record 紧凑构造校验）→ 存储配置 →
     * 经 DroneCommandService 下发扫描模式切换命令。
     */
    public RadarScanConfig configure(RadarScanConfig config) {
        configs.put(config.sysid(), config);
        log.info("radar config: sysid={} mode={} range={}m scanPeriod={}ms enabled={}",
                config.sysid(), config.mode(), config.range(),
                config.scanPeriodMs(), config.enabled());
        if (config.enabled()) {
            try {
                // 下发雷达配置命令：p1=mode, p2=azimCenter, p3=azimWidth, p4=elevCenter,
                // p5=beamWidth, p6=range, p7=scanPeriodMs
                commands.command(config.sysid(), MAV_CMD_NEXUS_RADAR_CONFIG,
                        config.mode().ordinal(),
                        (float) config.azimCenter(), (float) config.azimWidth(),
                        (float) config.elevCenter(), (float) config.beamWidth(),
                        (float) config.range(), (float) config.scanPeriodMs());
            } catch (Exception e) {
                // DFX 4.2 单机故障隔离：命令下发失败不阻塞配置存储
                log.warn("radar config command failed for sysid={}: {}", config.sysid(), e.getMessage());
            }
        }
        return config;
    }

    /** FR-24 查询配置。 */
    public RadarScanConfig getConfig(int sysid) {
        return configs.get(sysid);
    }

    /** FR-24 查询扫描状态。 */
    public RadarScanStatus getStatus(int sysid) {
        return statuses.computeIfAbsent(sysid, k -> new RadarScanStatus());
    }

    /** FR-25 查询当前扫描周期内探测到的目标列表。 */
    public List<RadarTargetMsg> getTargets(int sysid) {
        List<RadarTargetMsg> list = targets.get(sysid);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * 接收 RadarScanMsg 后更新扫描状态（FR-18）。
     * <p>
     * 由遥测路由器调用（从 MAVLink 帧解码后）。
     */
    public void onRadarScan(RadarScanMsg msg) {
        RadarScanStatus status = statuses.computeIfAbsent(msg.sysid, k -> new RadarScanStatus());
        status.mode = msg.mode;
        status.beamAzim = msg.beamAzim;
        status.beamElev = msg.beamElev;
        status.targetCount = msg.targetCount;
        status.lastScanTime = msg.timestamp;
    }

    /**
     * 接收 RadarTargetMsg 后缓存目标（FR-19/FR-25）。
     * <p>
     * 由遥测路由器调用。每架机维护一个目标列表，新目标追加，超过 64 个时淘汰最旧。
     */
    public void onRadarTarget(RadarTargetMsg msg) {
        targets.compute(msg.sysid, (k, existing) -> {
            List<RadarTargetMsg> list = existing == null
                    ? new ArrayList<>() : new ArrayList<>(existing);
            list.add(msg);
            while (list.size() > MAX_TARGETS_PER_DRONE) {
                list.remove(0);
            }
            return list;
        });
    }

    /** 所有扫描状态（供 HardwarePusher 1Hz 推送）。 */
    public Map<Integer, RadarScanStatus> allStatuses() {
        return Collections.unmodifiableMap(statuses);
    }

    /** 所有配置（供查询）。 */
    public Map<Integer, RadarScanConfig> allConfigs() {
        return Collections.unmodifiableMap(configs);
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理雷达消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.RadarScanMsg).ID")
    public void onRadarScanEvent(MavlinkMessageEvent event) {
        onRadarScan((RadarScanMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.RadarTargetMsg).ID")
    public void onRadarTargetEvent(MavlinkMessageEvent event) {
        onRadarTarget((RadarTargetMsg) event.getMessage());
    }

    // =====================================================================
    // 嵌套 DTO
    // =====================================================================

    /**
     * 雷达扫描配置 DTO（FR-03/数据约束 6.1）。
     * <p>
     * 不可变 record，紧凑构造校验所有参数在物理范围内。
     */
    public record RadarScanConfig(int sysid, ScanMode mode, double azimCenter, double azimWidth,
                                  double elevCenter, double beamWidth, double range,
                                  int scanPeriodMs, boolean enabled) {
        public RadarScanConfig {
            if (sysid < 1 || sysid > 254) {
                throw new IllegalArgumentException("sysid must be 1-254, got " + sysid);
            }
            if (mode == null) {
                throw new IllegalArgumentException("mode must be non-null");
            }
            if (azimCenter < 0 || azimCenter > 359) {
                throw new IllegalArgumentException("azimCenter must be 0-359, got " + azimCenter);
            }
            if (azimWidth < 1 || azimWidth > 360) {
                throw new IllegalArgumentException("azimWidth must be 1-360, got " + azimWidth);
            }
            if (elevCenter < -90 || elevCenter > 90) {
                throw new IllegalArgumentException("elevCenter must be -90~90, got " + elevCenter);
            }
            if (beamWidth < 1 || beamWidth > 30) {
                throw new IllegalArgumentException("beamWidth must be 1-30, got " + beamWidth);
            }
            if (range < 10 || range > 10000) {
                throw new IllegalArgumentException("range must be 10-10000m, got " + range);
            }
            if (scanPeriodMs < 100 || scanPeriodMs > 10000) {
                throw new IllegalArgumentException(
                        "scanPeriodMs must be 100-10000ms, got " + scanPeriodMs);
            }
        }
    }

    /**
     * 雷达扫描状态（FR-24/DFX 4.2）。
     * <p>
     * 各机实时扫描状态，volatile 字段保证 REST 线程与遥测线程并发读写安全。
     */
    public static class RadarScanStatus {
        public volatile int mode = 0;           // ScanMode.ordinal()
        public volatile float beamAzim = 0;
        public volatile float beamElev = 0;
        public volatile int targetCount = 0;
        public volatile long lastScanTime = 0;

        public RadarScanStatus() {
        }
    }
}
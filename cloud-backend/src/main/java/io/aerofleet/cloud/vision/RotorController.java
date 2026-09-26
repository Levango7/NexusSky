package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.messages.RotorTelemetryMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动力控制核心（M4 硬件抽象，FR-26/DFX 4.2）。
 * <p>
 * 持有 {@code ConcurrentHashMap<sysid, RotorConfig>}（配置）与
 * {@code ConcurrentHashMap<sysid, RotorTelemetry>}（气动遥测）。
 * <p>
 * 配置经 REST 入站后存储并经 {@link DroneCommandService} 下发动力参数调整命令（FR-26）。
 * 气动遥测由 drone-sim 上报 {@link RotorTelemetryMsg} 后缓存（FR-26）。
 * <p>
 * 并发安全（DFX 4.2）：ConcurrentHashMap + volatile 字段。
 * 单机故障隔离（DFX 4.2）：per-sysid try-catch。
 */
@Service
public class RotorController {

    private static final Logger log = LoggerFactory.getLogger(RotorController.class);

    /** 自定义命令 ID：旋翼气动配置（NexusSky 扩展，不与 MAVLink common 冲突）。 */
    private static final int MAV_CMD_NEXUS_ROTOR_CONFIG = 421;

    private final ConcurrentHashMap<Integer, RotorConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, RotorTelemetry> telemetries = new ConcurrentHashMap<>();
    private final DroneCommandService commands;

    public RotorController(DroneCommandService commands) {
        this.commands = commands;
    }

    /**
     * FR-26 旋翼气动配置。
     * <p>
     * 校验参数（由 RotorConfig record 紧凑构造校验）→ 存储配置 →
     * 经 DroneCommandService 下发动力参数调整命令。
     */
    public RotorConfig configure(RotorConfig config) {
        configs.put(config.sysid(), config);
        log.info("rotor config: sysid={} rotors={} diameter={}m maxRpm={} airDensity={}kg/m³",
                config.sysid(), config.rotorCount(), config.diameter(),
                config.maxRpm(), config.airDensity());
        try {
            // 下发气动配置命令：p1=rotorCount, p2=diameter, p3=pitch, p4=maxRpm, p5=airDensity
            commands.command(config.sysid(), MAV_CMD_NEXUS_ROTOR_CONFIG,
                    config.rotorCount(), (float) config.diameter(), (float) config.pitch(),
                    (float) config.maxRpm(), (float) config.airDensity(), 0, 0);
        } catch (Exception e) {
            // DFX 4.2 单机故障隔离：命令下发失败不阻塞配置存储
            log.warn("rotor config command failed for sysid={}: {}", config.sysid(), e.getMessage());
        }
        return config;
    }

    /** FR-26 查询配置。 */
    public RotorConfig getConfig(int sysid) {
        return configs.get(sysid);
    }

    /** FR-26 查询气动遥测。 */
    public RotorTelemetry getTelemetry(int sysid) {
        return telemetries.computeIfAbsent(sysid, k -> new RotorTelemetry());
    }

    /**
     * 接收 RotorTelemetryMsg 后更新遥测缓存（FR-20）。
     * <p>
     * 由遥测路由器调用（从 MAVLink 帧解码后）。
     */
    public void onRotorTelemetry(RotorTelemetryMsg msg) {
        RotorTelemetry tel = telemetries.computeIfAbsent(msg.sysid, k -> new RotorTelemetry());
        tel.rotorIndex = msg.rotorIndex;
        tel.rpm = msg.rpm;
        tel.thrust = msg.thrust;
        tel.power = msg.power;
        tel.totalThrust = msg.totalThrust;
        tel.totalPower = msg.totalPower;
        tel.lastUpdateTime = System.currentTimeMillis();
    }

    /** 所有遥测（供 HardwarePusher 1Hz 推送）。 */
    public Map<Integer, RotorTelemetry> allTelemetries() {
        return Collections.unmodifiableMap(telemetries);
    }

    /** 所有配置（供查询）。 */
    public Map<Integer, RotorConfig> allConfigs() {
        return Collections.unmodifiableMap(configs);
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理旋翼消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.RotorTelemetryMsg).ID")
    public void onRotorTelemetryEvent(MavlinkMessageEvent event) {
        onRotorTelemetry((RotorTelemetryMsg) event.getMessage());
    }

    // =====================================================================
    // 嵌套 DTO
    // =====================================================================

    /**
     * 旋翼气动配置 DTO（FR-26/数据约束 6.3）。
     * <p>
     * 不可变 record，紧凑构造校验所有参数在物理范围内。
     */
    public record RotorConfig(int sysid, int rotorCount, double diameter, double pitch,
                              double maxRpm, double airDensity) {
        public RotorConfig {
            if (sysid < 1 || sysid > 254) {
                throw new IllegalArgumentException("sysid must be 1-254, got " + sysid);
            }
            if (rotorCount < 1 || rotorCount > 12) {
                throw new IllegalArgumentException("rotorCount must be 1-12, got " + rotorCount);
            }
            if (diameter <= 0) {
                throw new IllegalArgumentException("diameter must be > 0, got " + diameter);
            }
            if (pitch < 0 || pitch > 30) {
                throw new IllegalArgumentException("pitch must be 0-30, got " + pitch);
            }
            if (maxRpm < 0 || maxRpm > 20000) {
                throw new IllegalArgumentException("maxRpm must be 0-20000, got " + maxRpm);
            }
            if (airDensity <= 0) {
                throw new IllegalArgumentException("airDensity must be > 0, got " + airDensity);
            }
        }
    }

    /**
     * 旋翼气动遥测（FR-26/DFX 4.2）。
     * <p>
     * 各机实时气动遥测，volatile 字段保证 REST 线程与遥测线程并发读写安全。
     */
    public static class RotorTelemetry {
        public volatile int rotorIndex = 0;
        public volatile float rpm = 0;
        public volatile float thrust = 0;
        public volatile float power = 0;
        public volatile float totalThrust = 0;
        public volatile float totalPower = 0;
        public volatile long lastUpdateTime = 0;

        public RotorTelemetry() {
        }
    }
}
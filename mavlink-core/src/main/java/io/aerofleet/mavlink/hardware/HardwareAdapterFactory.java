package io.aerofleet.mavlink.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 硬件适配器工厂：根据连接字符串前缀自动选择适配器类型。
 * <p>
 * 连接字符串前缀映射规则：
 * <ul>
 *   <li>udp:// → Px4Adapter（PX4 SITL 默认使用 UDP）</li>
 *   <li>tcp:// → ArduPilotAdapter（ArduPilot SITL 默认使用 TCP）</li>
 *   <li>serial:// → ArduPilotAdapter（串口连接通常用于真机）</li>
 *   <li>sim:// → SimulatedHardwareAdapter（纯模拟，无需硬件）</li>
 * </ul>
 * <p>
 * 对于 tcp:// 连接，可通过参数显式指定 PX4 或 ArduPilot。
 */
public final class HardwareAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(HardwareAdapterFactory.class);

    private HardwareAdapterFactory() {
    }

    /**
     * 根据连接字符串创建适配器。
     *
     * @param connectionUrl 连接字符串
     * @return 对应的硬件适配器实例
     * @throws IllegalArgumentException 如果连接字符串格式不支持
     */
    public static HardwareAdapter create(String connectionUrl) {
        if (connectionUrl == null || connectionUrl.isBlank()) {
            throw new IllegalArgumentException("连接字符串不能为空");
        }

        if (connectionUrl.startsWith("sim://")) {
            log.info("创建 SimulatedHardwareAdapter（模拟模式）");
            return new SimulatedHardwareAdapter();
        }

        if (connectionUrl.startsWith("udp://")) {
            log.info("创建 Px4Adapter（UDP 连接）");
            return new Px4Adapter();
        }

        if (connectionUrl.startsWith("tcp://")) {
            log.info("创建 ArduPilotAdapter（TCP 连接）");
            return new ArduPilotAdapter();
        }

        if (connectionUrl.startsWith("serial://")) {
            log.info("创建 ArduPilotAdapter（串口连接）");
            return new ArduPilotAdapter();
        }

        throw new IllegalArgumentException(
                "不支持的连接格式: " + connectionUrl + "。支持的前缀: udp://, tcp://, serial://, sim://");
    }

    /**
     * 显式指定适配器类型创建适配器。
     *
     * @param adapterType   适配器类型："PX4", "ArduPilot", "Simulated"
     * @param connectionUrl 连接字符串
     * @return 对应的硬件适配器实例
     * @throws IllegalArgumentException 如果适配器类型不支持
     */
    public static HardwareAdapter create(String adapterType, String connectionUrl) {
        if (adapterType == null || adapterType.isBlank()) {
            throw new IllegalArgumentException("适配器类型不能为空");
        }

        return switch (adapterType.toUpperCase()) {
            case "PX4" -> {
                log.info("显式创建 Px4Adapter");
                yield new Px4Adapter();
            }
            case "ARDUPILOT", "APM" -> {
                log.info("显式创建 ArduPilotAdapter");
                yield new ArduPilotAdapter();
            }
            case "SIMULATED", "SIM" -> {
                log.info("显式创建 SimulatedHardwareAdapter");
                yield new SimulatedHardwareAdapter();
            }
            default -> throw new IllegalArgumentException(
                    "不支持的适配器类型: " + adapterType + "。支持: PX4, ArduPilot, Simulated");
        };
    }
}
package io.aerofleet.linksim;

/**
 * 通信介质枚举（FR-01 异构链路桥接）。
 * <p>
 * 每种介质定义带宽、延迟、覆盖范围、功耗等特性参数，
 * 供链路桥接节点在跨介质转发时计算额外延迟与选择最优路径。
 * <p>
 * 参数取自公开行业经验值（数量级正确，用于仿真协议韧性）。
 */
public enum LinkType {

    /** WiFi：高带宽、低延迟、短覆盖、中等功耗 */
    WIFI("WiFi", 400_000, 2, 500, 500, "高带宽短距离"),

    /** LTE：中等带宽、中等延迟、广覆盖、较高功耗 */
    LTE("LTE", 80_000, 45, 10_000, 1500, "广覆盖蜂窝"),

    /** LoRa：低带宽、高延迟、超广覆盖、低功耗 */
    LORA("LoRa", 5_760, 60, 15_000, 100, "低功耗远距离"),

    /** Satellite：中等带宽、高延迟、全球覆盖、高功耗 */
    SATELLITE("Satellite", 600_000, 35, 500_000, 5000, "全球覆盖卫星");

    /** 介质显示名称 */
    public final String displayName;
    /** 典型带宽（字节/秒） */
    public final long bandwidthBytesPerSec;
    /** 典型单向延迟（毫秒） */
    public final long delayMs;
    /** 典型覆盖范围（米） */
    public final long coverageRangeM;
    /** 典型功耗（毫瓦） */
    public final long powerConsumptionMw;
    /** 介质特性描述 */
    public final String characteristic;

    LinkType(String displayName, long bandwidthBytesPerSec, long delayMs,
             long coverageRangeM, long powerConsumptionMw, String characteristic) {
        this.displayName = displayName;
        this.bandwidthBytesPerSec = bandwidthBytesPerSec;
        this.delayMs = delayMs;
        this.coverageRangeM = coverageRangeM;
        this.powerConsumptionMw = powerConsumptionMw;
        this.characteristic = characteristic;
    }

    /**
     * 跨介质转发额外延迟（毫秒）。
     * <p>
     * DFX 6.1.4 约束：
     * <ul>
     *   <li>WiFi → LoRa：100ms</li>
     *   <li>WiFi → Satellite：200ms</li>
     *   <li>其他组合：取两介质延迟差值的绝对值，最小 50ms</li>
     * </ul>
     *
     * @param from 源介质
     * @param to 目标介质
     * @return 跨介质转发额外延迟（毫秒）
     */
    public static long crossMediumDelay(LinkType from, LinkType to) {
        if (from == to) {
            return 0;
        }
        // DFX 6.1.4 明确规定的延迟对
        if (from == WIFI && to == LORA) {
            return 100;
        }
        if (from == WIFI && to == SATELLITE) {
            return 200;
        }
        // 反向也适用（对称）
        if (from == LORA && to == WIFI) {
            return 100;
        }
        if (from == SATELLITE && to == WIFI) {
            return 200;
        }
        // 其他组合：取两介质延迟差值绝对值，最小 50ms
        long diff = Math.abs(from.delayMs - to.delayMs);
        return Math.max(diff, 50);
    }

    /**
     * 判断该介质是否适合作为桥接介质（支持多介质同时监听）。
     * <p>
     * WiFi 和 LTE 适合作为桥接节点介质（功耗和带宽可承受多介质监听）；
     * LoRa 和 Satellite 不适合单独作为桥接节点（功耗或带宽受限）。
     */
    public boolean isBridgeCapable() {
        return this == WIFI || this == LTE;
    }
}
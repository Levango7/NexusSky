package io.aerofleet.sim.sat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卫星链路带宽分配器（FR-24 卫星链路带宽分配）。
 * <p>
 * 当卫星链路带宽受限（<100kbps）时，按 QoS 优先级分配带宽：
 * <ul>
 *   <li>EMERGENCY — 60%</li>
 *   <li>COMMAND — 25%</li>
 *   <li>MAPPING — 10%</li>
 *   <li>ROUTINE — 5%</li>
 * </ul>
 * <p>
 * 当带宽 ≥100kbps 时不做限制分配（各优先级可使用全量带宽）。
 * <p>
 * 不可变值对象：分配结果为一次性计算快照。
 */
public final class SatBandwidthAllocator {

    /** 带宽受限阈值（bps）— 低于此值启用 QoS 比例分配。 */
    public static final long LOW_BANDWIDTH_THRESHOLD_BPS = 100_000L; // 100kbps

    /** QoS 优先级枚举（与 QoSPriorityQueue.PriorityClass 对齐）。 */
    public enum QoSClass {
        /** 搜救通讯，最高优先级。 */
        EMERGENCY(0, 0.60),
        /** 指挥通讯。 */
        COMMAND(1, 0.25),
        /** 测绘数据。 */
        MAPPING(2, 0.10),
        /** 常规遥测，最低优先级。 */
        ROUTINE(3, 0.05);

        private final int code;
        private final double allocationRatio;

        QoSClass(int code, double allocationRatio) {
            this.code = code;
            this.allocationRatio = allocationRatio;
        }

        public int code() {
            return code;
        }

        public double allocationRatio() {
            return allocationRatio;
        }

        public static QoSClass fromCode(int code) {
            return switch (code) {
                case 0 -> EMERGENCY;
                case 1 -> COMMAND;
                case 2 -> MAPPING;
                default -> ROUTINE;
            };
        }
    }

    /** 带宽分配结果。 */
    public record BandwidthAllocation(
            long totalBandwidthBps,
            boolean lowBandwidthMode,
            Map<QoSClass, Long> allocations
    ) {
        /**
         * 获取指定 QoS 等级的分配带宽。
         *
         * @param qosClass QoS 等级
         * @return 分配带宽（bps）
         */
        public long getAllocation(QoSClass qosClass) {
            return allocations.getOrDefault(qosClass, 0L);
        }
    }

    /**
     * 执行带宽分配。
     * <p>
     * 当 totalBandwidthBps < 100kbps 时，按 QoS 比例分配；
     * 当 totalBandwidthBps ≥ 100kbps 时，各等级均可使用全量带宽。
     *
     * @param totalBandwidthBps 卫星链路总可用带宽（bps）
     * @return 带宽分配结果
     */
    public BandwidthAllocation allocate(long totalBandwidthBps) {
        if (totalBandwidthBps < 0) {
            throw new IllegalArgumentException(
                    "totalBandwidthBps must be >= 0, got " + totalBandwidthBps);
        }

        boolean lowBandwidth = totalBandwidthBps < LOW_BANDWIDTH_THRESHOLD_BPS;

        Map<QoSClass, Long> allocations = new LinkedHashMap<>();
        if (lowBandwidth) {
            // 受限模式：按比例分配
            for (QoSClass qos : QoSClass.values()) {
                long allocated = (long) (totalBandwidthBps * qos.allocationRatio());
                allocations.put(qos, allocated);
            }
        } else {
            // 非受限模式：各等级均可使用全量带宽
            for (QoSClass qos : QoSClass.values()) {
                allocations.put(qos, totalBandwidthBps);
            }
        }

        return new BandwidthAllocation(
                totalBandwidthBps,
                lowBandwidth,
                Collections.unmodifiableMap(allocations)
        );
    }

    /**
     * 判断是否为低带宽模式。
     *
     * @param totalBandwidthBps 总带宽（bps）
     * @return true 若带宽 <100kbps
     */
    public boolean isLowBandwidth(long totalBandwidthBps) {
        return totalBandwidthBps < LOW_BANDWIDTH_THRESHOLD_BPS;
    }
}
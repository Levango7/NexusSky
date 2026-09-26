package io.aerofleet.sim.mesh;

import java.util.Set;

/**
 * 跨区中继策略（灾区通信隔离，FR-04）。
 * <p>
 * 跨灾区通信仅允许通过卫星（SATELLITE）或 HAPS（高空平台）中继转发，
 * 拒绝 WiFi / LoRa / LTE 等地面链路跨区转发。
 * <p>
 * 不可变值对象，策略规则在构造时确定。
 */
public final class CrossZoneRelayPolicy {

    /** 中继类型枚举。 */
    public enum RelayType {
        /** 卫星中继。 */
        SATELLITE(0),
        /** HAPS（高空平台）中继。 */
        HAPS(1),
        /** WiFi 中继（不允许跨区）。 */
        WIFI(2),
        /** LoRa 中继（不允许跨区）。 */
        LORA(3),
        /** LTE 中继（不允许跨区）。 */
        LTE(4);

        private final int code;

        RelayType(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static RelayType fromCode(int code) {
            return switch (code) {
                case 0 -> SATELLITE;
                case 1 -> HAPS;
                case 2 -> WIFI;
                case 3 -> LORA;
                default -> LTE;
            };
        }
    }

    /** 允许的跨区中继类型集合。 */
    private final Set<RelayType> allowedRelayTypes;

    /**
     * 构造跨区中继策略，默认仅允许 SATELLITE 和 HAPS。
     */
    public CrossZoneRelayPolicy() {
        this.allowedRelayTypes = Set.of(RelayType.SATELLITE, RelayType.HAPS);
    }

    /**
     * 构造跨区中继策略，自定义允许的中继类型。
     *
     * @param allowedRelayTypes 允许的跨区中继类型集合
     */
    public CrossZoneRelayPolicy(Set<RelayType> allowedRelayTypes) {
        this.allowedRelayTypes = Set.copyOf(allowedRelayTypes);
    }

    /**
     * 判定指定中继类型是否允许跨区转发（FR-04）。
     *
     * @param relayType 中继类型
     * @return true 若该中继类型允许跨区转发
     */
    public boolean isAllowed(RelayType relayType) {
        return allowedRelayTypes.contains(relayType);
    }

    /**
     * 判定指定中继类型是否拒绝跨区转发。
     *
     * @param relayType 中继类型
     * @return true 若该中继类型被拒绝跨区转发
     */
    public boolean isRejected(RelayType relayType) {
        return !isAllowed(relayType);
    }

    /**
     * 获取允许的跨区中继类型集合（不可变）。
     */
    public Set<RelayType> getAllowedRelayTypes() {
        return allowedRelayTypes;
    }

    @Override
    public String toString() {
        return "CrossZoneRelayPolicy{allowed=" + allowedRelayTypes + "}";
    }
}
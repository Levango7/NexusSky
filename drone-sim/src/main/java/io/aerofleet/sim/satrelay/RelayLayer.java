package io.aerofleet.sim.satrelay;

/**
 * 通信中继层级枚举（M7 星-空-地多层级中继，FR-5.2）。
 * <p>
 * 5 个层级：L0 地面终端、L1 低空 mesh、L2 HAPS、L3 LEO 卫星、L4 地面站/云端。
 * 各层级携带高度区间（米）与覆盖半径（km），层级越高覆盖越广但延迟越大。
 * <p>
 * L1 层由 M5 MeshRouter 提供，本组件不重新实现（FR-5.2.1.5）。
 * L0 层为端点，不承担中继转发（FR-5.2.1.6）。
 */
public enum RelayLayer {
    /** L0 地面终端：层级路径端点，不中继。 */
    L0(0, "GROUND", 0.0, 0.0, 0.0),
    /** L1 低空 mesh：100-500m，由 M5 MeshRouter 提供。 */
    L1(1, "MESH", 100.0, 500.0, 5.0),
    /** L2 HAPS 高空中继机：18-20km，覆盖半径 200km。 */
    L2(2, "HAPS", 18_000.0, 20_000.0, 200.0),
    /** L3 LEO 卫星：300-1200km，覆盖半径 1000km。 */
    L3(3, "LEO", 300_000.0, 1_200_000.0, 1000.0),
    /** L4 地面站/云端：层级路径端点。 */
    L4(4, "GROUND_STATION", 0.0, 0.0, 0.0);

    private final int layerNumber;
    private final String name;
    private final double altitudeLowerM;
    private final double altitudeUpperM;
    private final double coverageRadiusKm;

    RelayLayer(int layerNumber, String name, double altitudeLowerM,
               double altitudeUpperM, double coverageRadiusKm) {
        this.layerNumber = layerNumber;
        this.name = name;
        this.altitudeLowerM = altitudeLowerM;
        this.altitudeUpperM = altitudeUpperM;
        this.coverageRadiusKm = coverageRadiusKm;
    }

    public int layerNumber() {
        return layerNumber;
    }

    public String layerName() {
        return name;
    }

    public double altitudeLowerM() {
        return altitudeLowerM;
    }

    public double altitudeUpperM() {
        return altitudeUpperM;
    }

    /** 覆盖半径（km），L0/L4 返回 0（端点不覆盖）。 */
    public double coverageRadiusKm() {
        return coverageRadiusKm;
    }

    /** 是否为中继层（L1/L2/L3），L0/L4 为端点。 */
    public boolean isRelay() {
        return this == L1 || this == L2 || this == L3;
    }

    /** 是否为端点层（L0/L4），不承担中继转发。 */
    public boolean isEndpoint() {
        return this == L0 || this == L4;
    }

    /**
     * 按飞行高度判定所属层级（FR-5.2.1.2）。
     * <p>
     * 高度落在层级区间内返回对应层级，落在间隙（如 5km，介于 L1 与 L2 之间）返回 null。
     *
     * @param altitudeM 飞行高度（米）
     * @return 所属层级，不属于任何层级时返回 null
     */
    public static RelayLayer fromAltitude(double altitudeM) {
        for (RelayLayer layer : values()) {
            if (layer.isRelay()
                    && altitudeM >= layer.altitudeLowerM
                    && altitudeM <= layer.altitudeUpperM) {
                return layer;
            }
        }
        return null;
    }

    /** 按层级编号获取，非法编号返回 null（FR-5.2.1.7 禁止未定义层级）。 */
    public static RelayLayer fromNumber(int number) {
        for (RelayLayer layer : values()) {
            if (layer.layerNumber == number) {
                return layer;
            }
        }
        return null;
    }
}
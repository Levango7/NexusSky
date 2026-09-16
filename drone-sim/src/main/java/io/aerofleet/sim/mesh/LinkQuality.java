package io.aerofleet.sim.mesh;

/**
 * 链路质量分级枚举（M5 应急 mesh，FR-12）。
 * <p>
 * 按 RSSI（dBm）将链路分为四级，对应可视化颜色：
 * <pre>
 * EXCELLENT  RSSI >  -50 dBm  序数 0  绿色 #2ECC71
 * GOOD       -50 ≥ RSSI > -70 dBm  序数 1  蓝色 #3498DB
 * FAIR       -70 ≥ RSSI > -85 dBm  序数 2  橙色 #F39C12
 * POOR       -85 ≥ RSSI          序数 3  红色 #E74C3C
 * </pre>
 * 越界值（&gt;0 或 &lt;-120）按保守默认 POOR 处理。
 */
public enum LinkQuality {
    /** 优秀：RSSI &gt; -50 dBm，绿色 #2ECC71。 */
    EXCELLENT(0, "#2ECC71"),
    /** 良好：-50 ≥ RSSI &gt; -70 dBm，蓝色 #3498DB。 */
    GOOD(1, "#3498DB"),
    /** 一般：-70 ≥ RSSI &gt; -85 dBm，橙色 #F39C12。 */
    FAIR(2, "#F39C12"),
    /** 差：-85 ≥ RSSI，红色 #E74C3C。 */
    POOR(3, "#E74C3C");

    private final int ordinalCode;
    private final String color;

    LinkQuality(int ordinalCode, String color) {
        this.ordinalCode = ordinalCode;
        this.color = color;
    }

    /** 可视化颜色（CSS hex）。 */
    public String color() {
        return color;
    }

    /** 稳定序数（与 enum ordinal 一致，供 MAVLink 编码用）。 */
    public int code() {
        return ordinalCode;
    }

    /**
     * 按 RSSI（dBm）返回对应分级。
     * <p>
     * 阈值边界：-50 → GOOD，-70 → FAIR，-85 → POOR。
     * 越界值（&gt;0 或 &lt;-120）按 POOR 处理（保守默认）。
     *
     * @param rssiDbm RSSI 值（dBm）
     * @return 对应的链路质量分级
     */
    public static LinkQuality fromRssi(int rssiDbm) {
        // 越界值按 POOR 处理（保守默认）
        if (rssiDbm > 0 || rssiDbm < -120) {
            return POOR;
        }
        if (rssiDbm > -50) {
            return EXCELLENT;
        } else if (rssiDbm > -70) {
            return GOOD;
        } else if (rssiDbm > -85) {
            return FAIR;
        } else {
            return POOR;
        }
    }

    /** 按 ordinal 反查。 */
    public static LinkQuality fromCode(int code) {
        return switch (code) {
            case 0 -> EXCELLENT;
            case 1 -> GOOD;
            case 2 -> FAIR;
            default -> POOR;
        };
    }
}
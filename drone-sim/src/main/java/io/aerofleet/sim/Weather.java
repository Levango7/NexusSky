package io.aerofleet.sim;

/**
 * 天气状态枚举（FR-14）：CLEAR/CLOUDY/RAIN/SNOW/FOG + 能见度映射（FR-17）。
 * 能见度由天气状态推导，避免散落 if-else。
 */
public enum Weather {
    CLEAR(0, 10_000),   // 晴朗，能见度 10km
    CLOUDY(1, 10_000),  // 多云，能见度 10km
    RAIN(2, 3_000),     // 雨，能见度 3km
    SNOW(3, 1_000),     // 雪，能见度 1km
    FOG(4, 300);        // 雾，能见度 300m

    /** MAVLink 枚举 code（ENVIRONMENT_STATUS.weather 字段）。 */
    public final int code;
    /** 该天气下的默认能见度（米）。 */
    public final int visibilityM;

    Weather(int code, int visibilityM) {
        this.code = code;
        this.visibilityM = visibilityM;
    }

    /**
     * 按 code 查找天气状态，未知 code 回退 {@link #CLEAR}（FR-14 异常容错）。
     *
     * @param code 天气枚举 code
     * @return 对应 Weather，未知时返回 CLEAR
     */
    public static Weather of(int code) {
        for (Weather w : values()) {
            if (w.code == code) return w;
        }
        return CLEAR;  // 未知 code 回退 CLEAR
    }
}
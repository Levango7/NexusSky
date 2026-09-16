package io.aerofleet.sim.terrain;

/**
 * 飞行限制类型枚举（§7.5）。
 * <p>
 * 取值：
 * <pre>
 * NO_FLY=0          禁飞区
 * ALTITUDE_LIMIT=1  限高区
 * WIND_SHEAR_WARN=2 风切变告警区
 * </pre>
 */
public enum RestrictionType {
    NO_FLY(0),
    ALTITUDE_LIMIT(1),
    WIND_SHEAR_WARN(2);

    public final int code;

    RestrictionType(int code) {
        this.code = code;
    }

    public static RestrictionType fromCode(int code) {
        for (RestrictionType r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return NO_FLY;
    }
}